package io.github.jichuo1.meguviz.ui.text

import android.annotation.TargetApi
import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.PrecomputedText
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.BindingToken
import io.github.jichuo1.meguviz.ui.EpochDomain
import io.github.jichuo1.meguviz.ui.EpochToken
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.scheduler.SubmitResult
import java.util.concurrent.Executor

/**
 * Explicitly opt in for long, plain, immutable labels. Spanned or editable text uses its normal
 * setter; no future is awaited during measurement. Owner must invalidate after font/theme changes.
 *
 * Measurement parameters can change between submission and the result without any owner action,
 * for example when the layout direction resolves on attach. Such a result is never applied; the
 * latest text is recomputed with the current parameters, at most [MAX_PARAMETER_RETRIES] times,
 * after which (or if parameters change again before the frame) the normal setter applies it.
 */
class PrecomputedTextBinding<V : TextView>(
    session: UiSession,
    ref: ViewRef<V>,
    background: Executor,
    onFailure: (Throwable) -> Unit,
    private val enabled: Boolean = false,
    private val minimumLength: Int = 256,
) : AutoCloseable {
    private data class Input(
        val text: String,
        val params: Any,
        val config: Configuration,
        val density: Int,
        val version: EpochToken,
    )
    private val handler = Handler(Looper.getMainLooper())
    private val version = EpochDomain()
    private val slot = Any()
    private var owner: UiSession? = session
    private var reference: ViewRef<V>? = ref
    private var reservation: AutoCloseable? = null
    private var currentToken: BindingToken? = null
    private var failureHandler: ((Throwable) -> Unit)? = onFailure
    private var parameterRetries = 0
    @Volatile private var closed = false
    private val worker = LatestTextWork<Input, CharSequence>(background, Executor { task ->
        synchronized(handler) {
            if (!closed) check(handler.post(task)) { "Main looper rejected text result" }
        }
    }, { input -> Platform28.calculate(input.text, input.params) }, ::finish)

    init {
        require(minimumLength > 0)
        val owned = session.reserveProperties(ref, setOf("text"))
        reservation = owned
        try { session.onRelease(ref) { close() } } catch (failure: Throwable) {
            owned.close()
            reservation = null
            throw failure
        }
    }

    /** Returns false when a normal setter was queued instead of precomputation. */
    fun submit(value: CharSequence): Boolean {
        val session = checkNotNull(owner) { "Binding closed" }
        session.assertOwner()
        check(!closed)
        version.advance()
        val currentVersion = version.token()
        worker.invalidate()
        parameterRetries = 0
        val ref = checkNotNull(reference)
        val view = ref.get()
        val token = ref.token(resources = true)
        currentToken = token
        if (!enabled || Build.VERSION.SDK_INT < 28 || value !is String || value.length < minimumLength ||
            view is android.widget.EditText || view.filters.isNotEmpty()) {
            val copy = if (value is Spanned) SpannedString(value) else value.toString()
            val valid = { !closed && token.isCurrent() }
            val result = if (value is String && view !is android.widget.EditText && view.filters.isEmpty()) {
                session.scheduler.submitState(slot, isValid = { valid() && version.token() == currentVersion }) { ref.get().text = copy }
            } else session.scheduler.submitCommand(isValid = valid) { ref.get().text = copy }
            if (result is SubmitResult.Rejected) failureHandler?.invoke(IllegalStateException("Text update rejected: ${result.reason}"))
            return false
        }
        val input = Input(value, Platform28.params(view), Configuration(view.resources.configuration),
            view.resources.displayMetrics.densityDpi, currentVersion)
        worker.submit(input)
        return true
    }

    /** Explicitly invalidate when an external font or theme owner changes in place. */
    fun invalidate() { checkNotNull(owner).assertOwner(); version.advance(); currentToken = null; worker.invalidate() }

    private fun finish(input: Input, output: Result<CharSequence>) {
        val session = owner ?: return
        session.assertOwner()
        val ref = reference ?: return
        val token = currentToken ?: return
        if (closed || version.token() != input.version || !token.isCurrent()) return
        output.fold(onSuccess = { computed ->
            val view = ref.get()
            if (!currentParamsMatch(view, input)) {
                if (parameterRetries < MAX_PARAMETER_RETRIES && Build.VERSION.SDK_INT >= 28 &&
                    view !is android.widget.EditText && view.filters.isEmpty()) {
                    parameterRetries++
                    worker.submit(Input(input.text, Platform28.params(view), Configuration(view.resources.configuration),
                        view.resources.displayMetrics.densityDpi, input.version))
                } else applyPlain(session, ref, token, input)
                return@fold
            }
            val result = session.scheduler.submitState(slot, isValid = {
                !closed && version.token() == input.version && token.isCurrent()
            }) {
                // Parameters may still change before the frame; the setter keeps the latest text correct.
                val target = ref.get()
                target.text = if (currentParamsMatch(target, input)) computed else input.text
            }
            if (result is SubmitResult.Rejected) failureHandler?.invoke(IllegalStateException("Text update rejected: ${result.reason}"))
        }, onFailure = { failureHandler?.invoke(it) })
    }

    private fun applyPlain(session: UiSession, ref: ViewRef<V>, token: BindingToken, input: Input) {
        val result = session.scheduler.submitState(slot, isValid = {
            !closed && version.token() == input.version && token.isCurrent()
        }) { ref.get().text = input.text }
        if (result is SubmitResult.Rejected) failureHandler?.invoke(IllegalStateException("Text update rejected: ${result.reason}"))
    }

    private fun currentParamsMatch(view: V, input: Input): Boolean = Build.VERSION.SDK_INT >= 28 &&
        view !is android.widget.EditText && view.filters.isEmpty() &&
        view.resources.configuration == input.config && view.resources.displayMetrics.densityDpi == input.density &&
        Platform28.matches(Platform28.params(view), input.params)

    override fun close() {
        val session = owner ?: return
        session.assertOwner()
        if (closed) return
        synchronized(handler) {
            closed = true
            handler.removeCallbacksAndMessages(null)
        }
        version.advance()
        worker.close()
        currentToken = null
        failureHandler = null
        reference = null
        owner = null
        val owned = reservation
        reservation = null
        try { owned?.close() } finally { session.scheduler.discardInvalid() }
    }

    private companion object { private const val MAX_PARAMETER_RETRIES = 2 }

    // The object is private; submit() only creates work after an SDK 28 check.
    @SuppressLint("UseRequiresApi")
    @TargetApi(28)
    private object Platform28 {
        fun params(view: TextView): Any {
            val current = view.textMetricsParams
            val builder = PrecomputedText.Params.Builder(TextPaint(current.textPaint))
                .setTextDirection(current.textDirection)
                .setBreakStrategy(current.breakStrategy)
                .setHyphenationFrequency(current.hyphenationFrequency)
            if (Build.VERSION.SDK_INT >= 33) builder.setLineBreakConfig(current.lineBreakConfig)
            return builder.build()
        }
        fun matches(actual: Any, expected: Any): Boolean {
            val current = actual as PrecomputedText.Params
            val saved = expected as PrecomputedText.Params
            return current == saved && (Build.VERSION.SDK_INT < 33 || current.lineBreakConfig == saved.lineBreakConfig)
        }
        fun calculate(text: String, params: Any): CharSequence = PrecomputedText.create(text, params as PrecomputedText.Params)
    }
}

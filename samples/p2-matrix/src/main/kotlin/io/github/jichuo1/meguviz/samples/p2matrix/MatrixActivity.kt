package io.github.jichuo1.meguviz.samples.p2matrix

import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.CreationEnvironment
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import io.github.jichuo1.meguviz.ui.layouts.nativeRow
import io.github.jichuo1.meguviz.ui.plan.MountedUiPlan
import io.github.jichuo1.meguviz.ui.plan.PlanComponent
import io.github.jichuo1.meguviz.ui.plan.PlanNode
import io.github.jichuo1.meguviz.ui.plan.UiPlan
import io.github.jichuo1.meguviz.ui.plan.resolve
import io.github.jichuo1.meguviz.ui.plan.validate
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmStop
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmSubmission
import io.github.jichuo1.meguviz.ui.prewarm.PrewarmWork
import io.github.jichuo1.meguviz.ui.prewarm.UiPrewarmer
import io.github.jichuo1.meguviz.ui.recycler.UiListAdapter
import io.github.jichuo1.meguviz.ui.state.RestorationSnapshot
import io.github.jichuo1.meguviz.ui.state.ViewRestoration
import io.github.jichuo1.meguviz.ui.text.PrecomputedTextBinding
import io.github.jichuo1.meguviz.ui.widgets.FrameScope
import io.github.jichuo1.meguviz.ui.widgets.Framework
import io.github.jichuo1.meguviz.ui.widgets.LinearPolicy
import io.github.jichuo1.meguviz.ui.widgets.LinearScope
import io.github.jichuo1.meguviz.ui.widgets.MatchParent
import io.github.jichuo1.meguviz.ui.widgets.WrapContent
import io.github.jichuo1.meguviz.ui.widgets.frameParams
import io.github.jichuo1.meguviz.ui.widgets.linearParams
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@GenerateUiFactory(id = "matrix.label")
class MatrixLabel(context: Context) : TextView(context)

/** Same-process configuration overrides for instrumentation; the device's system settings are never changed. */
object MatrixOverrides {
    @Volatile var fontScale: Float? = null
    @Volatile var rtl: Boolean = false
    @Volatile var night: Boolean = false
    @Volatile var locale: String? = null
    @Volatile var prewarmPaused: Boolean = false
    fun reset() { fontScale = null; rtl = false; night = false; locale = null; prewarmPaused = false }
}

/** Immutable list model; only [on] changes through user input. */
data class MatrixRow(val id: Long, val on: Boolean)

/**
 * One screen holding every P2 surface. The model (toggles, opened page) is the authority that is
 * saved and restored; native hierarchy state supplies scroll position and focus for registered IDs.
 */
open class MatrixActivity : Activity() {
    lateinit var session: UiSession; private set
    lateinit var root: LinearLayout; private set
    /** Upper scrolling half holding every non-list surface. */
    lateinit var content: LinearLayout; private set
    lateinit var recycler: RecyclerView; private set
    lateinit var adapter: RowsAdapter; private set
    lateinit var longText: TextView; private set
    lateinit var textBinding: PrecomputedTextBinding<TextView>; private set
    lateinit var openPage: Button; private set
    lateinit var pageHost: FrameLayout; private set
    lateinit var planHost: LinearLayout; private set
    lateinit var plan: MountedUiPlan; private set
    var restoredFromState = false; private set
    var restoredIds: Map<String, Int> = emptyMap(); private set
    var prewarmStop: PrewarmStop? = null; private set
    var pageReady = false; private set
    var pageBuiltLazily = false; private set
    var stagedPage: WeakReference<View>? = null; private set
    val toggled = linkedSetOf<Long>()
    var pageOpen = false; private set
    /** List anchor (adapter position, pixel offset from the list top) at the last state save. */
    var savedAnchor: Pair<Int, Int>? = null; private set
    private var prewarmer: UiPrewarmer? = null
    private var staged: FrameLayout? = null
    private var stagedRef: ViewRef<LinearLayout>? = null
    private lateinit var restoration: ViewRestoration
    private lateinit var background: ExecutorService
    private val registered = linkedMapOf<String, ViewRef<*>>()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        val overrides = Configuration()
        MatrixOverrides.fontScale?.let { overrides.fontScale = it }
        if (MatrixOverrides.rtl) overrides.setLayoutDirection(Locale.forLanguageTag("ar"))
        MatrixOverrides.locale?.let { overrides.setLocale(Locale.forLanguageTag(it)) }
        if (MatrixOverrides.night) overrides.uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL
        applyOverrideConfiguration(overrides)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredFromState = savedInstanceState != null
        savedInstanceState?.getLongArray(KEY_TOGGLED)?.let { saved -> saved.forEach { toggled += it } }
        pageOpen = savedInstanceState?.getBoolean(KEY_PAGE) ?: false
        val snapshot = savedInstanceState?.getBundle(KEY_RESTORATION)?.let(RestorationSnapshot::fromBundle)
        restoredIds = snapshot?.ids.orEmpty().mapKeys { it.key.path }
        background = Executors.newSingleThreadExecutor()
        session = UiSession(CreationEnvironment(this))
        restoration = ViewRestoration(session, snapshot)
        val frame = FrameLayout(this)
        setContentView(frame)
        build(frame)
        registered.forEach { (path, ref) -> restoration.register(path, ref, frame) }
        restoration.restoreAfterLayout(frame)
        adapter.submitList(rows())
        if (pageOpen) openPage() else startPrewarm()
        frame.viewTreeObserver.addOnGlobalLayoutListener { logStateSoon() }
        // Scrolling repositions children without a global layout pass.
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) logStateSoon()
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun build(frame: FrameLayout) {
        val registry = RegistrySnapshot.install(listOf(
            MatrixLabelUiGenerated.registration(),
            FactoryRegistration(ExtensionDescriptor("text", "1"), Framework.text, Framework.textFactory),
        ))
        val recyclerType = ComponentType("matrix.recycler", RecyclerView::class.java)
        val recyclerFactory = ViewFactory { environment ->
            RecyclerView(environment.context).apply { layoutManager = LinearLayoutManager(environment.context) }
        }
        val rootRef = FrameScope(session, frame).linear(ViewKey("root"), frameParams(MatchParent, MatchParent), configure = {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }) {
            val topRef = view(ViewKey("top"), Framework.scroll, linearParams(MatchParent, 0) { weight = 1f }, Framework.scrollFactory)
            registered["root/top"] = topRef
            content = FrameScope(session, topRef.get()).linear(ViewKey("content"), frameParams(MatchParent, WrapContent), configure = {
                orientation = LinearLayout.VERTICAL
            }) {
                text(ViewKey("title"), linearParams()) {
                    setText(R.string.title); textSize = 22f
                    if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                }
                planHost = linear(ViewKey("planHost"), linearParams(), configure = { orientation = LinearLayout.VERTICAL }) {}.get()
                view(ViewKey("generated"), MatrixLabelUiGenerated.type, linearParams(), registry.resolve(MatrixLabelUiGenerated.type)) {
                    setText(R.string.generated_label)
                }
                val textRef = text(ViewKey("longText"), linearParams())
                longText = textRef.get()
                registered["root/longText"] = textRef
                textBinding = PrecomputedTextBinding(session, textRef, background, { error -> Log.e(TAG, "text", error) }, enabled = true)
                nativeRow(ViewKey("rowA"), linearParams(), configure = { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }) {
                    text(ViewKey("rowALabel"), linearParams(0, WrapContent) { weight = 1f }) { setText(R.string.row_label) }
                    registered["root/rowAButton"] = button(ViewKey("rowAButton"), linearParams(WrapContent, WrapContent)) { setText(R.string.row_action) }
                }
                nativeRow(ViewKey("rowB"), linearParams(), configure = { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(56) }) {
                    text(ViewKey("rowBLabel"), linearParams(0, WrapContent) { weight = 1f }) { setText(R.string.row_second_label); textSize = 18f }
                    registered["root/rowBButton"] = button(ViewKey("rowBButton"), linearParams(WrapContent, WrapContent)) { setText(R.string.row_second_action) }
                }
                val openRef = button(ViewKey("openPage"), linearParams(WrapContent, WrapContent)) {
                    setText(R.string.open_page); setOnClickListener { openPage() }
                }
                openPage = openRef.get()
                registered["root/openPage"] = openRef
                pageHost = frame(ViewKey("pageHost"), linearParams(MatchParent, WrapContent)) {}.get()
            }.get()
            val listRef = view(ViewKey("list"), recyclerType, linearParams(MatchParent, 0) { weight = 1f }, recyclerFactory)
            recycler = listRef.get()
            registered["root/list"] = listRef
        }
        root = rootRef.get()
        textBinding.submit(getString(R.string.long_text))
        val components = listOf(PlanComponent(Framework.text, "linear", LinearLayout::class.java, LinearPolicy, { w, h -> linearParams(w, h) }))
        plan = UiPlan(rootKind = "linear", nodes = listOf(PlanNode("planA", Framework.text.id), PlanNode("planB", Framework.text.id)))
            .validate().resolve(registry, components).bind(session, planHost, "linear").mount()
        plan.ref(listOf("planA"), Framework.text).get().setText(R.string.plan_first)
        plan.ref(listOf("planB"), Framework.text).get().setText(R.string.plan_second)
        registered["root/planA"] = plan.ref(listOf("planA"), Framework.text)
        adapter = RowsAdapter(this, background)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        recycler.adapter = adapter
    }

    fun rows(): List<MatrixRow> = (0 until ROWS).map { MatrixRow(it.toLong(), it.toLong() in toggled) }

    fun toggle(id: Long) {
        if (!toggled.remove(id)) toggled += id
        adapter.submitList(rows())
    }

    private fun startPrewarm() {
        val prewarmer = UiPrewarmer.attach(session, capacity = 1, maxUnitsPerWork = 600)
        this.prewarmer = prewarmer
        val container = FrameLayout(this)
        staged = container
        stagedPage = WeakReference(container)
        var built = 0
        val submission = prewarmer.submit(PrewarmWork(
            isCurrent = { true },
            step = {
                if (MatrixOverrides.prewarmPaused) false else {
                    val page = stagedRef ?: FrameScope(session, container).linear(ViewKey("page"), frameParams(), configure = {
                        orientation = LinearLayout.VERTICAL
                    }) {}.also { stagedRef = it }
                    val scope = LinearScope(session, page.get())
                    repeat(PAGE_ROWS_PER_UNIT) { scope.text(ViewKey("p$built"), linearParams()) { text = getString(R.string.page_row, built) }; built++ }
                    built >= PAGE_ROWS
                }
            },
            ready = { pageReady = true },
            discard = { reason ->
                prewarmStop = reason
                stagedRef?.let { if (session.state == io.github.jichuo1.meguviz.ui.SessionState.ACTIVE) session.release(it) }
                stagedRef = null
                staged = null
            },
        ))
        check(submission == PrewarmSubmission.Accepted) { "Prewarm rejected: $submission" }
    }

    /** Uses the prewarmed page when ready; otherwise cancels staging and builds the page now. */
    fun openPage() {
        pageOpen = true
        val ready = pageReady && staged != null
        if (!ready) {
            prewarmer?.cancel()
            val container = FrameLayout(this)
            FrameScope(session, container).linear(ViewKey("page"), frameParams(), configure = { orientation = LinearLayout.VERTICAL }) {
                repeat(PAGE_ROWS) { index -> text(ViewKey("p$index"), linearParams()) { text = getString(R.string.page_row, index) } }
            }
            staged = container
            pageBuiltLazily = true
        }
        val page = checkNotNull(staged)
        if (page.parent == null) pageHost.addView(page, FrameLayout.LayoutParams(MatchParent, WrapContent))
        openPage.isEnabled = false
    }

    override fun onStop() {
        if (!pageReady && !pageOpen) prewarmer?.onDetached()
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && !pageOpen) prewarmer?.onLowMemory()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!pageOpen) prewarmer?.onResourcesChanged()
        textBinding.invalidate()
        textBinding.submit(getString(R.string.long_text))
    }

    /** Adapter position and pixel offset of the first visible row, as LinearLayoutManager anchors it. */
    fun listAnchor(): Pair<Int, Int> {
        val manager = recycler.layoutManager as LinearLayoutManager
        val position = manager.findFirstVisibleItemPosition()
        val child = manager.findViewByPosition(position)
        return position to ((child?.top ?: 0) - recycler.paddingTop)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        savedAnchor = listAnchor()
        super.onSaveInstanceState(outState)
        outState.putLongArray(KEY_TOGGLED, toggled.toLongArray())
        outState.putBoolean(KEY_PAGE, pageOpen)
        outState.putBundle(KEY_RESTORATION, restoration.snapshot().toBundle())
    }

    private var logPending = false
    private var lastLogged: String? = null
    private fun logStateSoon() {
        if (logPending) return
        logPending = true
        root.post {
            logPending = false
            val state = stateJson()
            if (state != lastLogged) { lastLogged = state; Log.i(TAG, state) }
        }
    }

    fun stateJson(): String {
        val manager = recycler.layoutManager as LinearLayoutManager
        val focus = currentFocus
        return org.json.JSONObject().apply {
            put("pid", Process.myPid())
            put("restored", restoredFromState)
            put("items", adapter.itemCount)
            put("toggled", org.json.JSONArray(toggled.toList()))
            put("pageOpen", pageOpen)
            put("pageBuiltLazily", pageBuiltLazily)
            put("firstVisible", manager.findFirstVisibleItemPosition())
            put("focusId", focus?.id ?: View.NO_ID)
            put("prewarmStop", prewarmStop?.name ?: JSON_NULL)
        }.toString()
    }

    override fun onDestroy() {
        recycler.adapter = null
        adapter.close()
        session.close()
        background.shutdownNow()
        super.onDestroy()
    }

    class RowHolder(val row: LinearLayout, val label: TextView, val action: Button) : RecyclerView.ViewHolder(row)

    class RowsAdapter(private val activity: MatrixActivity, background: java.util.concurrent.Executor) :
        UiListAdapter<MatrixRow, RowHolder>(background, { it.id }, Callback, { error -> Log.e(TAG, "list", error) }) {
        var fullBinds = 0; private set
        var partialBinds = 0; private set
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val context = parent.context
            val label = TextView(context)
            val action = Button(context)
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(label, LinearLayout.LayoutParams(0, WrapContent, 1f))
                addView(action, LinearLayout.LayoutParams(WrapContent, WrapContent))
                layoutParams = RecyclerView.LayoutParams(MatchParent, WrapContent)
            }
            return RowHolder(row, label, action)
        }
        override fun bindFull(holder: RowHolder, item: MatrixRow) {
            fullBinds++
            holder.label.text = activity.getString(R.string.item_label, item.id)
            bindAction(holder, item)
        }
        override fun bindPartial(holder: RowHolder, item: MatrixRow, payloads: List<Any>) {
            partialBinds++
            bindAction(holder, item)
        }
        private fun bindAction(holder: RowHolder, item: MatrixRow) {
            holder.action.setText(if (item.on) R.string.item_on else R.string.item_off)
            holder.action.contentDescription = activity.getString(R.string.item_toggle, item.id)
            holder.action.setOnClickListener { activity.toggle(item.id) }
        }
        override fun onRebind(holder: RowHolder) { holder.action.setOnClickListener(null) }
    }

    private object Callback : DiffUtil.ItemCallback<MatrixRow>() {
        override fun areItemsTheSame(oldItem: MatrixRow, newItem: MatrixRow) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: MatrixRow, newItem: MatrixRow) = oldItem == newItem
        override fun getChangePayload(oldItem: MatrixRow, newItem: MatrixRow): Any = "on"
    }

    companion object {
        const val TAG = "P2Matrix"
        const val ROWS = 100
        const val PAGE_ROWS = 24
        const val PAGE_ROWS_PER_UNIT = 4
        private const val KEY_TOGGLED = "toggled"
        private const val KEY_PAGE = "pageOpen"
        private const val KEY_RESTORATION = "restoration"
        private const val JSON_NULL = "null"
    }
}

/** Declares rotation as handled, so prewarming observes an in-place resource change. */
class MatrixConfigActivity : MatrixActivity()

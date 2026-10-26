package io.github.jichuo1.meguviz.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup

@DslMarker
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE)
annotation class UiDsl

/** Process-local identity. Overflow replaces the domain instead of reviving old tokens. */
class EpochDomain {
    private var identity = Any()
    private var epoch = 0L
    fun token(): EpochToken = EpochToken(identity, epoch)
    fun advance() {
        if (epoch == Long.MAX_VALUE) { identity = Any(); epoch = 0 } else epoch++
    }
}

class EpochToken internal constructor(private val identity: Any, private val epoch: Long) {
    override fun equals(other: Any?): Boolean = other is EpochToken && identity === other.identity && epoch == other.epoch
    override fun hashCode(): Int = 31 * System.identityHashCode(identity) + epoch.hashCode()
}

/** A bridge supplies its authority through closures; UI does not increment an upstream epoch. */
class ResourceAuthority(val capture: () -> Any, val isCurrent: (Any) -> Boolean)

class CreationEnvironment(
    val context: Context,
    val resourceOwner: String = "application",
    val resourceAuthority: ResourceAuthority? = null,
)

fun interface ViewFactory<V : View> { fun create(environment: CreationEnvironment): V }

class ComponentType<V : View>(val id: String, val viewClass: Class<V>, val schema: Int = 1) {
    init { require(id.isNotBlank()); require(schema > 0) }
}

@JvmInline
value class ViewKey(val value: String) { init { require(value.isNotBlank()) } }

/** LP is invariant: a LinearLayout policy cannot accept FrameLayout parameters. */
interface ParentPolicy<P : ViewGroup, LP : ViewGroup.LayoutParams> {
    fun attach(parent: P, child: View, params: LP) { parent.addView(child, params) }
}

enum class NativeOwnership { CREATED, BORROWED, TRANSFERRED }

class BindingToken internal constructor(internal val current: () -> Boolean) {
    fun isCurrent(): Boolean = current()
}

class ViewRef<V : View> internal constructor(
    internal val session: UiSession,
    internal val identity: Long,
    val key: ViewKey,
    val component: ComponentType<V>,
) {
    /** Owner thread only; fails after this node or its session is released. */
    fun get(): V = session.resolve(this)
    fun token(resources: Boolean = false): BindingToken = session.token(this, resources)
}

package io.github.jichuo1.meguviz.ui.plan

import android.view.View
import android.view.ViewGroup
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.ParentPolicy
import io.github.jichuo1.meguviz.ui.UiSession
import io.github.jichuo1.meguviz.ui.ViewKey
import io.github.jichuo1.meguviz.ui.ViewRef
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot
import java.util.Collections

/** Pure description; references, factories and callbacks belong to resolved or bound plans. */
class PlanNode(
    val key: String,
    val componentId: String,
    val componentSchema: Int = 1,
    val width: Int = -1,
    val height: Int = -2,
    children: List<PlanNode> = emptyList(),
) {
    val children: List<PlanNode> = Collections.unmodifiableList(children.toList())
}

class UiPlan(
    val schemaMajor: Int = 1,
    val schemaMinor: Int = 0,
    val rootKind: String,
    nodes: List<PlanNode>,
) {
    val nodes: List<PlanNode> = Collections.unmodifiableList(nodes.toList())
}

class ValidatedUiPlan internal constructor(internal val plan: UiPlan)

/** Limits prevent unbounded work on input supplied by external configuration. */
fun UiPlan.validate(maxNodes: Int = 4096, maxDepth: Int = 64): ValidatedUiPlan {
    require(schemaMajor == 1 && schemaMinor == 0) { "Unsupported plan schema" }
    require(rootKind.isNotBlank() && maxNodes > 0 && maxDepth > 0)
    var count = 0
    fun visit(siblings: List<PlanNode>, depth: Int) {
        require(depth <= maxDepth) { "Plan depth exceeds limit" }
        val keys = HashSet<String>()
        for (node in siblings) {
            require(node.key.isNotBlank() && keys.add(node.key)) { "Empty or duplicate sibling key: ${node.key}" }
            require(node.componentId.isNotBlank() && node.componentSchema > 0) { "Invalid component identity" }
            require(node.width >= 0 || node.width == -1 || node.width == -2) { "Invalid width" }
            require(node.height >= 0 || node.height == -1 || node.height == -2) { "Invalid height" }
            require(++count <= maxNodes) { "Plan size exceeds limit" }
            if (node.children.isNotEmpty()) visit(node.children, depth + 1)
        }
    }
    visit(nodes, 1)
    return ValidatedUiPlan(this)
}

/** Application-owned native adapters, constructed separately from the pure plan. */
class PlanComponent<P : ViewGroup, LP : ViewGroup.LayoutParams, V : View>(
    val type: ComponentType<V>,
    val parentKind: String,
    val parentClass: Class<P>,
    val parentPolicy: ParentPolicy<P, LP>,
    val layoutParams: (Int, Int) -> LP,
    val childKind: String? = null,
) {
    init {
        require(parentKind.isNotBlank() && (childKind == null || childKind.isNotBlank()))
        require(childKind == null || ViewGroup::class.java.isAssignableFrom(type.viewClass)) { "Children require a ViewGroup" }
    }

    internal fun resolve(registry: RegistrySnapshot): ResolvedComponent {
        val factory = registry.resolve(type)
        return object : ResolvedComponent {
            override fun mount(session: UiSession, parent: ViewGroup, node: PlanNode): ViewRef<*> {
                val typedParent = requireNotNull(parentClass.cast(parent)) { "Parent type mismatch" }
                return session.mount(typedParent, parentPolicy, ViewKey(node.key), type,
                    layoutParams(node.width, node.height), factory)
            }
            override fun accepts(parent: ViewGroup): Boolean = parentClass.isInstance(parent)
        }
    }
}

internal interface ResolvedComponent {
    fun accepts(parent: ViewGroup): Boolean
    fun mount(session: UiSession, parent: ViewGroup, node: PlanNode): ViewRef<*>
}

internal class ResolvedNode(val node: PlanNode, val component: ResolvedComponent, val children: List<ResolvedNode>)

class ResolvedUiPlan internal constructor(internal val rootKind: String, internal val nodes: List<ResolvedNode>) {
    /** Binding does not mount. The owner retains the session and native root. */
    fun bind(session: UiSession, root: ViewGroup, rootKind: String): BoundUiPlan {
        session.assertOwner()
        require(this.rootKind == rootKind) { "Root parent kind mismatch" }
        require(nodes.all { it.component.accepts(root) }) { "Root parent type mismatch" }
        return BoundUiPlan(session, root, nodes)
    }
}

fun ValidatedUiPlan.resolve(registry: RegistrySnapshot, components: List<PlanComponent<*, *, *>>): ResolvedUiPlan {
    require(components.map { it.type.id to it.parentKind }.distinct().size == components.size) { "Duplicate plan component and parent kind" }
    components.groupBy { it.type.id }.values.forEach { group ->
        val type = group.first().type
        require(group.all { it.type.schema == type.schema && it.type.viewClass === type.viewClass }) { "Component identity differs across parent kinds" }
    }
    val adapters = components.associateBy { it.type.id to it.parentKind }
    val resolved = mutableMapOf<Pair<String, String>, ResolvedComponent>()
    fun visit(nodes: List<PlanNode>, parentKind: String): List<ResolvedNode> = nodes.map { node ->
        val key = node.componentId to parentKind
        val adapter = requireNotNull(adapters[key]) { "Unknown component or parent kind: ${node.componentId}/$parentKind" }
        require(adapter.type.schema == node.componentSchema) { "Component schema mismatch" }
        require(adapter.parentKind == parentKind) { "Parent kind mismatch for ${node.key}" }
        val childKind = adapter.childKind
        require(node.children.isEmpty() || childKind != null) { "Component cannot have children" }
        val handle = resolved.getOrPut(key) { adapter.resolve(registry) }
        ResolvedNode(node, handle, if (childKind == null) emptyList() else visit(node.children, childKind))
    }
    return ResolvedUiPlan(plan.rootKind, visit(plan.nodes, plan.rootKind))
}

class BoundUiPlan internal constructor(
    session: UiSession,
    root: ViewGroup,
    nodes: List<ResolvedNode>,
) : AutoCloseable {
    private var owner: UiSession? = session
    private var parent: ViewGroup? = root
    private var entries: List<ResolvedNode>? = nodes
    override fun close() {
        val session = owner ?: return
        session.assertOwner()
        owner = null; parent = null; entries = null
    }
    fun mount(): MountedUiPlan {
        val session = checkNotNull(owner) { "A bound plan can be mounted only once" }
        session.assertOwner()
        val root = checkNotNull(parent)
        val nodes = checkNotNull(entries)
        owner = null; parent = null; entries = null
        val roots = mutableListOf<ViewRef<*>>()
        val refs = mutableMapOf<List<String>, ViewRef<*>>()
        fun mountChildren(parent: ViewGroup, nodes: List<ResolvedNode>, path: List<String>, top: Boolean) {
            for (resolved in nodes) {
                require(resolved.component.accepts(parent)) { "Actual parent type mismatch" }
                val ref = resolved.component.mount(session, parent, resolved.node)
                if (top) roots += ref
                val childPath = path + resolved.node.key
                refs[childPath] = ref
                if (resolved.children.isNotEmpty()) {
                    val childParent = ref.get() as? ViewGroup ?: error("Resolved component is not a ViewGroup")
                    mountChildren(childParent, resolved.children, childPath, false)
                }
            }
        }
        try { mountChildren(root, nodes, emptyList(), true) }
        catch (failure: Throwable) {
            roots.asReversed().forEach { try { session.release(it) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
            throw failure
        }
        return MountedUiPlan(session, roots.toList(), refs.toMap())
    }
}

class MountedUiPlan internal constructor(
    session: UiSession,
    roots: List<ViewRef<*>>,
    refs: Map<List<String>, ViewRef<*>>,
) : AutoCloseable {
    private var owner: UiSession? = session
    private var rootRefs: List<ViewRef<*>> = roots
    private var viewRefs: Map<List<String>, ViewRef<*>> = refs
    fun <V : View> ref(path: List<String>, type: ComponentType<V>): ViewRef<V> {
        val session = checkNotNull(owner) { "Plan is closed" }
        session.assertOwner()
        val ref = requireNotNull(viewRefs[path]) { "Unknown plan path" }
        require(ref.component.id == type.id && ref.component.schema == type.schema && ref.component.viewClass === type.viewClass) { "Component type mismatch" }
        @Suppress("UNCHECKED_CAST")
        return ref as ViewRef<V>
    }
    override fun close() {
        val session = owner ?: return
        session.assertOwner()
        val roots = rootRefs
        owner = null; rootRefs = emptyList(); viewRefs = emptyMap()
        var failure: Throwable? = null
        roots.asReversed().forEach {
            try { session.release(it) } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}

package io.github.jichuo1.meguviz.ui.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.ParentPolicy
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration
import io.github.jichuo1.meguviz.ui.extensions.RegistrySnapshot

class UiPlanValidationTest {
    @Test fun rejectsUnknownSchemaDuplicateKeysAndInvalidSizes() {
        assertThrows(IllegalArgumentException::class.java) { UiPlan(schemaMajor = 2, rootKind = "frame", nodes = emptyList()).validate() }
        assertThrows(IllegalArgumentException::class.java) { UiPlan(rootKind = "frame", nodes = listOf(PlanNode("x", "text"), PlanNode("x", "text"))).validate() }
        assertThrows(IllegalArgumentException::class.java) { UiPlan(rootKind = "frame", nodes = listOf(PlanNode("x", "text", width = -3))).validate() }
    }
    @Test fun boundsNestedInputAndCopiesCallerCollections() {
        val children = mutableListOf(PlanNode("child", "text"))
        val nodes = mutableListOf(PlanNode("parent", "container", children = children))
        val plan = UiPlan(rootKind = "frame", nodes = nodes)
        children.clear(); nodes.clear()
        assertEquals(1, plan.nodes.size)
        assertEquals(1, plan.nodes.single().children.size)
        assertThrows(IllegalArgumentException::class.java) { plan.validate(maxNodes = 1) }
        assertThrows(IllegalArgumentException::class.java) { plan.validate(maxDepth = 1) }
        plan.validate(maxNodes = 2, maxDepth = 2)
    }
    @Test fun resolveChecksParentAndSchemaAndCachesTypedHandles() {
        val type = ComponentType("test.text", TextView::class.java)
        val factory = ViewFactory<TextView> { error("resolution must not construct a View") }
        val registry = RegistrySnapshot.install(listOf(FactoryRegistration(ExtensionDescriptor("test", "1"), type, factory)))
        val adapter = PlanComponent(type, "frame", FrameLayout::class.java,
            object : ParentPolicy<FrameLayout, FrameLayout.LayoutParams> {}, { w, h -> FrameLayout.LayoutParams(w, h) })
        val nodes = listOf(PlanNode("a", "test.text"), PlanNode("b", "test.text"))
        assertThrows(IllegalArgumentException::class.java) { UiPlan(rootKind = "linear", nodes = nodes).validate().resolve(registry, listOf(adapter)) }
        assertThrows(IllegalArgumentException::class.java) { UiPlan(rootKind = "frame", nodes = listOf(PlanNode("a", "test.text", componentSchema = 2))).validate().resolve(registry, listOf(adapter)) }
        UiPlan(rootKind = "frame", nodes = nodes).validate().resolve(registry, listOf(adapter))
        assertEquals(1L, registry.candidateChecks)
        assertThrows(IllegalArgumentException::class.java) { UiPlan(rootKind = "frame", nodes = listOf(PlanNode("a", "test.text", children = listOf(PlanNode("b", "test.text"))))).validate().resolve(registry, listOf(adapter)) }
    }
}

package io.github.jichuo1.meguviz.samples.p2benchmark

import android.content.Context
import android.widget.TextView
import io.github.jichuo1.meguviz.ui.ComponentType
import io.github.jichuo1.meguviz.ui.ViewFactory
import io.github.jichuo1.meguviz.ui.codegen.GenerateUiFactory
import io.github.jichuo1.meguviz.ui.extensions.ExtensionDescriptor
import io.github.jichuo1.meguviz.ui.extensions.FactoryRegistration

/** One View class for both codegen groups, so only the factory origin differs. */
@GenerateUiFactory(id = "bench.label")
class BenchLabel(context: Context) : TextView(context)

/** The hand-written registration the generated one replaces. */
object HandWrittenBenchLabel {
    val type = ComponentType("bench.label", BenchLabel::class.java, 1)
    val factory = ViewFactory<BenchLabel> { environment -> BenchLabel(environment.context) }
    fun registration(): FactoryRegistration<BenchLabel> = FactoryRegistration(
        ExtensionDescriptor("handwritten:bench.label", "1.0.0"), type, factory
    )
}

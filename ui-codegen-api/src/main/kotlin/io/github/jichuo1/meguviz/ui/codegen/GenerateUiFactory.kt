package io.github.jichuo1.meguviz.ui.codegen

/** Explicit declaration for a public concrete View with a public Context constructor. */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateUiFactory(val id: String, val schema: Int = 1)

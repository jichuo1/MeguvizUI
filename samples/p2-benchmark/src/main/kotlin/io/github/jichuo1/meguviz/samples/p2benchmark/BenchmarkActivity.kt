package io.github.jichuo1.meguviz.samples.p2benchmark

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/** Keeps the measured process foreground with a visible window, so platform frames keep arriving. */
class BenchmarkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(FrameLayout(this))
    }
}

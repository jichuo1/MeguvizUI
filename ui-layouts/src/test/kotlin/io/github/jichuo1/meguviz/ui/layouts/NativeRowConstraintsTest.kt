package io.github.jichuo1.meguviz.ui.layouts

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeRowConstraintsTest {
    @Test fun acceptsEmptyAndMaximumInitialChildren() {
        val constraints = NativeRowConstraints(maxChildren = 2)
        constraints.checkChildCount(0)
        constraints.checkChildCount(2)
    }

    @Test fun rejectsOutOfRangeBoundsAndExcessChildren() {
        assertThrows(IllegalArgumentException::class.java) { NativeRowConstraints(0) }
        assertThrows(IllegalArgumentException::class.java) { NativeRowConstraints(33) }
        val constraints = NativeRowConstraints(maxChildren = 2)
        assertThrows(IllegalArgumentException::class.java) { constraints.checkChildCount(-1) }
        assertThrows(IllegalArgumentException::class.java) { constraints.checkChildCount(3) }
    }
}

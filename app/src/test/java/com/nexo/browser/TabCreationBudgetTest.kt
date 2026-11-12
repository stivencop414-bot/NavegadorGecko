package com.nexo.browser
import org.junit.Assert.*
import org.junit.Test
class TabCreationBudgetTest {
    @Test fun limitsBurstsAndRecoversAfterTheWindow() {
        val p = TabCreationBudget()
        repeat(4) { assertTrue(p.allow(1000L)) }
        repeat(10) { assertFalse(p.allow(1001L)) }
        assertTrue(p.allow(11000L))
    }
    @Test fun limitsTheWholeMinuteAsWell() {
        val p = TabCreationBudget()
        repeat(5) { window -> repeat(4) { assertTrue(p.allow(window * 10000L)) } }
        assertFalse(p.allow(50000L))
        assertTrue(p.allow(60000L))
    }
}

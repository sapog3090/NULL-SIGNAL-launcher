package com.nullsignal.core

import com.nullsignal.core.breach.Breach
import org.junit.Assert.assertEquals
import org.junit.Test

class BreachDefaultTest {
    @Test fun `random game can be created`() {
        repeat(200) {
            val g = Breach()
            assertEquals(4, g.secret.length)
            assertEquals(Breach.State.RUNNING, g.state)
        }
    }
}

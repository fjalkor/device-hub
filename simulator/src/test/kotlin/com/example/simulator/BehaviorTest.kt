package com.example.simulator

import kotlin.test.Test
import kotlin.test.assertEquals

class BehaviorTest {

    @Test
    fun `last devices get one faulty behavior each`() {
        val behaviors = (0 until 10).map { behaviorFor(it, deviceCount = 10) }

        assertEquals(List(6) { Behavior.NORMAL } + listOf(Behavior.REJECTING, Behavior.DEAF, Behavior.HANGING, Behavior.FLAKY), behaviors)
    }

    @Test
    fun `few devices get the first faulty behaviors that fit`() {
        val behaviors = (0 until 2).map { behaviorFor(it, deviceCount = 2) }

        assertEquals(listOf(Behavior.HANGING, Behavior.FLAKY), behaviors)
    }
}

package com.freespace.guide.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class GroundLossTest {

    private fun reading(groundVisible: Boolean): SceneReading {
        val reading = SceneReading(9, 72)
        val span = 0.36f
        for (sector in 0 until 9) {
            reading.bearings[sector] = -span + (sector + 0.5f) * (2f * span / 9)
            reading.ranges[sector] = 5f
        }
        reading.corridorRange = 5f
        reading.groundVisible = groundVisible
        return reading
    }

    @Test
    fun `qualche frame senza pavimento non cambia stato`() {
        val guidance = Guidance()
        repeat(5) {
            assertEquals(Alert.CLEAR, guidance.evaluate(reading(groundVisible = false)).alert)
        }
    }

    @Test
    fun `una perdita prolungata viene segnalata`() {
        val guidance = Guidance()
        repeat(20) { guidance.evaluate(reading(groundVisible = false)) }
        assertEquals(Alert.NO_GROUND, guidance.evaluate(reading(groundVisible = false)).alert)
    }

    @Test
    fun `il ritorno del pavimento ha effetto immediato`() {
        val guidance = Guidance()
        repeat(20) { guidance.evaluate(reading(groundVisible = false)) }
        assertEquals(Alert.NO_GROUND, guidance.evaluate(reading(groundVisible = false)).alert)
        assertEquals(Alert.CLEAR, guidance.evaluate(reading(groundVisible = true)).alert)
    }

    @Test
    fun `un'oscillazione a ogni passo non genera alcun annuncio`() {
        val guidance = Guidance()

        val states = (0 until 40).map { guidance.evaluate(reading(it % 3 != 0)).alert }
        assertEquals(listOf(Alert.CLEAR), states.distinct())
    }
}

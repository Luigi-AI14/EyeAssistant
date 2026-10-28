package com.freespace.guide.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceTest {

    private val sectors = 9

    private fun reading(vararg ranges: Float): SceneReading {
        val reading = SceneReading(sectors, 72)
        val span = 0.36f
        for (sector in 0 until sectors) {
            reading.bearings[sector] = -span + (sector + 0.5f) * (2f * span / sectors)
            reading.ranges[sector] = ranges.getOrElse(sector) { ranges.last() }
        }

        reading.corridorRange = (3..5).minOf { reading.ranges[it] }
        reading.groundVisible = true
        return reading
    }

    private fun uniform(range: Float) = reading(*FloatArray(sectors) { range })

    @Test
    fun `pavimento sgombro non genera allarmi`() {
        val cue = Guidance().evaluate(uniform(5f))
        assertEquals(Alert.CLEAR, cue.alert)
        assertEquals(Detour.NONE, cue.detour)
    }

    @Test
    fun `i livelli salgono man mano che l'ostacolo si avvicina`() {
        val guidance = Guidance()
        assertEquals(Alert.CLEAR, guidance.evaluate(uniform(4.0f)).alert)
        assertEquals(Alert.CAUTION, guidance.evaluate(uniform(2.5f)).alert)
        assertEquals(Alert.WARNING, guidance.evaluate(uniform(1.2f)).alert)
        assertEquals(Alert.STOP, guidance.evaluate(uniform(0.5f)).alert)
    }

    @Test
    fun `una distanza che oscilla sulla soglia non fa oscillare il livello`() {
        val guidance = Guidance()
        guidance.evaluate(uniform(1.2f))

        val levels = listOf(1.65f, 1.55f, 1.70f, 1.58f, 1.72f)
            .map { guidance.evaluate(uniform(it)).alert }
        assertTrue("livelli osservati: $levels", levels.all { it == Alert.WARNING })

        assertEquals(Alert.CAUTION, guidance.evaluate(uniform(2.1f)).alert)
    }

    @Test
    fun `un varco a sinistra manda a sinistra`() {

        val ranges = FloatArray(sectors) { 1.0f }
        ranges[0] = 4.5f
        ranges[1] = 4.5f
        val cue = Guidance().evaluate(reading(*ranges))

        assertEquals(Alert.WARNING, cue.alert)
        assertEquals(Detour.LEFT, cue.detour)
        assertTrue("il rilevamento consigliato deve essere negativo", cue.headingBearing < 0f)
    }

    @Test
    fun `un varco largo un solo settore non viene proposto`() {

        val ranges = FloatArray(sectors) { 0.9f }
        ranges[2] = 5f
        val cue = Guidance().evaluate(reading(*ranges))
        assertEquals(Detour.BLOCKED, cue.detour)
    }

    @Test
    fun `senza pavimento inquadrato non si annunciano ostacoli`() {
        val guidance = Guidance()

        lateinit var cue: Cue
        repeat(20) { cue = guidance.evaluate(uniform(5f).apply { groundVisible = false }) }
        assertEquals(Alert.NO_GROUND, cue.alert)
        assertEquals(Detour.NONE, cue.detour)
    }

    @Test
    fun `il cambio di stato viene segnalato una volta sola`() {
        val guidance = Guidance()
        guidance.evaluate(uniform(4f))
        assertTrue("il passaggio a CAUTION e' un cambio", guidance.evaluate(uniform(2.5f)).changed)
        assertTrue("restare in CAUTION non lo e'", !guidance.evaluate(uniform(2.4f)).changed)
    }
}

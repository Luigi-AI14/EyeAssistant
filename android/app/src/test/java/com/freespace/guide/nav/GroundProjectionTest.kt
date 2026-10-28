package com.freespace.guide.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class GroundProjectionTest {

    private val width = 288
    private val height = 512
    private val focal = 386f
    private val projection = GroundProjection(focal, width / 2f, height / 2f)

    private fun tilted(degrees: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        return floatArrayOf(0f, cos(radians).toFloat(), sin(radians).toFloat())
    }

    @Test
    fun `il centro dell'immagine cade dove dice la tangente`() {
        val height = 1.2f
        for (degrees in listOf(15.0, 30.0, 45.0, 60.0)) {
            val ground = projection.project(width / 2f, this.height / 2f, tilted(degrees), height)
            assertNotNull("nessuna intersezione a $degrees gradi", ground)
            val expected = height / tan(Math.toRadians(degrees)).toFloat()
            assertEquals("a $degrees gradi", expected, ground!!.forward, expected * 0.01f)
            assertEquals("nessuna deriva laterale", 0f, ground.lateral, 1e-3f)
        }
    }

    @Test
    fun `sopra l'orizzonte non c'e' pavimento`() {

        assertNull(projection.project(width / 2f, height / 2f, tilted(0.0), 1.2f))

        assertNull(projection.project(width / 2f, 10f, tilted(20.0), 1.2f))
    }

    @Test
    fun `piu' in basso nell'immagine significa piu' vicino`() {
        val up = tilted(35.0)
        var previous = Float.MAX_VALUE
        for (row in listOf(300, 350, 400, 450, 500)) {
            val ground = projection.project(width / 2f, row.toFloat(), up, 1.2f)
            assertNotNull("riga $row", ground)
            assertTrue("la riga $row dovrebbe essere piu' vicina", ground!!.forward < previous)
            previous = ground.forward
        }
    }

    @Test
    fun `l'altezza della fotocamera scala le distanze linearmente`() {
        val up = tilted(30.0)
        val low = projection.project(width / 2f, 400f, up, 1.0f)!!
        val high = projection.project(width / 2f, 400f, up, 2.0f)!!
        assertEquals(2f * low.forward, high.forward, low.forward * 0.01f)
    }

    @Test
    fun `una colonna a destra da' un rilevamento a destra`() {
        val up = tilted(30.0)
        val right = projection.project(width - 20f, 400f, up, 1.2f)!!
        val left = projection.project(20f, 400f, up, 1.2f)!!
        assertTrue("la colonna destra deve avere rilevamento positivo", right.bearing > 0.05f)
        assertTrue("la colonna sinistra deve avere rilevamento negativo", left.bearing < -0.05f)
        assertEquals("simmetrici rispetto al centro", -left.bearing, right.bearing, 0.02f)
    }

    @Test
    fun `la rotazione attorno all'asse ottico non sposta il punto centrale`() {
        val pitch = Math.toRadians(30.0)
        val roll = Math.toRadians(20.0)

        val up = floatArrayOf(
            (sin(roll) * cos(pitch)).toFloat(),
            (cos(roll) * cos(pitch)).toFloat(),
            sin(pitch).toFloat(),
        )
        val ground = projection.project(width / 2f, height / 2f, up, 1.2f)
        assertNotNull(ground)
        val expected = 1.2f / tan(pitch).toFloat()
        assertEquals(expected, ground!!.forward, expected * 0.02f)
        assertEquals("il centro resta dritto davanti", 0f, ground.lateral, 0.05f)
    }

    @Test
    fun `l'orizzonte sale nell'immagine quando il telefono si abbassa`() {
        val flat = projection.horizonRow(tilted(0.0))!!
        val tilted = projection.horizonRow(tilted(30.0))!!
        assertEquals("telefono verticale: orizzonte al centro", height / 2f, flat, 1f)
        assertTrue("inclinando in basso l'orizzonte sale", tilted < flat)
    }
}

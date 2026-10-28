package com.freespace.guide.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class FreeSpaceAnalyzerTest {

    private val maskWidth = 72
    private val maskHeight = 128
    private val stride = 4
    private val focal = 386f
    private val projection = GroundProjection(focal, maskWidth * stride / 2f, maskHeight * stride / 2f)

    private fun analyzer() = FreeSpaceAnalyzer(
        maskWidth, maskHeight, stride, projection, focal, sectorCount = 9
    )

    private fun tilted(degrees: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        return floatArrayOf(0f, cos(radians).toFloat(), sin(radians).toFloat())
    }

    private fun mask(freeFromRow: Int, blockedColumns: IntRange? = null, blockedFrom: Int = 0) =
        ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (row in 0 until maskHeight) {
                for (column in 0 until maskWidth) {
                    val blocked = blockedColumns?.contains(column) == true && row >= blockedFrom
                    val free = row >= freeFromRow && !blocked
                    put(row * maskWidth + column, if (free) 255.toByte() else 0)
                }
            }
        }

    @Test
    fun `pavimento libero da una riga nota da' la distanza attesa`() {
        val freeFromRow = 64
        val reading = analyzer().analyse(mask(freeFromRow), tilted(35.0), 1.2f, 0.5f)

        val boundary = freeFromRow.toFloat() * stride
        val expected = projection.project(
            maskWidth * stride / 2f, boundary, tilted(35.0), 1.2f
        )!!.range

        val central = reading.ranges[reading.ranges.size / 2]
        assertEquals(expected, central, expected * 0.05f)
        assertTrue("il pavimento e' inquadrato", reading.groundVisible)
    }

    @Test
    fun `un ostacolo su un lato accorcia solo i settori di quel lato`() {
        val analyzer = analyzer()
        val up = tilted(35.0)

        val reading = analyzer.analyse(
            mask(freeFromRow = 40, blockedColumns = 55 until maskWidth, blockedFrom = 90),
            up, 1.2f, 0.5f
        )

        val left = reading.ranges.first()
        val right = reading.ranges.last()
        assertTrue("il lato ostruito deve risultare piu' vicino ($right vs $left)", right < left)
    }

    @Test
    fun `un ostacolo ai piedi da' distanza quasi nulla`() {

        val reading = analyzer().analyse(mask(freeFromRow = maskHeight), tilted(40.0), 1.2f, 0.5f)
        val central = reading.ranges[reading.ranges.size / 2]
        assertTrue("atteso vicinissimo, ottenuto $central", central < 1.0f)
    }

    @Test
    fun `telefono puntato in alto segnala pavimento non inquadrato`() {
        val reading = analyzer().analyse(mask(freeFromRow = 0), tilted(-20.0), 1.2f, 0.5f)
        assertFalse(reading.groundVisible)
    }

    @Test
    fun `telefono verticale vede comunque il pavimento vicino`() {
        val reading = analyzer().analyse(mask(freeFromRow = 40), tilted(0.0), 1.2f, 0.5f)
        assertTrue(reading.groundVisible)
    }

    @Test
    fun `il filtro temporale reagisce subito a un ostacolo che compare`() {
        val analyzer = analyzer()
        val up = tilted(35.0)
        analyzer.analyse(mask(freeFromRow = 20), up, 1.2f, 0.5f)
        val far = analyzer.analyse(mask(freeFromRow = 20), up, 1.2f, 0.5f)
            .ranges[4]

        val near = analyzer.analyse(mask(freeFromRow = 110), up, 1.2f, 0.5f).ranges[4]
        assertTrue("atteso un calo netto: $far -> $near", near < far * 0.6f)
    }

    @Test
    fun `la soglia di probabilita' sposta il confine`() {
        val analyzer = analyzer()
        val buffer = ByteBuffer.allocate(maskWidth * maskHeight)
        for (row in 0 until maskHeight) {

            val value = if (row >= 64) 150.toByte() else 0
            for (column in 0 until maskWidth) buffer.put(row * maskWidth + column, value)
        }
        val up = tilted(35.0)

        val permissive = analyzer.analyse(buffer, up, 1.2f, 0.5f).ranges[4]
        val strict = FreeSpaceAnalyzer(maskWidth, maskHeight, stride, projection, focal, 9)
            .analyse(buffer, up, 1.2f, 0.7f).ranges[4]

        assertTrue("con soglia 0.7 nulla e' libero, la distanza crolla", strict < permissive)
    }

    @Test
    fun `distanza e riga sono coerenti con la tangente`() {

        val degrees = 30.0
        val up = tilted(degrees)
        val freeFromRow = 80
        val reading = analyzer().analyse(mask(freeFromRow), up, 1.5f, 0.5f)

        val v = freeFromRow.toFloat() * stride
        val rayAngle = kotlin.math.atan((v - maskHeight * stride / 2f) / focal)
        val depression = Math.toRadians(degrees) + rayAngle
        val expected = 1.5f / tan(depression).toFloat()

        assertEquals(expected, reading.ranges[4], expected * 0.05f)
    }
}

package com.freespace.guide.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class CorridorTest {

    private val maskWidth = 72
    private val maskHeight = 128
    private val stride = 4
    private val focal = 386f
    private val projection =
        GroundProjection(focal, maskWidth * stride / 2f, maskHeight * stride / 2f)

    private fun analyzer() =
        FreeSpaceAnalyzer(maskWidth, maskHeight, stride, projection, focal, sectorCount = 9)

    private fun tilted(degrees: Double): FloatArray {
        val radians = Math.toRadians(degrees)
        return floatArrayOf(0f, cos(radians).toFloat(), sin(radians).toFloat())
    }

    private fun corridor(sideColumns: Int, sideFreeBelowRow: Int) =
        ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (row in 0 until maskHeight) {
                for (column in 0 until maskWidth) {
                    val isSide = column < sideColumns || column >= maskWidth - sideColumns
                    val free = if (isSide) row >= sideFreeBelowRow else row >= 20
                    put(row * maskWidth + column, if (free) 255.toByte() else 0)
                }
            }
        }

    @Test
    fun `gli stipiti ai lati non accorciano la strada davanti`() {
        val up = tilted(35.0)

        val reading = analyzer().analyse(corridor(maskWidth / 4, 60), up, 1.2f, 0.5f)

        assertTrue(
            "il corridoio centrale deve restare libero, letto ${reading.corridorRange} m",
            reading.corridorRange > 2.5f
        )

        assertTrue("il settore piu' a sinistra deve vedere l'ostacolo",
            reading.ranges.first() < reading.corridorRange)
    }

    @Test
    fun `un ostacolo davvero sul cammino accorcia la strada`() {
        val up = tilted(35.0)

        val mask = ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (row in 0 until maskHeight) {
                for (column in 0 until maskWidth) {
                    val central = column in (maskWidth / 2 - 8) until (maskWidth / 2 + 8)
                    val free = if (central) row in 0 until 100 else row >= 20
                    put(row * maskWidth + column, if (free) 255.toByte() else 0)
                }
            }
        }
        val reading = analyzer().analyse(mask, up, 1.2f, 0.5f)
        assertTrue(
            "atteso vicino, letto ${reading.corridorRange} m",
            reading.corridorRange < 1.5f
        )
    }

    @Test
    fun `pavimento completamente sgombro da la portata massima`() {
        val mask = ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (index in 0 until maskWidth * maskHeight) put(index, 255.toByte())
        }
        val reading = analyzer().analyse(mask, tilted(35.0), 1.2f, 0.5f)
        assertTrue(reading.corridorRange >= FreeSpaceAnalyzer.MAX_RANGE - 0.01f)
    }

    private fun blocking(columns: IntRange) =
        ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (row in 0 until maskHeight) {
                for (column in 0 until maskWidth) {
                    val free = if (column in columns) row in 0 until 100 else row >= 20
                    put(row * maskWidth + column, if (free) 255.toByte() else 0)
                }
            }
        }

    @Test
    fun `la direzione del corridoio segue il lato dell'ostacolo`() {
        val up = tilted(35.0)

        val fromLeft = analyzer().analyse(blocking(8..20), up, 1.2f, 0.5f)
        val leftBearing = fromLeft.corridorBearing
        val leftRange = fromLeft.corridorRange

        val fromRight = analyzer().analyse(blocking(51..63), up, 1.2f, 0.5f)
        val rightBearing = fromRight.corridorBearing

        assertTrue("l'ostacolo deve essere dentro il corridoio, letto ${'$'}leftRange m",
            leftRange < 1.5f)
        assertTrue("ostacolo a sinistra, atteso negativo, letto ${'$'}leftBearing",
            leftBearing < -0.1f)
        assertTrue("ostacolo a destra, atteso positivo, letto ${'$'}rightBearing",
            rightBearing > 0.1f)
    }

    @Test
    fun `corridoio sgombro non indica nessuna direzione`() {
        val mask = ByteBuffer.allocate(maskWidth * maskHeight).apply {
            for (index in 0 until maskWidth * maskHeight) put(index, 255.toByte())
        }
        val reading = analyzer().analyse(mask, tilted(35.0), 1.2f, 0.5f)
        assertEquals(0f, reading.corridorBearing, 1e-4f)
    }
}

package com.freespace.guide.nav

import kotlin.math.abs

enum class Alert {

    NO_GROUND,
    CLEAR,
    CAUTION,
    WARNING,
    STOP;
}

enum class Detour { NONE, LEFT, RIGHT, BLOCKED }

class Cue {
    var alert = Alert.CLEAR
        internal set
    var detour = Detour.NONE
        internal set
    var frontRange = FreeSpaceAnalyzer.MAX_RANGE
        internal set
    var corridorBearing = 0f
        internal set
    var headingBearing = 0f
        internal set
    var headingClearance = FreeSpaceAnalyzer.MAX_RANGE
        internal set
    var changed = false
        internal set
}

class Guidance {

    private val cue = Cue()
    private var level = Alert.CLEAR
    private var detour = Detour.NONE

    private var groundMissingFrames = 0
    private var groundLost = false

    fun evaluate(reading: SceneReading): Cue {
        val previousLevel = level
        val previousDetour = detour

        if (isGroundLost(reading.groundVisible)) {
            level = Alert.NO_GROUND
            detour = Detour.NONE
            cue.alert = level
            cue.detour = detour
            cue.frontRange = 0f
            cue.corridorBearing = 0f
            cue.headingBearing = 0f
            cue.headingClearance = 0f
            cue.changed = previousLevel != level
            return cue
        }

        val front = frontRange(reading)
        level = escalate(previousLevel, front)

        cue.headingBearing = 0f
        cue.headingClearance = front
        detour = if (level >= Alert.WARNING) chooseDetour(reading, front) else Detour.NONE

        cue.alert = level
        cue.detour = detour
        cue.frontRange = front
        cue.corridorBearing = reading.corridorBearing
        cue.changed = previousLevel != level || previousDetour != detour
        return cue
    }

    private fun isGroundLost(groundVisible: Boolean): Boolean {
        if (groundVisible) {
            groundMissingFrames = 0
            groundLost = false
        } else if (++groundMissingFrames >= GROUND_LOST_FRAMES) {
            groundLost = true
        }
        return groundLost
    }

    private fun frontRange(reading: SceneReading): Float = reading.corridorRange

    private fun escalate(current: Alert, front: Float): Alert {
        val measured = when {
            front < STOP_RANGE -> Alert.STOP
            front < WARNING_RANGE -> Alert.WARNING
            front < CAUTION_RANGE -> Alert.CAUTION
            else -> Alert.CLEAR
        }
        if (current == Alert.NO_GROUND || measured > current) return measured

        val exitRange = when (current) {
            Alert.STOP -> STOP_RANGE
            Alert.WARNING -> WARNING_RANGE
            Alert.CAUTION -> CAUTION_RANGE
            else -> return measured
        } + HYSTERESIS
        return if (front >= exitRange) measured else current
    }

    private fun chooseDetour(reading: SceneReading, front: Float): Detour {
        val required = front + DETOUR_GAIN
        var bestSector = -1
        var bestScore = Float.NEGATIVE_INFINITY

        for (sector in 0 until reading.ranges.size - 1) {
            val pairClearance = minOf(reading.ranges[sector], reading.ranges[sector + 1])
            if (pairClearance < required || pairClearance < WARNING_RANGE) continue

            val bearing = (reading.bearings[sector] + reading.bearings[sector + 1]) / 2f

            val score = pairClearance - abs(bearing) * TURN_PENALTY
            if (score > bestScore) {
                bestScore = score
                bestSector = sector
            }
        }

        if (bestSector < 0) {
            cue.headingBearing = 0f
            cue.headingClearance = front
            return Detour.BLOCKED
        }

        val bearing = (reading.bearings[bestSector] + reading.bearings[bestSector + 1]) / 2f
        cue.headingBearing = bearing
        cue.headingClearance = minOf(reading.ranges[bestSector], reading.ranges[bestSector + 1])
        return if (bearing < 0f) Detour.LEFT else Detour.RIGHT
    }

    private companion object {

        const val FRONT_HALF_ANGLE = 0.21f

        const val CAUTION_RANGE = 3.0f
        const val WARNING_RANGE = 1.6f
        const val STOP_RANGE = 0.8f
        const val HYSTERESIS = 0.35f

        const val DETOUR_GAIN = 0.8f
        const val TURN_PENALTY = 1.2f

        const val GROUND_LOST_FRAMES = 15
    }
}

package com.freespace.guide.feedback

import com.freespace.guide.nav.Alert
import com.freespace.guide.nav.FreeSpaceAnalyzer

class AudioScene {

    var silent = true

    var alert = Alert.CLEAR

    var corridorRange = FreeSpaceAnalyzer.MAX_RANGE

    var corridorBearing = 0f

    var halfSpan = 0.36f
}

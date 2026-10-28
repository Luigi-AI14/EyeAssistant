package com.freespace.guide.feedback

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

fun headsetConnected(context: Context): Boolean {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
    return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
        when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    device.type == AudioDeviceInfo.TYPE_HEARING_AID
                ) {
                    true
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                     device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                     device.type == AudioDeviceInfo.TYPE_BLE_BROADCAST)
                ) {
                    true
                } else {
                    false
                }
            }
        }
    }
}

package com.heineken.scanalert

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenReaderService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName != "com.heineken.cmobilepda") return
        
        val rootNode = rootInActiveWindow ?: return
        val screenText = getEventText(rootNode).lowercase()
        
        if (screenText.contains("pallet không tồn tai") || screenText.contains("pallet không tồn tại")) {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1500)
        }
    }

    private fun getEventText(node: AccessibilityNodeInfo): String {
        var text = node.text?.toString() ?: ""
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { text += " " + getEventText(it) }
        }
        return text
    }
    override fun onInterrupt() {}
}

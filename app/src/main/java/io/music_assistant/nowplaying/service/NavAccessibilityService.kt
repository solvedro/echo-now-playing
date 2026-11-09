package io.music_assistant.nowplaying.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class NavAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "NavAccessibilityService connected successfully")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        Log.w(TAG, "NavAccessibilityService interrupted")
    }

    override fun onDestroy() {
        if (instance == this) {
            instance = null
        }
        super.onDestroy()
        Log.i(TAG, "NavAccessibilityService destroyed")
    }

    companion object {
        private const val TAG = "NavAccessibilityService"
        private var instance: NavAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun performBack(): Boolean {
            val inst = instance
            Log.d(TAG, "performBack: instance=$inst")
            if (inst != null) {
                val ok = inst.performGlobalAction(GLOBAL_ACTION_BACK)
                Log.d(TAG, "performGlobalAction(GLOBAL_ACTION_BACK) returned: $ok")
                return ok
            }
            return false
        }

        fun performHome(): Boolean {
            val inst = instance
            Log.d(TAG, "performHome: instance=$inst")
            if (inst != null) {
                val ok = inst.performGlobalAction(GLOBAL_ACTION_HOME)
                Log.d(TAG, "performGlobalAction(GLOBAL_ACTION_HOME) returned: $ok")
                return ok
            }
            return false
        }

        fun performRecents(): Boolean {
            val inst = instance
            Log.d(TAG, "performRecents: instance=$inst")
            if (inst != null) {
                val ok = inst.performGlobalAction(GLOBAL_ACTION_RECENTS)
                Log.d(TAG, "performGlobalAction(GLOBAL_ACTION_RECENTS) returned: $ok")
                return ok
            }
            return false
        }

        fun triggerHome(context: Context) {
            Log.d(TAG, "triggerHome called")
            if (performHome()) return
            try {
                Log.d(TAG, "triggerHome: falling back to CATEGORY_HOME Intent")
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send Home intent", e)
            }
        }

        fun triggerBack(context: Context? = null) {
            Log.d(TAG, "triggerBack called")
            if (performBack()) return
            try {
                Log.d(TAG, "triggerBack: falling back to Runtime input keyevent 4")
                Runtime.getRuntime().exec("input keyevent 4")
            } catch (e: Exception) {
                Log.d(TAG, "Fallback keyevent failed: ${e.message}")
            }
        }
    }
}

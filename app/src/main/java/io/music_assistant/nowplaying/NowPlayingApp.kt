package io.music_assistant.nowplaying

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.music_assistant.nowplaying.api.MusicAssistantClient
import java.net.NetworkInterface
import java.util.Collections

class NowPlayingApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val prefs = getSharedPreferences("ma_nowplaying_prefs", Context.MODE_PRIVATE)
        initDefaultPrefs(prefs)

        val localIp = detectLocalIp()
        MusicAssistantClient.instance.localDeviceIp = localIp
        Log.i("NowPlayingApp", "Local device IP detected: $localIp")

        val host = prefs.getString("server_host", "192.168.100.116") ?: "192.168.100.116"
        val port = prefs.getInt("server_port", 8095)
        val token = prefs.getString("auth_token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
        val playerId = prefs.getString("selected_player_id", "auto") ?: "auto"

        MusicAssistantClient.instance.updateConfig(host, port, token, playerId)
        MusicAssistantClient.instance.start()

        val autoOverlay = prefs.getBoolean("auto_overlay", true)
        if (autoOverlay) {
            io.music_assistant.nowplaying.service.OverlayService.start(this)
            io.music_assistant.nowplaying.service.OverlayService.showOverlay(this)
        }
    }

    private fun initDefaultPrefs(prefs: SharedPreferences) {
        val editor = prefs.edit()
        var modified = false

        if (!prefs.contains("server_host")) {
            editor.putString("server_host", "192.168.100.116")
            modified = true
        }
        if (!prefs.contains("server_port")) {
            editor.putInt("server_port", 8095)
            modified = true
        }
        if (!prefs.contains("auth_token")) {
            editor.putString("auth_token", DEFAULT_TOKEN)
            modified = true
        }
        // Always default or migrate to "auto" so both Squeezelite and Music Assistant app are tracked!
        val curPid = prefs.getString("selected_player_id", null)
        if (curPid == null || curPid == "cd:ef:db:59:36:12" || curPid == "cd:ef:db:59:36:13") {
            editor.putString("selected_player_id", "auto")
            modified = true
        }
        if (!prefs.contains("auto_overlay")) {
            editor.putBoolean("auto_overlay", true)
            modified = true
        }
        if (!prefs.contains("keep_screen_on")) {
            editor.putBoolean("keep_screen_on", true)
            modified = true
        }

        if (modified) {
            editor.apply()
        }
    }

    private fun detectLocalIp(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: continue
                        if (!sAddr.contains(":")) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("NowPlayingApp", "Error detecting IP", e)
        }
        return "192.168.100.10"
    }

    companion object {
        lateinit var instance: NowPlayingApp
            private set

        const val DEFAULT_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJqdXVJbW90NE90MGF3ZEJKOGdnSklkWWd2QUl2Qm1KeFRDbzkyNXZldms4IiwianRpIjoiSEVVaUZOZmNfWlZqbS1IS1N4YnM5Q3p5UEhZUTVXcVBsSTZrT2hkaGpQTSIsImlhdCI6MTc5Mzk1NDE5MSwiZXhwIjoxODAxNzMwMTkxLCJ1c2VybmFtZSI6ImRlbmhlbHQiLCJyb2xlIjoiYWRtaW4iLCJ0b2tlbl9uYW1lIjoiS01QIGFwcCAzMWFhZDA0OS0wZTQ5LTRmMzAtYTg4Yy1jYmU2NGZiYTVkNWIiLCJpc19sb25nX2xpdmVkIjpmYWxzZX0.sXgDalPGizXlWh4uI68IedIddBPhh2WiFX9CvP-s3us"
    }
}

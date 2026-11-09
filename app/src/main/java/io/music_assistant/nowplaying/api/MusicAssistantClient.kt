package io.music_assistant.nowplaying.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.music_assistant.nowplaying.model.PlayerInfo
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MusicAssistantClient private constructor() {

    interface Listener {
        fun onConnectionStateChanged(connected: Boolean, errorMsg: String? = null)
        fun onPlayerUpdated(player: PlayerInfo)
        fun onPlayersListUpdated(players: List<PlayerInfo>)
    }

    private val TAG = "MusicAssistantClient"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<Listener>()

    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var isStarted = false
    private val msgIdSeq = AtomicLong(100)

    var serverHost: String = "192.168.100.116"
        private set
    var serverPort: Int = 8095
        private set
    var authToken: String = ""
        private set
    var targetPlayerId: String? = "auto"
    var localDeviceIp: String = ""

    var activePlayer: PlayerInfo? = null
        private set

    val allPlayers = ConcurrentHashMap<String, PlayerInfo>()

    val httpBaseUrl: String
        get() = "http://$serverHost:$serverPort"

    val wsUrl: String
        get() = "ws://$serverHost:$serverPort/ws"

    companion object {
        val instance by lazy { MusicAssistantClient() }
    }

    fun isPlayerForThisDevice(p: PlayerInfo): Boolean {
        val isFilipDevice = localDeviceIp.endsWith(".45")
        if (isFilipDevice) {
            // Filip device (.45)
            if (p.id.equals("cd:ef:db:59:36:13", ignoreCase = true)) return true
            if (p.name.contains("Filip", ignoreCase = true)) return true
        } else {
            // Main Echo Show 8 (.10 or default)
            if (p.id.equals("cd:ef:db:59:36:12", ignoreCase = true)) return true
            if (p.name.contains("Echo Show 8", ignoreCase = true) && !p.name.contains("Filip", ignoreCase = true)) return true
            if (p.name.equals("Echo Show", ignoreCase = true)) return true
        }
        return false
    }

    fun recomputeActivePlayer() {
        val isAutoMode = targetPlayerId.isNullOrEmpty() ||
            targetPlayerId == "auto" ||
            targetPlayerId == "cd:ef:db:59:36:12" ||
            targetPlayerId == "cd:ef:db:59:36:13"

        val candidate = if (isAutoMode) {
            val devicePlayers = allPlayers.values.filter { isPlayerForThisDevice(it) }
            if (devicePlayers.isNotEmpty()) {
                devicePlayers.firstOrNull { it.isPlaying }
                    ?: devicePlayers.firstOrNull { it.isPaused }
                    ?: devicePlayers.maxByOrNull { it.lastUpdatedMs }
            } else {
                allPlayers[targetPlayerId]
            }
        } else {
            allPlayers[targetPlayerId]
        }

        if (candidate != null) {
            val switched = (activePlayer?.id != candidate.id)
            activePlayer = candidate
            if (switched) {
                Log.i(TAG, "Active player switched to: ${candidate.name} (${candidate.id}), state=${candidate.state}, title=${candidate.currentTitle}")
            }
            notifyPlayerUpdated(candidate)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
        // Send current state
        listener.onConnectionStateChanged(isConnected)
        activePlayer?.let { p ->
            listener.onPlayerUpdated(p)
        }
        listener.onPlayersListUpdated(allPlayers.values.toList())
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun updateConfig(host: String, port: Int, token: String, playerId: String?) {
        val changed = (serverHost != host || serverPort != port || authToken != token)
        serverHost = host
        serverPort = port
        authToken = token
        targetPlayerId = if (playerId.isNullOrEmpty() || playerId == "cd:ef:db:59:36:12" || playerId == "cd:ef:db:59:36:13") "auto" else playerId

        if (changed && isStarted) {
            disconnect()
            connect()
        } else {
            recomputeActivePlayer()
        }
    }

    fun start() {
        isStarted = true
        connect()
    }

    fun stop() {
        isStarted = false
        disconnect()
    }

    private fun connect() {
        if (webSocket != null) return

        Log.d(TAG, "Connecting to WebSocket: $wsUrl")
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket open, sending auth...")
                sendAuth()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $code / $reason")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code / $reason")
                onDisconnected(null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}", t)
                onDisconnected(t.message)
            }
        })
    }

    private fun disconnect() {
        webSocket?.let {
            try {
                it.close(1000, "Normal closure")
            } catch (e: Exception) {
                it.cancel()
            }
        }
        webSocket = null
        onDisconnected(null)
    }

    private fun onDisconnected(errorMsg: String?) {
        webSocket = null
        if (isConnected) {
            isConnected = false
            notifyConnectionState(false, errorMsg)
        }
        if (isStarted) {
            // Schedule reconnect after 4 seconds
            mainHandler.postDelayed({
                if (isStarted && webSocket == null) {
                    connect()
                }
            }, 4000)
        }
    }

    private fun sendAuth() {
        try {
            val json = JSONObject().apply {
                put("message_id", "auth_req")
                put("command", "auth")
                put("args", JSONObject().apply {
                    put("token", authToken)
                    put("device_name", "Echo Show Now Playing")
                })
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending auth", e)
        }
    }

    private fun queryInitialData() {
        sendRpc("get_players", "players/all")
        sendRpc("get_queues", "player_queues/all")
    }

    private fun sendRpc(msgId: String, command: String, args: JSONObject? = null) {
        try {
            val json = JSONObject().apply {
                put("message_id", msgId)
                put("command", command)
                if (args != null) {
                    put("args", args)
                }
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error sending RPC: $command", e)
        }
    }

    private fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            Log.d(TAG, "WS message: " + text.take(300))

            // 1. Auth response
            if (obj.optString("message_id") == "auth_req") {
                val error = obj.optJSONObject("error")
                if (error != null) {
                    val msg = error.optString("message", "Authentication failed")
                    Log.e(TAG, "Auth failed: $msg")
                    onDisconnected("Auth failed: $msg")
                } else {
                    Log.i(TAG, "Auth succeeded! Requesting players & queues.")
                    isConnected = true
                    notifyConnectionState(true)
                    queryInitialData()
                }
                return
            }

            // 2. Initial players query response
            if (obj.optString("message_id") == "get_players") {
                val result = obj.optJSONArray("result")
                if (result != null) {
                    for (i in 0 until result.length()) {
                        val pObj = result.optJSONObject(i) ?: continue
                        PlayerInfo.parseFromJson(pObj, httpBaseUrl)?.let { p ->
                            mergeAndStorePlayer(p)
                        }
                    }
                    notifyPlayersListUpdated()
                }
                return
            }

            // 3. Initial queues query response
            if (obj.optString("message_id") == "get_queues") {
                val result = obj.optJSONArray("result")
                if (result != null) {
                    for (i in 0 until result.length()) {
                        val qObj = result.optJSONObject(i) ?: continue
                        PlayerInfo.parseFromJson(qObj, httpBaseUrl)?.let { p ->
                            mergeAndStorePlayer(p)
                        }
                    }
                    notifyPlayersListUpdated()
                }
                return
            }

            // 3b. Item metadata detail response
            val msgId = obj.optString("message_id")
            if (msgId.startsWith("item_detail_")) {
                val targetId = msgId.removePrefix("item_detail_")
                val result = obj.optJSONObject("result")
                if (result != null) {
                    val foundImage = PlayerInfo.extractImageFromMediaItem(result, httpBaseUrl)
                    Log.i(TAG, "Item detail for $targetId found image: $foundImage")
                    if (foundImage != null) {
                        val existing = allPlayers[targetId]
                        if (existing != null) {
                            val updated = existing.copy(imageUrl = foundImage)
                            allPlayers[targetId] = updated
                            if (activePlayer?.id == targetId) {
                                activePlayer = updated
                                notifyPlayerUpdated(updated)
                            }
                        }
                    }
                }
                return
            }

            // 4. Real-time events
            val event = obj.optString("event")
            if (event.isNotEmpty()) {
                val data = obj.optJSONObject("data")
                Log.i(TAG, "WS event: $event, data: $data")
                when (event) {
                    "player_updated" -> {
                        if (data != null) {
                            PlayerInfo.parseFromJson(data, httpBaseUrl)?.let { p ->
                                mergeAndStorePlayer(p)
                                notifyPlayersListUpdated()
                            }
                        }
                    }
                    "queue_updated" -> {
                        if (data != null) {
                            PlayerInfo.parseFromJson(data, httpBaseUrl)?.let { p ->
                                mergeAndStorePlayer(p)
                                notifyPlayersListUpdated()
                            }
                        }
                    }
                    "queue_time_updated" -> {
                        if (data != null) {
                            val queueId = data.optString("queue_id")
                            val elapsed = data.optDouble("elapsed_time", -1.0)
                            if (queueId.isNotEmpty() && !elapsed.isNaN() && elapsed >= 0) {
                                val existing = allPlayers[queueId]
                                if (existing != null) {
                                    val updated = existing.copy(
                                        elapsedTime = elapsed,
                                        lastUpdatedMs = System.currentTimeMillis()
                                    )
                                    allPlayers[queueId] = updated
                                    if (activePlayer?.id == queueId) {
                                        activePlayer = updated
                                        notifyPlayerUpdated(updated)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling WS message", e)
        }
    }

    private fun mergeAndStorePlayer(newInfo: PlayerInfo) {
        val existing = allPlayers[newInfo.id]
        val merged = if (existing == null) {
            newInfo
        } else {
            existing.copy(
                name = newInfo.name.ifEmpty { existing.name },
                state = if (newInfo.state != "idle" || existing.state == "idle") newInfo.state else existing.state,
                volume = if (newInfo.volume > 0) newInfo.volume else existing.volume,
                isMuted = newInfo.isMuted,
                currentTitle = newInfo.currentTitle ?: existing.currentTitle,
                currentArtist = newInfo.currentArtist ?: existing.currentArtist,
                currentAlbum = newInfo.currentAlbum ?: existing.currentAlbum,
                imageUrl = newInfo.imageUrl ?: existing.imageUrl,
                duration = if (newInfo.duration > 0.0) newInfo.duration else existing.duration,
                elapsedTime = if (newInfo.elapsedTime >= 0.0) newInfo.elapsedTime else existing.elapsedTime,
                lastUpdatedMs = newInfo.lastUpdatedMs,
                mediaUri = newInfo.mediaUri ?: existing.mediaUri
            )
        }
        allPlayers[newInfo.id] = merged
        Log.i(TAG, "Merged player ${merged.id} (${merged.name}): state=${merged.state}, title=${merged.currentTitle}, img=${merged.imageUrl}")

        if (merged.imageUrl.isNullOrEmpty() && !merged.mediaUri.isNullOrEmpty()) {
            queryItemMetadata(merged.id, merged.mediaUri)
        }

        recomputeActivePlayer()
        if (activePlayer?.id == merged.id) {
            notifyPlayerUpdated(merged)
        }
    }

    private fun notifyConnectionState(connected: Boolean, errorMsg: String? = null) {
        mainHandler.post {
            synchronized(listeners) {
                listeners.forEach { it.onConnectionStateChanged(connected, errorMsg) }
            }
        }
    }

    private fun notifyPlayerUpdated(player: PlayerInfo) {
        mainHandler.post {
            synchronized(listeners) {
                listeners.forEach { it.onPlayerUpdated(player) }
            }
        }
    }

    private fun notifyPlayersListUpdated() {
        val list = allPlayers.values.toList()
        mainHandler.post {
            synchronized(listeners) {
                listeners.forEach { it.onPlayersListUpdated(list) }
            }
        }
    }

    // Media Control API
    fun playPause() {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        sendCommand("players/cmd/play_pause", JSONObject().apply {
            put("player_id", pid)
        })
    }

    fun next() {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        sendCommand("players/cmd/next", JSONObject().apply {
            put("player_id", pid)
        })
    }

    fun previous() {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        sendCommand("players/cmd/previous", JSONObject().apply {
            put("player_id", pid)
        })
    }

    fun seek(positionSeconds: Long) {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        sendCommand("players/cmd/seek", JSONObject().apply {
            put("player_id", pid)
            put("position", positionSeconds)
        })
    }

    fun setVolume(volumeLevel: Int) {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        val clamped = volumeLevel.coerceIn(0, 100)
        sendCommand("players/cmd/volume_set", JSONObject().apply {
            put("player_id", pid)
            put("volume_level", clamped)
        })
    }

    fun setMute(muted: Boolean) {
        val pid = activePlayer?.id ?: targetPlayerId ?: return
        sendCommand("players/cmd/volume_mute", JSONObject().apply {
            put("player_id", pid)
            put("muted", muted)
        })
    }

    private fun queryItemMetadata(playerId: String, uri: String) {
        val msgId = "item_detail_$playerId"
        sendRpc(msgId, "music/item_by_uri", JSONObject().apply {
            put("uri", uri)
        })
    }

    private fun sendCommand(command: String, args: JSONObject) {
        val id = msgIdSeq.incrementAndGet().toString()
        sendRpc(id, command, args)
    }
}

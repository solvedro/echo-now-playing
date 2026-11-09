package io.music_assistant.nowplaying.model

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

fun JSONObject.optStringClean(name: String): String? {
    if (isNull(name)) return null
    val v = optString(name, "").trim()
    if (v.isEmpty() || v.equals("null", ignoreCase = true)) return null
    return v
}

data class PlayerInfo(
    val id: String,
    val name: String,
    val state: String = "idle", // "playing", "paused", "idle", "off"
    val volume: Int = 100,
    val isMuted: Boolean = false,
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentAlbum: String? = null,
    val imageUrl: String? = null,
    val duration: Double = 0.0,
    val elapsedTime: Double = 0.0,
    val lastUpdatedMs: Long = System.currentTimeMillis(),
    val mediaUri: String? = null,
    val lyrics: String? = null
) {
    val isPlaying: Boolean
        get() = state.equals("playing", ignoreCase = true)

    val isPaused: Boolean
        get() = state.equals("paused", ignoreCase = true)

    fun getEstimatedPositionSeconds(): Double {
        if (!isPlaying || duration <= 0.0) return elapsedTime
        val elapsedSinceUpdate = (System.currentTimeMillis() - lastUpdatedMs) / 1000.0
        val est = elapsedTime + elapsedSinceUpdate
        return est.coerceIn(0.0, duration)
    }

    companion object {
        fun parseFromJson(obj: JSONObject, serverBaseUrl: String): PlayerInfo? {
            val playerId = obj.optStringClean("player_id")
                ?: obj.optStringClean("queue_id")
                ?: return null

            val name = obj.optStringClean("display_name")
                ?: obj.optStringClean("name")
                ?: playerId

            val state = obj.optStringClean("playback_state")
                ?: obj.optStringClean("state")
                ?: "idle"
            val volume = obj.optInt("volume_level", 100)
            val isMuted = obj.optBoolean("volume_muted", false)

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var imageUrl: String? = null
            var duration = 0.0
            var elapsed = obj.optDouble("elapsed_time", 0.0)
            if (elapsed.isNaN()) elapsed = 0.0

            var mediaUri: String? = null
            var lyricsText: String? = null

            // 1. Check current_media (from Player)
            val currentMedia = obj.optJSONObject("current_media")
            if (currentMedia != null) {
                mediaUri = currentMedia.optStringClean("uri")
                title = currentMedia.optStringClean("title")
                artist = currentMedia.optStringClean("artist")
                album = currentMedia.optStringClean("album")
                val rawImg = currentMedia.optStringClean("image_url")
                if (rawImg != null) {
                    imageUrl = resolveImageUrl(rawImg, serverBaseUrl)
                }
                duration = currentMedia.optDouble("duration", 0.0)
                if (duration.isNaN()) duration = 0.0
                val mediaElapsed = currentMedia.optDouble("elapsed_time", -1.0)
                if (!mediaElapsed.isNaN() && mediaElapsed >= 0) {
                    elapsed = mediaElapsed
                }
                val mediaMeta = currentMedia.optJSONObject("metadata")
                lyricsText = mediaMeta?.optStringClean("lrc_lyrics") ?: mediaMeta?.optStringClean("lyrics")
            }

            // 2. Check current_item (from Queue)
            val currentItem = obj.optJSONObject("current_item")
            if (currentItem != null) {
                if (lyricsText.isNullOrEmpty()) {
                    val itemMeta = currentItem.optJSONObject("metadata")
                    lyricsText = itemMeta?.optStringClean("lrc_lyrics") ?: itemMeta?.optStringClean("lyrics")
                }
                if (title.isNullOrEmpty()) {
                    title = currentItem.optStringClean("name")
                }
                if (duration <= 0.0) {
                    val d = currentItem.optDouble("duration", 0.0)
                    if (!d.isNaN()) duration = d
                }
                val mediaItem = currentItem.optJSONObject("media_item")
                if (mediaItem != null) {
                    if (title.isNullOrEmpty()) {
                        title = mediaItem.optStringClean("name")
                    }
                    if (artist.isNullOrEmpty()) {
                        val artists = mediaItem.optJSONArray("artists")
                        if (artists != null && artists.length() > 0) {
                            val artistNames = mutableListOf<String>()
                            for (i in 0 until artists.length()) {
                                val aObj = artists.optJSONObject(i)
                                if (aObj != null) {
                                    val an = aObj.optStringClean("name")
                                    if (an != null) artistNames.add(an)
                                }
                            }
                            if (artistNames.isNotEmpty()) {
                                artist = artistNames.joinToString(", ")
                            }
                        }
                    }
                    val albumObj = mediaItem.optJSONObject("album")
                    if (albumObj != null) {
                        if (album.isNullOrEmpty()) {
                            album = albumObj.optStringClean("name")
                        }
                        if (imageUrl.isNullOrEmpty()) {
                            imageUrl = extractImageFromMediaItem(albumObj, serverBaseUrl)
                        }
                    }
                    if (imageUrl.isNullOrEmpty()) {
                        imageUrl = extractImageFromMediaItem(mediaItem, serverBaseUrl)
                    }
                    if (lyricsText.isNullOrEmpty()) {
                        val mMeta = mediaItem.optJSONObject("metadata")
                        lyricsText = mMeta?.optStringClean("lrc_lyrics") ?: mMeta?.optStringClean("lyrics")
                    }
                }
                if (imageUrl.isNullOrEmpty()) {
                    val imgObj = currentItem.optJSONObject("image")
                    if (imgObj != null) {
                        imageUrl = resolveImageObject(imgObj, serverBaseUrl)
                    }
                }
                if (imageUrl.isNullOrEmpty()) {
                    val streamDetails = currentItem.optJSONObject("streamdetails")
                    val streamMeta = streamDetails?.optJSONObject("stream_metadata")
                    val sImg = streamMeta?.optStringClean("image_url")
                    if (sImg != null) {
                        imageUrl = resolveImageUrl(sImg, serverBaseUrl)
                    }
                    if (mediaUri.isNullOrEmpty()) {
                        mediaUri = currentItem.optJSONObject("media_item")?.optJSONObject("album")?.optStringClean("uri")
                            ?: currentItem.optJSONObject("media_item")?.optStringClean("uri")
                    }
                }
            }

            return PlayerInfo(
                id = playerId,
                name = name,
                state = state,
                volume = volume,
                isMuted = isMuted,
                currentTitle = title,
                currentArtist = artist,
                currentAlbum = album,
                imageUrl = imageUrl,
                duration = duration,
                elapsedTime = elapsed,
                lastUpdatedMs = System.currentTimeMillis(),
                mediaUri = mediaUri,
                lyrics = lyricsText
            )
        }

        fun extractImageFromMediaItem(item: JSONObject, baseUrl: String): String? {
            val directImg = item.optJSONObject("image")
            if (directImg != null) {
                val res = resolveImageObject(directImg, baseUrl)
                if (res != null) return res
            }
            val meta = item.optJSONObject("metadata")
            if (meta != null) {
                val images = meta.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val img = images.optJSONObject(i) ?: continue
                        val res = resolveImageObject(img, baseUrl)
                        if (res != null) return res
                    }
                }
            }
            val artists = item.optJSONArray("artists")
            if (artists != null && artists.length() > 0) {
                for (i in 0 until artists.length()) {
                    val aObj = artists.optJSONObject(i) ?: continue
                    val res = extractImageFromMediaItem(aObj, baseUrl)
                    if (res != null) return res
                }
            }
            return null
        }

        private fun resolveImageObject(img: JSONObject, baseUrl: String): String? {
            val url = img.optStringClean("url")
            if (url != null) return resolveImageUrl(url, baseUrl)

            val path = img.optStringClean("path") ?: return null
            if (path.startsWith("http://") || path.startsWith("https://")) {
                return path
            }
            val proxyId = img.optStringClean("proxy_id")
            if (proxyId != null) {
                val cleanBase = baseUrl.trimEnd('/')
                return "$cleanBase/imageproxy/$proxyId?size=512"
            }
            val provider = img.optStringClean("provider") ?: "library"
            return buildProxyUrl(baseUrl, path, provider)
        }

        fun resolveImageUrl(raw: String?, baseUrl: String): String? {
            if (raw == null) return null
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed == "/null") return null
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return trimmed
            }
            val cleanBase = baseUrl.trimEnd('/')
            val cleanRaw = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
            return "$cleanBase$cleanRaw"
        }

        fun buildProxyUrl(baseUrl: String, path: String, provider: String): String {
            val cleanBase = baseUrl.trimEnd('/')
            val encodedPath = try {
                URLEncoder.encode(path, "UTF-8")
            } catch (e: Exception) {
                path
            }
            return "$cleanBase/imageproxy?path=$encodedPath&provider=$provider"
        }
    }
}

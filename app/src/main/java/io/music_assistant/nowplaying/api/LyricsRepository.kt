package io.music_assistant.nowplaying.api

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.music_assistant.nowplaying.model.LyricsParser
import io.music_assistant.nowplaying.model.TrackLyrics
import io.music_assistant.nowplaying.model.optStringClean
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object LyricsRepository {

    private const val TAG = "LyricsRepository"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Cache by "Artist - Title" lowercased
    private val cache = ConcurrentHashMap<String, TrackLyrics>()

    fun cacheLyrics(artist: String?, title: String?, rawLyrics: String) {
        val trimmed = rawLyrics.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return
        val key = makeCacheKey(artist, title)
        val parsed = LyricsParser.parse(trimmed)
        if (parsed.lines.isNotEmpty()) {
            cache[key] = parsed
            Log.d(TAG, "Cached lyrics for [$key]: ${parsed.lines.size} lines, synced=${parsed.isSynced}")
        }
    }

    fun getCachedLyrics(artist: String?, title: String?): TrackLyrics? {
        val key = makeCacheKey(artist, title)
        return cache[key]
    }

    fun fetchLyrics(
        artist: String?,
        title: String?,
        duration: Double = 0.0,
        callback: (TrackLyrics?) -> Unit
    ) {
        if (title.isNullOrBlank()) {
            mainHandler.post { callback(null) }
            return
        }

        val key = makeCacheKey(artist, title)
        val cached = cache[key]
        if (cached != null) {
            mainHandler.post { callback(cached) }
            return
        }

        val cleanArtist = cleanQueryText(artist ?: "")
        val cleanTitle = cleanQueryText(title)

        // Run fetch on background thread
        Thread {
            try {
                var result: TrackLyrics? = null
                var plainFallback: TrackLyrics? = null

                val effArtist = cleanArtist.ifEmpty { artist ?: "" }
                val effTitle = cleanTitle.ifEmpty { title }

                // Step 1: Exact match with cleanArtist and cleanTitle
                if (effArtist.isNotEmpty() && effTitle.isNotEmpty()) {
                    val r = queryLrclibGet(effArtist, effTitle, duration)
                    if (r?.isSynced == true) {
                        result = r
                    } else if (r != null) {
                        plainFallback = r
                    }
                }

                // Step 1b: Stripped title without parentheticals (e.g. "Many Men (Wish Death)" -> "Many Men")
                val strippedTitle = effTitle.replace(Regex("\\s*\\([^)]*\\)"), "").replace(Regex("\\s*\\[[^]]*\\]"), "").trim()
                if (result == null && strippedTitle.isNotEmpty() && strippedTitle != effTitle) {
                    val r = queryLrclibGet(effArtist, strippedTitle, duration)
                    if (r?.isSynced == true) {
                        result = r
                    } else if (plainFallback == null && r != null) {
                        plainFallback = r
                    }
                }

                // Step 2: If title contains " - ", try splitting into artist and title (common on radio/streams)
                if (result == null && effTitle.contains(" - ")) {
                    val parts = effTitle.split(" - ", limit = 2)
                    val splitArtist = cleanQueryText(parts[0])
                    val splitTitle = cleanQueryText(parts[1])
                    if (splitArtist.isNotEmpty() && splitTitle.isNotEmpty()) {
                        val r = queryLrclibGet(splitArtist, splitTitle, duration)
                        if (r?.isSynced == true) {
                            result = r
                        } else if (plainFallback == null && r != null) {
                            plainFallback = r
                        }
                    }
                }

                // Step 3: Search queries on LRCLIB to find synced lyrics!
                if (result == null) {
                    val searchQueries = listOfNotNull(
                        "$effArtist $effTitle".trim().ifEmpty { null },
                        if (strippedTitle != effTitle && strippedTitle.isNotEmpty()) "$effArtist $strippedTitle".trim().ifEmpty { null } else null,
                        effTitle.ifEmpty { null }
                    ).distinct()

                    for (q in searchQueries) {
                        val searchResult = queryLrclibSearch(q)
                        if (searchResult?.isSynced == true) {
                            result = searchResult
                            break
                        } else if (plainFallback == null && searchResult != null) {
                            plainFallback = searchResult
                        }
                    }
                }

                // Fall back to plain lyrics if synced was not found
                if (result == null) {
                    result = plainFallback
                }

                if (result != null && result.lines.isNotEmpty()) {
                    cache[key] = result
                    Log.i(TAG, "Successfully fetched lyrics for [$key] (${result.lines.size} lines, synced=${result.isSynced})")
                } else {
                    Log.w(TAG, "No lyrics found on LRCLIB for [$key]")
                }

                mainHandler.post { callback(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching lyrics for [$key]", e)
                mainHandler.post { callback(null) }
            }
        }.start()
    }

    private fun queryLrclibGet(artist: String, title: String, duration: Double): TrackLyrics? {
        if (title.isBlank()) return null
        return try {
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val durParam = if (duration > 0.0) "&duration=${duration.toInt()}" else ""
            val url = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle$durParam"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EchoNowPlaying/1.0 (Amazon Echo Show 8; Android)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            extractLyricsFromJson(json)
        } catch (e: Exception) {
            Log.d(TAG, "queryLrclibGet failed: ${e.message}")
            null
        }
    }

    private fun queryLrclibSearch(query: String): TrackLyrics? {
        if (query.isBlank()) return null
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://lrclib.net/api/search?q=$encodedQuery"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "EchoNowPlaying/1.0 (Amazon Echo Show 8; Android)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val body = response.body?.string() ?: return null
            val array = JSONArray(body)
            var plainFallback: TrackLyrics? = null

            for (i in 0 until array.length().coerceAtMost(10)) {
                val item = array.optJSONObject(i) ?: continue
                val parsed = extractLyricsFromJson(item) ?: continue
                if (parsed.isSynced) {
                    return parsed // Found synced match!
                } else if (plainFallback == null) {
                    plainFallback = parsed
                }
            }

            plainFallback
        } catch (e: Exception) {
            Log.d(TAG, "queryLrclibSearch failed: ${e.message}")
            null
        }
    }

    private fun extractLyricsFromJson(json: JSONObject): TrackLyrics? {
        val synced = json.optStringClean("syncedLyrics")
        if (synced != null) {
            val parsed = LyricsParser.parse(synced)
            if (parsed.lines.isNotEmpty()) {
                return parsed
            }
        }
        val plain = json.optStringClean("plainLyrics")
        if (plain != null) {
            val parsed = LyricsParser.parse(plain)
            if (parsed.lines.isNotEmpty()) {
                return parsed
            }
        }
        return null
    }

    private fun makeCacheKey(artist: String?, title: String?): String {
        val a = artist?.trim()?.lowercase() ?: ""
        val t = title?.trim()?.lowercase() ?: ""
        return "$a - $t"
    }

    fun cleanQueryText(text: String): String {
        return text
            .replace(Regex("(?i)\\b3rei\\b"), "3")
            .replace(Regex("\\s*[\\[\\(](feat\\.|ft\\.|remastered|version|live|deluxe|explicit|clean|mono|stereo|soundtrack|radio edit).*?[\\]\\)]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(remastered|single|live|radio edit|deluxe|bonus track).*", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}

package io.music_assistant.nowplaying.model

import java.util.regex.Pattern

data class LyricLine(
    val timeSeconds: Double,
    val text: String
)

data class TrackLyrics(
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val rawText: String
) {
    fun findActiveIndex(currentSeconds: Double): Int {
        if (!isSynced || lines.isEmpty()) return -1
        var activeIdx = -1
        for (i in lines.indices) {
            if (lines[i].timeSeconds <= currentSeconds + 0.25) {
                activeIdx = i
            } else {
                break
            }
        }
        return activeIdx
    }
}

object LyricsParser {
    private val LRC_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{1,2}(?:\\.\\d{1,3})?)\\]")

    fun parse(raw: String): TrackLyrics {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return TrackLyrics(isSynced = false, lines = emptyList(), rawText = "")
        }

        val rawLines = trimmed.lines()
        val parsedLines = mutableListOf<LyricLine>()
        var foundAnyLrc = false

        for (line in rawLines) {
            val lineTrimmed = line.trim()
            if (lineTrimmed.isEmpty() || lineTrimmed.equals("null", ignoreCase = true)) continue

            // Skip metadata header tags
            if (lineTrimmed.startsWith("[ar:") ||
                lineTrimmed.startsWith("[ti:") ||
                lineTrimmed.startsWith("[al:") ||
                lineTrimmed.startsWith("[by:") ||
                lineTrimmed.startsWith("[length:") ||
                lineTrimmed.startsWith("[re:") ||
                lineTrimmed.startsWith("[ve:") ||
                lineTrimmed.startsWith("[offset:")) {
                continue
            }

            val matcher = LRC_TIMESTAMP_PATTERN.matcher(lineTrimmed)
            val timestamps = mutableListOf<Double>()
            var lastMatchEnd = 0

            while (matcher.find()) {
                val minStr = matcher.group(1) ?: "0"
                val secStr = matcher.group(2) ?: "0"
                val minutes = minStr.toDoubleOrNull() ?: 0.0
                val seconds = secStr.toDoubleOrNull() ?: 0.0
                timestamps.add(minutes * 60.0 + seconds)
                lastMatchEnd = matcher.end()
            }

            if (timestamps.isNotEmpty()) {
                foundAnyLrc = true
                val lyricText = lineTrimmed.substring(lastMatchEnd).trim()
                if (lyricText.isNotEmpty()) {
                    for (ts in timestamps) {
                        parsedLines.add(LyricLine(timeSeconds = ts, text = lyricText))
                    }
                }
            } else if (!foundAnyLrc) {
                parsedLines.add(LyricLine(timeSeconds = 0.0, text = lineTrimmed))
            }
        }

        return if (foundAnyLrc) {
            parsedLines.sortBy { it.timeSeconds }
            TrackLyrics(isSynced = true, lines = parsedLines, rawText = raw)
        } else {
            TrackLyrics(isSynced = false, lines = parsedLines, rawText = raw)
        }
    }
}

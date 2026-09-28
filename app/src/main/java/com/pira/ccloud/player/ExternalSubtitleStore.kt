package com.pira.ccloud.player

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.Properties

// A subtitle downloaded for a video, with the user's timing correction
data class ExternalSubtitle(
    val file: File,
    val release: String,
    val offsetMs: Long
)

/**
 * Keeps downloaded subtitles per video URL, so reopening a video doesn't use the download quota
 * again. The original file is kept; a shifted copy is written when a timing offset is set.
 */
class ExternalSubtitleStore(context: Context, private val videoUrl: String) {
    private val dir = File(context.cacheDir, "external_subtitles").apply { mkdirs() }
    private val key = MessageDigest.getInstance("SHA-1")
        .digest(videoUrl.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val originalFile = File(dir, "$key.srt")
    private val infoFile = File(dir, "$key.properties")

    fun load(): ExternalSubtitle? {
        if (!originalFile.exists() || !infoFile.exists()) return null
        val info = Properties().apply { infoFile.inputStream().use { load(it) } }
        val offsetMs = info.getProperty("offsetMs")?.toLongOrNull() ?: 0L
        return ExternalSubtitle(
            file = shiftedFile(offsetMs),
            release = info.getProperty("release").orEmpty(),
            offsetMs = offsetMs
        )
    }

    fun save(text: String, release: String): ExternalSubtitle {
        clear()
        originalFile.writeText(text)
        writeInfo(release, 0L)
        return ExternalSubtitle(originalFile, release, 0L)
    }

    fun setOffset(subtitle: ExternalSubtitle, offsetMs: Long): ExternalSubtitle {
        writeInfo(subtitle.release, offsetMs)
        return subtitle.copy(file = shiftedFile(offsetMs), offsetMs = offsetMs)
    }

    fun clear() {
        dir.listFiles { file -> file.name.startsWith(key) }?.forEach { it.delete() }
    }

    // A new file name per offset, so the player never reuses an old version
    private fun shiftedFile(offsetMs: Long): File {
        if (offsetMs == 0L) return originalFile
        val file = File(dir, "${key}_$offsetMs.srt")
        if (!file.exists()) {
            dir.listFiles { f -> f.name.startsWith("${key}_") }?.forEach { it.delete() }
            file.writeText(shiftSrt(originalFile.readText(), offsetMs))
        }
        return file
    }

    private fun writeInfo(release: String, offsetMs: Long) {
        val info = Properties()
        info.setProperty("release", release)
        info.setProperty("offsetMs", offsetMs.toString())
        infoFile.outputStream().use { info.store(it, null) }
    }

    companion object {
        private val timestamp = Regex("(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})")

        // Moves every SRT timing line by offsetMs (positive = later), not below zero
        fun shiftSrt(text: String, offsetMs: Long): String =
            text.lines().joinToString("\n") { line ->
                if ("-->" in line) shiftTimestamps(line, offsetMs) else line
            }

        private fun shiftTimestamps(line: String, offsetMs: Long): String =
            timestamp.replace(line) { match ->
                val (h, m, s, ms) = match.destructured
                val time = (h.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + ms.toLong()
                val shifted = (time + offsetMs).coerceAtLeast(0L)
                "%02d:%02d:%02d,%03d".format(
                    shifted / 3_600_000,
                    shifted / 60_000 % 60,
                    shifted / 1000 % 60,
                    shifted % 1000
                )
            }
    }
}

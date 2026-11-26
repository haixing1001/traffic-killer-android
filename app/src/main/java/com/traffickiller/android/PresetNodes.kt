package com.traffickiller.android

import java.net.URI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

data class PresetNode(
    val title: String,
    val url: String
)

object PresetNodes {
    val items: List<PresetNode> = listOf(
        PresetNode("自定义链接", ""),
        PresetNode(
            "移动云手机 H5 JS (1.12MB / cloudphoneh5.buy.139.com)",
            "https://cloudphoneh5.buy.139.com/js/chunk-vendors.47eadd9a.js"
        ),
        PresetNode(
            "移动云手机 Banner (392KB / cloud.139.com)",
            "https://cloud.139.com/cloudimage/dashboard/202604/2039876149437403136.png"
        ),
        PresetNode(
            "移动云手机静态图 (97KB / img.mcloud.139.com)",
            "https://img.mcloud.139.com/material_prod/material_media/20251104/1762247817800.png"
        ),
        PresetNode(
            "咪咕快游 影之刃零 (342MB / gcache.migu.cn)",
            "https://gcache.migu.cn/prod/upload/game_resource/small_screen_file/202610/1410/34/media-file-696ca8be0e3748139e6e6f988447644c/yingzhirenling.mp4"
        ),
        PresetNode(
            "咪咕视频 GIF (img.cmvideo.cn)",
            "https://img.cmvideo.cn/publish/noms/output/2026/10/09/1O7H81IQNJTSO.gif"
        ),
        PresetNode(
            "Cloudflare Speed 1GB [Global]",
            "https://speed.cloudflare.com/__down?bytes=1073741824"
        )
    )
}

object TrafficUnitFormatter {
    const val MIN_THREADS = 1
    const val MAX_THREADS = 32
    const val DEFAULT_THREADS = 4
    const val GIB_BYTES = 1024L * 1024L * 1024L

    private val byteUnits = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    private val bytePrecisions = intArrayOf(0, 0, 1, 2, 2, 2)
    private val speedUnits = arrayOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s", "PB/s")
    private val bitUnits = arrayOf("bps", "Kbps", "Mbps", "Gbps", "Tbps", "Pbps")
    private val bitPrecisions = intArrayOf(0, 0, 2, 2, 2, 2)

    fun clampThreadCount(raw: Int): Int = raw.coerceIn(MIN_THREADS, MAX_THREADS)

    fun parseThreadCount(raw: String): Int =
        raw.trim().toIntOrNull()?.let(::clampThreadCount) ?: DEFAULT_THREADS

    fun parseLimitGbToBytes(raw: String): Long {
        val gb = raw.trim().toDoubleOrNull() ?: return 0L
        if (!gb.isFinite() || gb <= 0.0) return 0L
        return (gb * GIB_BYTES.toDouble()).toLong().coerceAtLeast(1L)
    }

    fun isValidHttpUrl(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun formatBytes(bytes: Long): String =
        formatUnits(bytes.toDouble(), 1024.0, byteUnits, bytePrecisions)

    fun formatSpeed(bytesPerSec: Double): String =
        formatUnits(bytesPerSec, 1024.0, speedUnits, bytePrecisions)

    fun formatBitsPerSec(bitsPerSec: Double): String =
        formatUnits(bitsPerSec, 1000.0, bitUnits, bitPrecisions)

    private fun formatUnits(
        value: Double,
        base: Double,
        units: Array<String>,
        precisions: IntArray
    ): String {
        if (!value.isFinite() || value <= 0.0) return "0${units[0]}"
        val rawIndex = floor(ln(value) / ln(base)).toInt()
        val idx = rawIndex.coerceIn(0, units.lastIndex)
        val scaled = value / base.pow(idx.toDouble())
        val digits = precisions[idx]
        return String.format("%.${digits}f%s", scaled, units[idx])
    }
}

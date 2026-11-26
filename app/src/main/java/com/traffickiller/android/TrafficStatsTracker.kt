package com.traffickiller.android

import android.net.TrafficStats
import android.os.Process
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks real network bytes consumed during a run.
 *
 * Uses OS-level [TrafficStats] UID rx+tx deltas as primary truth so wire bytes
 * (including headers and compressed payloads) are reflected accurately, while
 * maintaining an application-level stream read counter as fallback when a device
 * reports [TrafficStats.UNSUPPORTED].
 */
class TrafficStatsTracker {
    private val uid = Process.myUid()
    private val appReadBytes = AtomicLong(0L)

    @Volatile
    private var baselineUidBytes: Long = -1L

    fun resetForNewRound() {
        appReadBytes.set(0L)
        baselineUidBytes = readUidTotalBytes()
    }

    fun recordAppRead(bytesRead: Int) {
        if (bytesRead > 0) {
            appReadBytes.addAndGet(bytesRead.toLong())
        }
    }

    fun getRoundConsumedBytes(): Long {
        val base = baselineUidBytes
        val current = readUidTotalBytes()
        if (base >= 0L && current >= base) {
            return current - base
        }
        return appReadBytes.get()
    }

    private fun readUidTotalBytes(): Long {
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return -1L
        }
        return (rx + tx).coerceAtLeast(0L)
    }
}

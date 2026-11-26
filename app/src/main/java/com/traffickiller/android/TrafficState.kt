package com.traffickiller.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class TrafficSnapshot(
    val isRunning: Boolean = false,
    val totalBytes: Long = 0L,
    val roundBytes: Long = 0L,
    val speedBytesPerSec: Double = 0.0,
    val limitBytes: Long = 5L * TrafficUnitFormatter.GIB_BYTES,
    val speedIsAverage: Boolean = false,
    val activeNetworkLabel: String = "未启动",
    val statusMessage: String = "",
    val isError: Boolean = false
)

object TrafficEngineStateHolder {
    private val _state = MutableStateFlow(TrafficSnapshot())
    val state: StateFlow<TrafficSnapshot> = _state.asStateFlow()

    // Base bytes accumulated across previous completed rounds before a manual reset.
    @Volatile
    var archivedTotalBytes: Long = 0L

    fun hasReachedLimit(limitBytes: Long): Boolean =
        limitBytes > 0L && archivedTotalBytes >= limitBytes

    fun update(transform: (TrafficSnapshot) -> TrafficSnapshot) {
        _state.update(transform)
    }

    fun resetTotalWhileStopped() {
        archivedTotalBytes = 0L
        _state.update {
            it.copy(
                totalBytes = 0L,
                roundBytes = 0L,
                speedBytesPerSec = 0.0,
                speedIsAverage = false,
                statusMessage = "",
                isError = false
            )
        }
    }
}

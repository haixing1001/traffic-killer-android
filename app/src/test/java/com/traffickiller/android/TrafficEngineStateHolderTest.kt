package com.traffickiller.android

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrafficEngineStateHolderTest {

    @Before
    fun setUp() {
        TrafficEngineStateHolder.resetTotalWhileStopped()
    }

    @After
    fun tearDown() {
        TrafficEngineStateHolder.resetTotalWhileStopped()
    }

    @Test
    fun hasReachedLimit_detectsLimitReachedAndAllowsRaiseOrReset() {
        val oneGb = TrafficUnitFormatter.GIB_BYTES
        TrafficEngineStateHolder.archivedTotalBytes = oneGb

        assertTrue(TrafficEngineStateHolder.hasReachedLimit(oneGb))
        assertTrue(TrafficEngineStateHolder.hasReachedLimit(oneGb - 1024L))
        assertFalse(TrafficEngineStateHolder.hasReachedLimit(oneGb * 2))
        assertFalse(TrafficEngineStateHolder.hasReachedLimit(0L))

        TrafficEngineStateHolder.resetTotalWhileStopped()
        assertFalse(TrafficEngineStateHolder.hasReachedLimit(oneGb))
    }
}

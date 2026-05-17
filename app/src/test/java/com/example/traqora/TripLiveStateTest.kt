package com.example.traqora

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TripLiveStateTest {

    private val startedAtEpochMs = 1_700_000_000_000L

    @Before
    fun setup() {
        TripLiveState.stopTrip()
        TripLiveState.startTrip("test-trip-1", startedAtEpochMs)
    }

    @Test
    fun `startTrip initializes active trip state`() {
        val state = TripLiveState.state.value

        assertEquals(true, state.isTracking)
        assertEquals("test-trip-1", state.tripId)
        assertEquals(startedAtEpochMs, state.startedAtEpochMs)
        assertEquals(0.0, state.distanceMeters, 0.001)
        assertEquals(0, state.harshEventCount)
        assertEquals(100, state.score)
    }

    @Test
    fun `updateLocation does not accumulate distance if speed is zero`() {
        val location1 = locationWithSpeed(0f)
        val location2 = locationWithSpeed(0f)
        every { location1.distanceTo(location2) } returns 10f

        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        val state = TripLiveState.state.value
        assertEquals(0f, state.speedMps)
        assertEquals(0.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `updateLocation accumulates distance if speed is greater than zero`() {
        val location1 = locationWithSpeed(5f)
        val location2 = locationWithSpeed(5f)
        every { location1.distanceTo(location2) } returns 10f

        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        val state = TripLiveState.state.value
        assertEquals(5f, state.speedMps)
        assertEquals(10.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `recordHarshEvent increments event count and lowers score`() {
        TripLiveState.recordHarshEvent()

        val state = TripLiveState.state.value
        assertEquals(1, state.harshEventCount)
        assertEquals(88, state.score)
    }

    @Test
    fun `stopTrip marks trip inactive and clears speed`() {
        TripLiveState.updateLocation(locationWithSpeed(7f), null)

        TripLiveState.stopTrip()

        val state = TripLiveState.state.value
        assertEquals(false, state.isTracking)
        assertEquals(null, state.speedMps)
        assertEquals("test-trip-1", state.tripId)
    }

    private fun locationWithSpeed(speedMps: Float): Location {
        return mockk {
            every { hasSpeed() } returns true
            every { speed } returns speedMps
        }
    }
}

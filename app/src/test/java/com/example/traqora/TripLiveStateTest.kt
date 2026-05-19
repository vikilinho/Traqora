package com.example.traqora

import android.location.Location
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TripLiveStateTest {

    @Before
    fun setup() {
        TripLiveState.stopTrip() // reset state
        TripLiveState.startTrip("test-trip-1", System.currentTimeMillis())
    }

    @Test
    fun `updateLocation does not accumulate distance if speed is zero`() {
        // Arrange: Fake a location drift of 10 meters but with 0 speed
        val location1 = mockk<Location>()
        every { location1.hasSpeed() } returns true
        every { location1.speed } returns 0f
        every { location1.time } returns 1000L

        val location2 = mockk<Location>()
        every { location2.hasSpeed() } returns true
        every { location2.speed } returns 0f
        every { location2.time } returns 2000L
        every { location1.distanceTo(location2) } returns 10f // Phantom drift!

        // Act
        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        // Assert: Distance should remain 0 because speed is 0
        val state = TripLiveState.state.value
        assertEquals(0f, state.speedMps)
        assertEquals(0.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `updateLocation accumulates distance if speed is greater than zero`() {
        // Arrange: Real movement with speed
        val location1 = mockk<Location>()
        every { location1.hasSpeed() } returns true
        every { location1.speed } returns 5f
        every { location1.time } returns 1000L

        val location2 = mockk<Location>()
        every { location2.hasSpeed() } returns true
        every { location2.speed } returns 5f
        every { location2.time } returns 2000L
        every { location1.distanceTo(location2) } returns 10f // Real movement

        // Act
        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        // Assert: Distance should increase by 10 meters
        val state = TripLiveState.state.value
        assertEquals(5f, state.speedMps)
        assertEquals(10.0, state.distanceMeters, 0.001)
    }

    @Test
    fun `updateLocation applies distance decay to penalty points`() {
        // Arrange
        // Add 20 penalty points first
        TripLiveState.recordHarshEvent(1.0f) // 1.0g * 20 = 20 points
        var state = TripLiveState.state.value
        assertEquals(20.0, state.penaltyPoints, 0.001)
        assertEquals(80, state.score)

        // Fake a 5-mile safe drive (8046.7 meters is the half-life)
        val location1 = mockk<Location>()
        every { location1.hasSpeed() } returns true
        every { location1.speed } returns 20f
        every { location1.time } returns 2000L

        val location2 = mockk<Location>()
        every { location2.hasSpeed() } returns true
        every { location2.speed } returns 20f
        every { location2.time } returns 3000L
        every { location1.distanceTo(location2) } returns TripScoreCalculator.HALF_LIFE_METERS.toFloat()

        // Act
        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        // Assert
        state = TripLiveState.state.value
        // Penalty should halve from 20 to 10
        assertEquals(10.0, state.penaltyPoints, 0.001)
        assertEquals(90, state.score)
    }

    @Test
    fun `updateLocation applies speed penalty if over 80mph`() {
        // Arrange
        val location1 = mockk<Location>()
        every { location1.hasSpeed() } returns true
        every { location1.speed } returns 40f // ~89 mph, over 35.76
        every { location1.time } returns 1000L

        val location2 = mockk<Location>()
        every { location2.hasSpeed() } returns true
        every { location2.speed } returns 40f
        every { location2.time } returns 11000L // 10 seconds later
        every { location1.distanceTo(location2) } returns 400f 

        // Act
        TripLiveState.updateLocation(location1, null)
        TripLiveState.updateLocation(location2, location1)

        // Assert
        val state = TripLiveState.state.value
        // 10 seconds * 0.05 penalty = 0.5 points
        assertEquals(0.5, state.penaltyPoints, 0.001)
        assertEquals(99, state.score)
    }
}

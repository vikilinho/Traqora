package com.example.traqora.data

import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRepositoryShareTextTest {

    @Test
    fun `buildShareText formats key trip summary values`() {
        val repository = TripRepository(mockk<TripDao>())
        val summary = TripSummary(
            tripId = "trip-1",
            startedAtEpochMs = 1_700_000_000_000L,
            endedAtEpochMs = null,
            status = TripEntity.STATUS_ACTIVE,
            distanceMeters = 1609.344,
            harshEventCount = 2,
            locationSampleCount = 42,
            averageSpeedMps = 13.4112f,
            score = 92
        )

        val shareText = repository.buildShareText(summary)

        assertTrue(shareText.contains("Traqora trip summary"))
        assertTrue(shareText.contains("Score: 92/100"))
        assertTrue(shareText.contains("Distance: 1.00 mi"))
        assertTrue(shareText.contains("Harsh events: 2"))
        assertTrue(shareText.contains("Location samples: 42"))
        assertTrue(shareText.contains("Average speed: 30 mph"))
        assertTrue(shareText.contains("Ended: In progress"))
    }
}

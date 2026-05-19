package com.example.traqora

import com.example.traqora.data.TelemetryEventEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class TripScoreCalculatorTest {

    @Test
    fun `harsh event penalty scales with g force severity`() {
        val events = listOf(
            harshEvent(timestampEpochMs = 1_000L, gForce = 0.7f)
        )

        val penalty = TripScoreCalculator.calculatePenaltyPointsFromEvents(events)
        val score = TripScoreCalculator.calculateScoreFromEvents(events)

        assertEquals(14.0, penalty, 0.001)
        assertEquals(86, score)
    }

    @Test
    fun `braking and cornering events apply penalty points correctly`() {
        val events = listOf(
            TelemetryEventEntity(
                tripId = TRIP_ID,
                timestampEpochMs = 1_000L,
                type = TelemetryEventEntity.TYPE_HARSH_BRAKING,
                value = -0.6f
            ),
            TelemetryEventEntity(
                tripId = TRIP_ID,
                timestampEpochMs = 2_000L,
                type = TelemetryEventEntity.TYPE_HARSH_CORNERING,
                value = 0.5f
            )
        )

        val penalty = TripScoreCalculator.calculatePenaltyPointsFromEvents(events)
        val score = TripScoreCalculator.calculateScoreFromEvents(events)

        // Penalty = 0.6 * 20.0 + 0.5 * 20.0 = 12.0 + 10.0 = 22.0
        assertEquals(22.0, penalty, 0.001)
        assertEquals(78, score)
    }

    @Test
    fun `safe driving over one half life halves existing penalty`() {
        val events = listOf(
            harshEvent(timestampEpochMs = 1_000L, gForce = 1.0f),
            locationEvent(
                timestampEpochMs = 2_000L,
                longitude = 0.0,
                speedMps = 20f
            ),
            locationEvent(
                timestampEpochMs = 3_000L,
                longitude = longitudeDeltaForMeters(TripScoreCalculator.HALF_LIFE_METERS),
                speedMps = 20f
            )
        )

        val penalty = TripScoreCalculator.calculatePenaltyPointsFromEvents(events)
        val score = TripScoreCalculator.calculateScoreFromEvents(events)

        assertEquals(10.0, penalty, 0.001)
        assertEquals(90, score)
    }

    @Test
    fun `high speed adds penalty by elapsed seconds`() {
        val events = listOf(
            locationEvent(
                timestampEpochMs = 1_000L,
                longitude = 0.0,
                speedMps = 40f
            ),
            locationEvent(
                timestampEpochMs = 11_000L,
                longitude = longitudeDeltaForMeters(400.0),
                speedMps = 40f
            )
        )

        val penalty = TripScoreCalculator.calculatePenaltyPointsFromEvents(events)
        val score = TripScoreCalculator.calculateScoreFromEvents(events)

        assertEquals(0.5, penalty, 0.001)
        assertEquals(99, score)
    }

    private fun harshEvent(timestampEpochMs: Long, gForce: Float): TelemetryEventEntity {
        return TelemetryEventEntity(
            tripId = TRIP_ID,
            timestampEpochMs = timestampEpochMs,
            type = TelemetryEventEntity.TYPE_HARSH_ACCELERATION,
            value = gForce
        )
    }

    private fun locationEvent(
        timestampEpochMs: Long,
        longitude: Double,
        speedMps: Float
    ): TelemetryEventEntity {
        return TelemetryEventEntity(
            tripId = TRIP_ID,
            timestampEpochMs = timestampEpochMs,
            type = TelemetryEventEntity.TYPE_LOCATION,
            latitude = 0.0,
            longitude = longitude,
            speedMps = speedMps
        )
    }

    private fun longitudeDeltaForMeters(meters: Double): Double {
        return Math.toDegrees(meters / EARTH_RADIUS_METERS)
    }

    private companion object {
        const val TRIP_ID = "test-trip"
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}

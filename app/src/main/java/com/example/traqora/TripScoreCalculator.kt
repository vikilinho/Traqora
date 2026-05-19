package com.example.traqora

import com.example.traqora.data.TelemetryEventEntity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object TripScoreCalculator {
    const val HALF_LIFE_METERS = 8046.7 // 5 miles
    const val HIGH_SPEED_THRESHOLD_MPS = 35.76f // 80 mph
    const val SPEED_PENALTY_PER_SECOND = 0.05f
    const val HARSH_EVENT_MULTIPLIER = 20.0f

    fun calculateScoreFromEvents(events: List<TelemetryEventEntity>): Int {
        return (100 - calculatePenaltyPointsFromEvents(events).roundToInt()).coerceIn(0, 100)
    }

    fun calculatePenaltyPointsFromEvents(events: List<TelemetryEventEntity>): Double {
        var penaltyPoints = 0.0
        var previousLocationEvent: TelemetryEventEntity? = null

        val sortedEvents = events.sortedBy { it.timestampEpochMs }

        for (event in sortedEvents) {
            if (event.type == TelemetryEventEntity.TYPE_LOCATION) {
                val previous = previousLocationEvent
                if (previous != null) {
                    val deltaMeters = distanceMetersBetween(previous, event)
                    if (deltaMeters != null) {
                        val speedToUse = event.speedMps ?: previous.speedMps ?: 0f
                        if (speedToUse > 0f && deltaMeters > 0) {
                            val decayFactor = 0.5.pow(deltaMeters / HALF_LIFE_METERS)
                            penaltyPoints *= decayFactor
                        }
                    }

                    val speed = event.speedMps ?: 0f
                    if (speed > HIGH_SPEED_THRESHOLD_MPS) {
                        val elapsedMs = event.timestampEpochMs - previous.timestampEpochMs
                        if (elapsedMs in 1..MAX_SCORABLE_GPS_GAP_MS) {
                            val elapsedSeconds = elapsedMs / 1000.0
                            penaltyPoints += elapsedSeconds * SPEED_PENALTY_PER_SECOND
                        }
                    }
                }
                previousLocationEvent = event
            } else if (event.type == TelemetryEventEntity.TYPE_HARSH_ACCELERATION) {
                val gForce = event.value ?: 0.3f
                penaltyPoints += abs(gForce) * HARSH_EVENT_MULTIPLIER
            }
        }

        return penaltyPoints
    }

    fun scoreFromPenaltyPoints(penaltyPoints: Double): Int {
        return (100 - penaltyPoints.roundToInt()).coerceIn(0, 100)
    }

    private fun distanceMetersBetween(
        previous: TelemetryEventEntity,
        current: TelemetryEventEntity
    ): Double? {
        val previousLatitude = previous.latitude ?: return null
        val previousLongitude = previous.longitude ?: return null
        val currentLatitude = current.latitude ?: return null
        val currentLongitude = current.longitude ?: return null

        val previousLatRadians = Math.toRadians(previousLatitude)
        val currentLatRadians = Math.toRadians(currentLatitude)
        val deltaLatRadians = Math.toRadians(currentLatitude - previousLatitude)
        val deltaLonRadians = Math.toRadians(currentLongitude - previousLongitude)

        val haversine = sin(deltaLatRadians / 2).pow(2.0) +
            cos(previousLatRadians) * cos(currentLatRadians) *
            sin(deltaLonRadians / 2).pow(2.0)
        val centralAngle = 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
        return EARTH_RADIUS_METERS * centralAngle
    }

    private const val MAX_SCORABLE_GPS_GAP_MS = 10_000L
    private const val EARTH_RADIUS_METERS = 6_371_000.0
}

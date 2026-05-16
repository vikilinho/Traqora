package com.example.traqora.data

import android.location.Location
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class TripRepository(private val tripDao: TripDao) {

    suspend fun getLatestTripSummary(): TripSummary? {
        val trip = tripDao.getLatestTrip() ?: return null
        return buildTripSummary(trip)
    }

    suspend fun getRecentTripSummaries(limit: Int = 20): List<TripSummary> {
        return tripDao.getRecentTrips(limit).map { trip ->
            buildTripSummary(trip)
        }
    }

    suspend fun deleteAllTrips() {
        tripDao.deleteAllTrips()
    }

    suspend fun deleteTrip(tripId: String) {
        tripDao.deleteTripById(tripId)
    }

    suspend fun completeActiveTrips() {
        tripDao.completeActiveTrips(endedAtEpochMs = System.currentTimeMillis())
    }

    private suspend fun buildTripSummary(trip: TripEntity): TripSummary {
        val events = tripDao.getTelemetryEventsForTrip(trip.id)
        val locationEvents = events.filter { it.type == TelemetryEventEntity.TYPE_LOCATION }
        val harshEventCount = events.count { it.type == TelemetryEventEntity.TYPE_HARSH_ACCELERATION }
        val distanceMeters = calculateDistanceMeters(locationEvents)
        val averageSpeedMps = locationEvents
            .mapNotNull { it.speedMps }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toFloat()

        return TripSummary(
            tripId = trip.id,
            startedAtEpochMs = trip.startedAtEpochMs,
            endedAtEpochMs = trip.endedAtEpochMs,
            status = trip.status,
            distanceMeters = distanceMeters,
            harshEventCount = harshEventCount,
            locationSampleCount = locationEvents.size,
            averageSpeedMps = averageSpeedMps,
            score = calculateScore(distanceMeters, harshEventCount)
        )
    }

    fun buildShareText(summary: TripSummary): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val started = formatter.format(Date(summary.startedAtEpochMs))
        val ended = summary.endedAtEpochMs?.let { formatter.format(Date(it)) } ?: "In progress"
        val miles = summary.distanceMeters * METERS_TO_MILES
        val averageMph = summary.averageSpeedMps?.times(MPS_TO_MPH)

        return buildString {
            appendLine("Traqora trip summary")
            appendLine("Score: ${summary.score}/100")
            appendLine("Distance: ${String.format(Locale.US, "%.2f mi", miles)}")
            appendLine("Harsh events: ${summary.harshEventCount}")
            appendLine("Location samples: ${summary.locationSampleCount}")
            appendLine("Average speed: ${averageMph?.let { String.format(Locale.US, "%.0f mph", it) } ?: "Not available"}")
            appendLine("Started: $started")
            appendLine("Ended: $ended")
        }
    }

    private fun calculateDistanceMeters(locationEvents: List<TelemetryEventEntity>): Double {
        var total = 0.0
        var previous: TelemetryEventEntity? = null

        for (event in locationEvents) {
            val prior = previous
            if (prior?.latitude != null &&
                prior.longitude != null &&
                event.latitude != null &&
                event.longitude != null
            ) {
                val result = FloatArray(1)
                Location.distanceBetween(
                    prior.latitude,
                    prior.longitude,
                    event.latitude,
                    event.longitude,
                    result
                )
                if (result[0].isFinite() && result[0] >= 0f) {
                    total += result[0].toDouble()
                }
            }
            previous = event
        }

        return total
    }

    private fun calculateScore(distanceMeters: Double, harshEventCount: Int): Int {
        if (distanceMeters <= 0.0 && harshEventCount == 0) return 100

        val miles = distanceMeters * METERS_TO_MILES
        val eventRatePenalty = if (miles > 0.1) {
            ((harshEventCount / miles) * 10).roundToInt()
        } else {
            harshEventCount * 12
        }

        return (100 - eventRatePenalty).coerceIn(0, 100)
    }

    companion object {
        private const val METERS_TO_MILES = 0.000621371
        private const val MPS_TO_MPH = 2.2369363f
    }
}

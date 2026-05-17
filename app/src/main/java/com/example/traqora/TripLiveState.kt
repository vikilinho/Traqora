package com.example.traqora

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

data class LiveTripState(
    val isTracking: Boolean = false,
    val tripId: String? = null,
    val startedAtEpochMs: Long? = null,
    val speedMps: Float? = null,
    val distanceMeters: Double = 0.0,
    val harshEventCount: Int = 0,
    val score: Int = 100
)

object TripLiveState {

    private val mutableState = MutableStateFlow(LiveTripState())
    val state: StateFlow<LiveTripState> = mutableState.asStateFlow()

    fun startTrip(tripId: String, startedAtEpochMs: Long) {
        mutableState.value = LiveTripState(
            isTracking = true,
            tripId = tripId,
            startedAtEpochMs = startedAtEpochMs
        )
    }

    fun updateLocation(location: Location, previousLocation: Location?) {
        mutableState.update { current ->
            val deltaMeters = previousLocation?.distanceTo(location)?.takeIf {
                it.isFinite() && it >= 0f
            } ?: 0f
            
            // Only accumulate distance if the vehicle is actually moving
            val speedToUse = if (location.hasSpeed()) location.speed else current.speedMps ?: 0f
            val actualDelta = if (speedToUse > 0f) deltaMeters else 0f
            val nextDistance = current.distanceMeters + actualDelta

            current.copy(
                speedMps = if (location.hasSpeed()) location.speed else current.speedMps,
                distanceMeters = nextDistance,
                score = calculateScore(nextDistance, current.harshEventCount)
            )
        }
    }

    fun recordHarshEvent() {
        mutableState.update { current ->
            val nextHarshEventCount = current.harshEventCount + 1
            current.copy(
                harshEventCount = nextHarshEventCount,
                score = calculateScore(current.distanceMeters, nextHarshEventCount)
            )
        }
    }

    fun stopTrip() {
        mutableState.update {
            it.copy(isTracking = false, speedMps = null)
        }
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

    private const val METERS_TO_MILES = 0.000621371
}

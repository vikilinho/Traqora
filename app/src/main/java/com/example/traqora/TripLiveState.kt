package com.example.traqora

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs
import kotlin.math.pow

data class LiveTripState(
    val isTracking: Boolean = false,
    val tripId: String? = null,
    val startedAtEpochMs: Long? = null,
    val speedMps: Float? = null,
    val distanceMeters: Double = 0.0,
    val harshEventCount: Int = 0,
    val penaltyPoints: Double = 0.0,
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
            
            val speedToUse = if (location.hasSpeed()) location.speed else current.speedMps ?: 0f
            val actualDelta = if (speedToUse > 0f) deltaMeters else 0f
            val nextDistance = current.distanceMeters + actualDelta

            var nextPenalty = current.penaltyPoints
            if (actualDelta > 0) {
                val decayFactor = 0.5.pow(actualDelta / TripScoreCalculator.HALF_LIFE_METERS)
                nextPenalty *= decayFactor
            }

            if (previousLocation != null && speedToUse > TripScoreCalculator.HIGH_SPEED_THRESHOLD_MPS) {
                val elapsedMs = location.time - previousLocation.time
                if (elapsedMs in 1..10000) {
                    nextPenalty += (elapsedMs / 1000.0) * TripScoreCalculator.SPEED_PENALTY_PER_SECOND
                }
            }

            current.copy(
                speedMps = if (location.hasSpeed()) location.speed else current.speedMps,
                distanceMeters = nextDistance,
                penaltyPoints = nextPenalty,
                score = TripScoreCalculator.scoreFromPenaltyPoints(nextPenalty)
            )
        }
    }

    fun recordHarshEvent(gForce: Float) {
        mutableState.update { current ->
            val nextPenalty = current.penaltyPoints + (abs(gForce) * TripScoreCalculator.HARSH_EVENT_MULTIPLIER)
            current.copy(
                harshEventCount = current.harshEventCount + 1,
                penaltyPoints = nextPenalty,
                score = TripScoreCalculator.scoreFromPenaltyPoints(nextPenalty)
            )
        }
    }

    fun stopTrip() {
        mutableState.value = LiveTripState(isTracking = false)
    }
}

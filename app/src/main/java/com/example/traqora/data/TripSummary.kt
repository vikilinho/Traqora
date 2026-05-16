package com.example.traqora.data

data class TripSummary(
    val tripId: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: String,
    val distanceMeters: Double,
    val harshEventCount: Int,
    val locationSampleCount: Int,
    val averageSpeedMps: Float?,
    val score: Int
)

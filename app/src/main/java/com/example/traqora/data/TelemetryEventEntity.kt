package com.example.traqora.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "telemetry_events",
    foreignKeys = [
        ForeignKey(
            entity = TripEntity::class,
            parentColumns = ["id"],
            childColumns = ["tripId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tripId"), Index("timestampEpochMs")]
)
data class TelemetryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val tripId: String,
    val timestampEpochMs: Long,
    val type: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Float? = null,
    val accuracyMeters: Float? = null,
    val value: Float? = null,
    val message: String? = null
) {
    companion object {
        const val TYPE_LOCATION = "location"
        const val TYPE_HARSH_ACCELERATION = "harsh_acceleration"
        const val TYPE_HARSH_BRAKING = "harsh_braking"
        const val TYPE_HARSH_CORNERING = "harsh_cornering"
    }
}

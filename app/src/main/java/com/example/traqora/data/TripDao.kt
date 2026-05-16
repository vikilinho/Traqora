package com.example.traqora.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TripDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrip(trip: TripEntity)

    @Insert
    suspend fun insertTelemetryEvent(event: TelemetryEventEntity)

    @Query("SELECT * FROM trips WHERE status = :status ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestTripByStatus(status: String = TripEntity.STATUS_ACTIVE): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun getLatestTrip(): TripEntity?

    @Query("SELECT * FROM trips ORDER BY startedAtEpochMs DESC LIMIT :limit")
    suspend fun getRecentTrips(limit: Int = 20): List<TripEntity>

    @Query("SELECT * FROM telemetry_events WHERE tripId = :tripId ORDER BY timestampEpochMs ASC")
    suspend fun getTelemetryEventsForTrip(tripId: String): List<TelemetryEventEntity>

    @Query("DELETE FROM trips")
    suspend fun deleteAllTrips()

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTripById(tripId: String)

    @Query(
        """
        UPDATE trips
        SET status = :completedStatus, endedAtEpochMs = COALESCE(endedAtEpochMs, :endedAtEpochMs)
        WHERE status = :activeStatus
        """
    )
    suspend fun completeActiveTrips(
        endedAtEpochMs: Long,
        activeStatus: String = TripEntity.STATUS_ACTIVE,
        completedStatus: String = TripEntity.STATUS_COMPLETED
    )

    @Query(
        """
        UPDATE trips
        SET status = :completedStatus, endedAtEpochMs = :endedAtEpochMs
        WHERE id = :tripId
        """
    )
    suspend fun completeTrip(
        tripId: String,
        endedAtEpochMs: Long,
        completedStatus: String = TripEntity.STATUS_COMPLETED
    )
}

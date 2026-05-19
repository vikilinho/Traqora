package com.example.traqora

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.traqora.data.TelemetryEventEntity
import com.example.traqora.data.TraqoraDatabase
import com.example.traqora.data.TripDao
import com.example.traqora.data.TripEntity
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class TripTrackerService : LifecycleService(), SensorEventListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var tripDao: TripDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var accelerometer: Sensor? = null
    private var lastLocation: Location? = null
    private var isTracking = false
    private var activeTripId: String? = null
    private var tripStartJob: Job? = null
    private var autoStopJob: Job? = null

    private val gravity = FloatArray(AXIS_COUNT)
    private val linearAcceleration = FloatArray(AXIS_COUNT)
    private val drivingVector = FloatArray(AXIS_COUNT) { if (it == AXIS_Y) 1f else 0f }
    private var hasGravityBaseline = false
    private var hasDrivingVectorBaseline = false

    private val locationRequest: LocationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MS)
            .build()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                // Filter inaccurate locations to prevent GPS drift
                if (location.hasAccuracy() && location.accuracy > MIN_LOCATION_ACCURACY_METERS) {
                    Log.d(TAG, "Ignoring inaccurate location update: accuracyM=${location.accuracy}")
                    return
                }

                // Apply speed deadband
                val processedLocation = Location(location).apply {
                    if (hasSpeed() && speed < MIN_SPEED_DEADBAND_MPS) {
                        speed = 0f
                    }
                }

                val previousLocation = lastLocation
                lastLocation = processedLocation
                TripLiveState.updateLocation(processedLocation, previousLocation)
                persistLocationUpdate(processedLocation)
                Log.d(
                    TAG,
                    "Location update: lat=${processedLocation.latitude}, lon=${processedLocation.longitude}, " +
                        "speedMps=${processedLocation.speed}, accuracyM=${processedLocation.accuracy}"
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        tripDao = TraqoraDatabase.getInstance(this).tripDao()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopTrackingAndFinish()
                return Service.START_NOT_STICKY
            }
            ACTION_VEHICLE_EXIT -> {
                scheduleAutoStop()
            }
            ACTION_START, null -> startTrackingIfAllowed()
            else -> Log.w(TAG, "Unknown action received: ${intent.action}")
        }

        return Service.START_STICKY
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        // Ignore harsh events if the vehicle is not moving fast enough
        val currentSpeed = lastLocation?.speed ?: 0f
        if (currentSpeed < MIN_SPEED_FOR_EVENTS_MPS) {
            return
        }

        val longitudinalAccelerationG = isolateDrivingVectorAcceleration(event.values)
        if (abs(longitudinalAccelerationG) > SUDDEN_CHANGE_THRESHOLD_G) {
            persistHarshAccelerationEvent(longitudinalAccelerationG)
            Log.w(
                TAG,
                "Driving alert: sudden longitudinal change=${"%.3f".format(longitudinalAccelerationG)}g, " +
                    "threshold=${SUDDEN_CHANGE_THRESHOLD_G}g"
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: sensor=${sensor?.name}, accuracy=$accuracy")
    }

    override fun onDestroy() {
        stopLocationUpdates()
        stopAccelerometerUpdates()
        completeActiveTripIfNeeded()
        isTracking = false
        activeTripId = null
        tripStartJob = null
        autoStopJob?.cancel()
        autoStopJob = null
        TripLiveState.stopTrip()
        serviceScope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTrackingIfAllowed() {
        cancelAutoStop()
        if (isTracking) return

        if (!hasLocationPermission()) {
            Log.e(TAG, "Trip tracking cannot start without fine or coarse location permission.")
            stopSelf()
            return
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildForegroundNotification(),
            foregroundServiceType()
        )

        startNewTrip()
        startLocationUpdates()
        startAccelerometerUpdates()
        isTracking = true
    }

    private fun stopTrackingAndFinish() {
        if (!isTracking && activeTripId == null) {
            stopSelf()
            return
        }

        stopLocationUpdates()
        stopAccelerometerUpdates()
        isTracking = false
        TripLiveState.stopTrip()

        val tripId = activeTripId
        activeTripId = null

        serviceScope.launch {
            tripStartJob?.join()
            if (tripId != null) {
                tripDao.completeTrip(
                    tripId = tripId,
                    endedAtEpochMs = System.currentTimeMillis()
                )
            }
            stopSelf()
        }
    }

    private fun scheduleAutoStop() {
        if (!isTracking) return

        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            Log.d(TAG, "Scheduling auto-stop in 3 minutes due to vehicle exit...")
            delay(180_000L) // 3 minutes
            Log.d(TAG, "Vehicle exit grace period expired. Stopping trip automatically.")
            stopTrackingAndFinish()
        }
    }

    private fun cancelAutoStop() {
        if (autoStopJob != null) {
            Log.d(TAG, "Canceling scheduled auto-stop")
            autoStopJob?.cancel()
            autoStopJob = null
        }
    }

    private fun startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "Location permission was revoked while starting updates.", exception)
            stopSelf()
        }
    }

    private fun stopLocationUpdates() {
        if (::fusedLocationClient.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }

    private fun startAccelerometerUpdates() {
        val sensor = accelerometer
        if (sensor == null) {
            Log.w(TAG, "Accelerometer unavailable; harsh driving detection disabled.")
            return
        }

        sensorManager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    private fun stopAccelerometerUpdates() {
        if (::sensorManager.isInitialized) {
            sensorManager.unregisterListener(this)
        }
    }

    private fun startNewTrip() {
        val tripId = UUID.randomUUID().toString()
        val startedAtEpochMs = System.currentTimeMillis()
        activeTripId = tripId
        lastLocation = null
        TripLiveState.startTrip(tripId, startedAtEpochMs)

        tripStartJob = serviceScope.launch {
            tripDao.completeActiveTrips(endedAtEpochMs = startedAtEpochMs)
            tripDao.upsertTrip(
                TripEntity(
                    id = tripId,
                    startedAtEpochMs = startedAtEpochMs
                )
            )
        }
    }

    private fun completeActiveTripIfNeeded() {
        val tripId = activeTripId ?: return

        runBlocking(Dispatchers.IO) {
            tripDao.completeTrip(
                tripId = tripId,
                endedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    private fun persistLocationUpdate(location: Location) {
        val tripId = activeTripId ?: return

        serviceScope.launch {
            tripStartJob?.join()
            tripDao.insertTelemetryEvent(
                TelemetryEventEntity(
                    tripId = tripId,
                    timestampEpochMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                    type = TelemetryEventEntity.TYPE_LOCATION,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedMps = if (location.hasSpeed()) location.speed else null,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null
                )
            )
        }
    }

    private fun persistHarshAccelerationEvent(longitudinalAccelerationG: Float) {
        val tripId = activeTripId ?: return
        TripLiveState.recordHarshEvent(longitudinalAccelerationG)

        serviceScope.launch {
            tripStartJob?.join()
            tripDao.insertTelemetryEvent(
                TelemetryEventEntity(
                    tripId = tripId,
                    timestampEpochMs = System.currentTimeMillis(),
                    type = TelemetryEventEntity.TYPE_HARSH_ACCELERATION,
                    value = longitudinalAccelerationG,
                    message = "Sudden longitudinal acceleration exceeded ${SUDDEN_CHANGE_THRESHOLD_G}g"
                )
            )
        }
    }

    private fun isolateDrivingVectorAcceleration(values: FloatArray): Float {
        for (axis in 0 until AXIS_COUNT) {
            if (!hasGravityBaseline) {
                gravity[axis] = values[axis]
            } else {
                gravity[axis] = GRAVITY_ALPHA * gravity[axis] + (1f - GRAVITY_ALPHA) * values[axis]
            }
            linearAcceleration[axis] = values[axis] - gravity[axis]
        }
        hasGravityBaseline = true

        val magnitude = linearAcceleration.magnitude()
        if (magnitude > DRIVING_VECTOR_LEARNING_THRESHOLD) {
            for (axis in 0 until AXIS_COUNT) {
                val normalizedAxis = linearAcceleration[axis] / magnitude
                drivingVector[axis] = if (hasDrivingVectorBaseline) {
                    DRIVING_VECTOR_ALPHA * drivingVector[axis] +
                        (1f - DRIVING_VECTOR_ALPHA) * normalizedAxis
                } else {
                    normalizedAxis
                }
            }
            drivingVector.normalizeInPlace()
            hasDrivingVectorBaseline = true
        }

        val longitudinalAccelerationMs2 = linearAcceleration.dot(drivingVector)
        return longitudinalAccelerationMs2 / SensorManager.GRAVITY_EARTH
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun foregroundServiceType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = NOTIFICATION_CHANNEL_DESCRIPTION
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, launchIntent, pendingIntentFlags)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.trip_tracker_notification_title))
            .setContentText(getString(R.string.trip_tracker_notification_text))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun FloatArray.magnitude(): Float {
        return sqrt(dot(this))
    }

    private fun FloatArray.dot(other: FloatArray): Float {
        return this[AXIS_X] * other[AXIS_X] +
            this[AXIS_Y] * other[AXIS_Y] +
            this[AXIS_Z] * other[AXIS_Z]
    }

    private fun FloatArray.normalizeInPlace() {
        val magnitude = magnitude()
        if (magnitude <= 0f) return

        for (axis in 0 until AXIS_COUNT) {
            this[axis] /= magnitude
        }
    }

    companion object {
        private const val TAG = "TripTrackerService"

        const val ACTION_START = "com.example.traqora.action.START_TRIP_TRACKING"
        const val ACTION_STOP = "com.example.traqora.action.STOP_TRIP_TRACKING"
        const val ACTION_VEHICLE_EXIT = "com.example.traqora.action.VEHICLE_EXIT"

        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "trip_tracker"
        private const val NOTIFICATION_CHANNEL_NAME = "Trip tracking"
        private const val NOTIFICATION_CHANNEL_DESCRIPTION =
            "Shows persistent trip tracking status while Traqora records driving data."

        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val AXIS_COUNT = 3
        private const val AXIS_X = 0
        private const val AXIS_Y = 1
        private const val AXIS_Z = 2
        private const val GRAVITY_ALPHA = 0.8f
        private const val DRIVING_VECTOR_ALPHA = 0.95f
        private const val DRIVING_VECTOR_LEARNING_THRESHOLD = 0.15f
        private const val SUDDEN_CHANGE_THRESHOLD_G = 0.3f
        private const val MIN_LOCATION_ACCURACY_METERS = 20.0f
        private const val MIN_SPEED_DEADBAND_MPS = 1.0f
        private const val MIN_SPEED_FOR_EVENTS_MPS = 2.2f
    }
}

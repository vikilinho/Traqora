package com.example.traqora

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.hardware.common.OnCarDataAvailableListener
import androidx.car.app.hardware.info.CarInfo
import androidx.car.app.hardware.info.Speed
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs

class LiveTelemetryScreen(carContext: CarContext) : Screen(carContext) {

    private val mainExecutor: Executor = ContextCompat.getMainExecutor(carContext)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(carContext)

    private var carInfo: CarInfo? = null
    private var isUsingVehicleHardware = false
    private var isUsingLocationFallback = false
    private var hasStarted = false

    private var speedMps: Float? = null
    private var distanceMeters = 0.0
    private var previousLocation: Location? = null
    private var lastSpeedIntegrationElapsedMs: Long? = null
    private var connectionHealth = "Starting telemetry"

    private val speedListener = OnCarDataAvailableListener<Speed> { speed ->
        val displaySpeed = speed.displaySpeedMetersPerSecond.successValue()
        val rawSpeed = speed.rawSpeedMetersPerSecond.successValue()
        val resolvedSpeed = displaySpeed ?: rawSpeed

        if (resolvedSpeed == null) {
            connectionHealth = "Vehicle speed unavailable"
            startLocationFallback("Vehicle speed value unavailable")
        } else {
            isUsingVehicleHardware = true
            updateSpeedFromVehicleHardware(resolvedSpeed)
            connectionHealth = "Vehicle hardware connected"
            invalidate()
        }
    }

    private val fallbackLocationRequest: LocationRequest by lazy {
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
            .setMaxUpdateDelayMillis(LOCATION_INTERVAL_MS)
            .build()
    }

    private val fallbackLocationListener = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            val previous = previousLocation

            if (previous != null) {
                val deltaMeters = previous.distanceTo(location).toDouble()
                if (deltaMeters.isFinite() && deltaMeters >= 0.0) {
                    distanceMeters += deltaMeters
                }
            }

            previousLocation = location
            speedMps = when {
                location.hasSpeed() -> location.speed
                previous != null -> estimateSpeedFromLocations(previous, location)
                else -> speedMps
            }
            connectionHealth = "Phone GPS fallback active"
            invalidate()
        }
    }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                startTelemetry()
            }

            override fun onStop(owner: LifecycleOwner) {
                stopTelemetry()
            }

            override fun onDestroy(owner: LifecycleOwner) {
                stopTelemetry()
            }
        })
    }

    override fun onGetTemplate(): Template {
        if (!hasStarted) {
            startTelemetry()
        }

        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Live speed")
                    .addText(formatSpeed(speedMps))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Trip distance")
                    .addText(formatDistance(distanceMeters))
                    .build()
            )
            .addRow(
                Row.Builder()
                    .setTitle("Connection")
                    .addText(connectionHealth)
                    .build()
            )
            .build()

        return PaneTemplate.Builder(pane)
            .setTitle("Traqora telemetry")
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun startTelemetry() {
        if (hasStarted) return
        hasStarted = true

        if (!startVehicleHardwareSpeedUpdates()) {
            startLocationFallback("Vehicle hardware unavailable")
        }
    }

    private fun stopTelemetry() {
        stopVehicleHardwareSpeedUpdates()
        stopLocationFallback()
        hasStarted = false
        isUsingVehicleHardware = false
        connectionHealth = "Telemetry disconnected"
    }

    private fun startVehicleHardwareSpeedUpdates(): Boolean {
        return try {
            val hardwareManager = carContext.getCarService(CarContext.HARDWARE_SERVICE)
                as CarHardwareManager
            val info = hardwareManager.carInfo
            carInfo = info

            info.addSpeedListener(mainExecutor, speedListener)
            connectionHealth = "Vehicle hardware initializing"
            true
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to initialize vehicle speed API; using location fallback.", exception)
            false
        } catch (exception: LinkageError) {
            Log.w(TAG, "Vehicle hardware API unavailable on this host; using location fallback.", exception)
            false
        }
    }

    private fun stopVehicleHardwareSpeedUpdates() {
        try {
            carInfo?.removeSpeedListener(speedListener)
        } catch (exception: RuntimeException) {
            Log.w(TAG, "Unable to remove vehicle speed listener.", exception)
        } finally {
            carInfo = null
        }
    }

    private fun startLocationFallback(reason: String) {
        if (isUsingLocationFallback || isUsingVehicleHardware) return

        if (!hasLocationPermission()) {
            connectionHealth = "Location permission required"
            invalidate()
            return
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                fallbackLocationRequest,
                fallbackLocationListener,
                carContext.mainLooper
            )
            isUsingLocationFallback = true
            connectionHealth = "$reason; using phone GPS"
            invalidate()
        } catch (exception: SecurityException) {
            connectionHealth = "Location permission revoked"
            Log.e(TAG, "Unable to start fallback location listener.", exception)
            invalidate()
        } catch (exception: RuntimeException) {
            connectionHealth = "Telemetry unavailable"
            Log.e(TAG, "Unable to start fallback location listener.", exception)
            invalidate()
        }
    }

    private fun stopLocationFallback() {
        if (!isUsingLocationFallback) return

        fusedLocationClient.removeLocationUpdates(fallbackLocationListener)
        isUsingLocationFallback = false
        previousLocation = null
    }

    private fun updateSpeedFromVehicleHardware(newSpeedMps: Float) {
        val nowMs = System.currentTimeMillis()
        val previousTimestamp = lastSpeedIntegrationElapsedMs
        if (previousTimestamp != null) {
            val elapsedSeconds = (nowMs - previousTimestamp).coerceAtLeast(0L) / 1000.0
            distanceMeters += abs(newSpeedMps) * elapsedSeconds
        }

        lastSpeedIntegrationElapsedMs = nowMs
        speedMps = newSpeedMps
    }

    private fun estimateSpeedFromLocations(previous: Location, current: Location): Float? {
        val elapsedMs = current.time - previous.time
        if (elapsedMs <= 0L) return null

        val metersPerSecond = previous.distanceTo(current) / (elapsedMs / 1000f)
        return if (metersPerSecond.isFinite()) metersPerSecond else null
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            carContext,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun CarValue<Float>.successValue(): Float? {
        return if (status == CarValue.STATUS_SUCCESS) value else null
    }

    private fun formatSpeed(valueMps: Float?): String {
        if (valueMps == null) return "Waiting for signal"

        val mph = valueMps * MPS_TO_MPH
        return String.format(Locale.US, "%.0f mph", mph)
    }

    private fun formatDistance(valueMeters: Double): String {
        val miles = valueMeters * METERS_TO_MILES
        return String.format(Locale.US, "%.2f mi", miles)
    }

    companion object {
        private const val TAG = "LiveTelemetryScreen"
        private const val LOCATION_INTERVAL_MS = 1_000L
        private const val MPS_TO_MPH = 2.2369363f
        private const val METERS_TO_MILES = 0.000621371
    }
}

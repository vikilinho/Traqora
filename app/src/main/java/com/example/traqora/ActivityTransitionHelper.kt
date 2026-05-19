package com.example.traqora

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.DetectedActivity

object ActivityTransitionHelper {
    private const val TAG = "ActivityTransitionHelper"
    private const val REQUEST_CODE = 4242

    @SuppressLint("MissingPermission")
    fun registerForActivityTransitions(context: Context) {
        if (!hasActivityRecognitionPermission(context)) {
            Log.w(TAG, "Cannot register: Activity Recognition permission missing")
            return
        }

        val transitions = listOf(
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build(),
            ActivityTransition.Builder()
                .setActivityType(DetectedActivity.IN_VEHICLE)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_EXIT)
                .build()
        )

        val request = ActivityTransitionRequest(transitions)
        val pendingIntent = getPendingIntent(context)

        ActivityRecognition.getClient(context)
            .requestActivityTransitionUpdates(request, pendingIntent)
            .addOnSuccessListener {
                Log.d(TAG, "Successfully registered for activity transitions")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to register for activity transitions", e)
            }
    }

    private fun getPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, ActivityTransitionReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private fun hasActivityRecognitionPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

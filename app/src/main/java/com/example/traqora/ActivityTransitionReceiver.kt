package com.example.traqora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.ActivityTransition

class ActivityTransitionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive action: ${intent.action}")

        if (ActivityTransitionResult.hasResult(intent)) {
            val result = ActivityTransitionResult.extractResult(intent) ?: return
            for (event in result.transitionEvents) {
                Log.d(TAG, "Activity Transition: type=${event.activityType}, transition=${event.transitionType}")
                if (event.activityType == DetectedActivity.IN_VEHICLE) {
                    if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) {
                        Log.d(TAG, "Entered vehicle - starting/resuming trip")
                        sendIntentToService(context, TripTrackerService.ACTION_START)
                    } else if (event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_EXIT) {
                        Log.d(TAG, "Exited vehicle - triggering delayed auto-stop")
                        sendIntentToService(context, TripTrackerService.ACTION_VEHICLE_EXIT)
                    }
                }
            }
        }
    }

    // Visible for testing
    var createServiceIntent: (Context, String) -> Intent = { ctx, act ->
        Intent(ctx, TripTrackerService::class.java).apply {
            action = act
        }
    }

    private fun sendIntentToService(context: Context, actionStr: String) {
        val serviceIntent = createServiceIntent(context, actionStr)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service with action $actionStr", e)
        }
    }

    companion object {
        private const val TAG = "ActivityTransitionRx"
    }
}

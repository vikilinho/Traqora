package com.example.traqora

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionEvent
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import io.mockk.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ActivityTransitionReceiverTest {

    private val context = mockk<Context>(relaxed = true)
    private val intent = mockk<Intent>(relaxed = true)
    private val receiver = ActivityTransitionReceiver()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<Throwable>()) } returns 0

        mockkStatic(ActivityTransitionResult::class)

        val mockIntent = mockk<Intent>(relaxed = true)
        receiver.createServiceIntent = { _, action ->
            every { mockIntent.action } returns action
            mockIntent
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(ActivityTransitionResult::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun `onReceive starts service on vehicle enter`() {
        // Arrange
        val event = mockk<ActivityTransitionEvent>()
        every { event.activityType } returns DetectedActivity.IN_VEHICLE
        every { event.transitionType } returns ActivityTransition.ACTIVITY_TRANSITION_ENTER

        val result = mockk<ActivityTransitionResult>()
        every { result.transitionEvents } returns listOf(event)

        every { ActivityTransitionResult.hasResult(intent) } returns true
        every { ActivityTransitionResult.extractResult(intent) } returns result

        // Act
        receiver.onReceive(context, intent)

        // Assert
        val intentSlot = slot<Intent>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            verify(exactly = 1) { context.startForegroundService(capture(intentSlot)) }
        } else {
            verify(exactly = 1) { context.startService(capture(intentSlot)) }
        }
        assertEquals(TripTrackerService.ACTION_START, intentSlot.captured.action)
    }

    @Test
    fun `onReceive stops service on vehicle exit`() {
        // Arrange
        val event = mockk<ActivityTransitionEvent>()
        every { event.activityType } returns DetectedActivity.IN_VEHICLE
        every { event.transitionType } returns ActivityTransition.ACTIVITY_TRANSITION_EXIT

        val result = mockk<ActivityTransitionResult>()
        every { result.transitionEvents } returns listOf(event)

        every { ActivityTransitionResult.hasResult(intent) } returns true
        every { ActivityTransitionResult.extractResult(intent) } returns result

        // Act
        receiver.onReceive(context, intent)

        // Assert
        val intentSlot = slot<Intent>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            verify(exactly = 1) { context.startForegroundService(capture(intentSlot)) }
        } else {
            verify(exactly = 1) { context.startService(capture(intentSlot)) }
        }
        assertEquals(TripTrackerService.ACTION_VEHICLE_EXIT, intentSlot.captured.action)
    }
}

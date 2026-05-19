package com.example.traqora

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.traqora.data.TripSummary
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.graphics.graphicsLayer

class MainActivity : ComponentActivity() {

    private val viewModel: TripDashboardViewModel by viewModels {
        TripDashboardViewModel.Factory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TraqoraTheme {
                TraqoraDashboard(viewModel)
            }
        }
    }
}

private sealed interface ScreenState {
    object Home : ScreenState
    object History : ScreenState
    data class Detail(val summary: TripSummary) : ScreenState
}

@Composable
private fun TraqoraDashboard(viewModel: TripDashboardViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val liveTripState by TripLiveState.state.collectAsState()
    val isTracking = liveTripState.isTracking
    var selectedTab by remember { mutableIntStateOf(0) }
    var selectedTripSummary by remember { mutableStateOf<TripSummary?>(null) }
    var isStartingTrip by remember { mutableStateOf(false) }
    var isStoppingTrip by remember { mutableStateOf(false) }
    var hasRequiredPermissions by remember { mutableStateOf(context.hasTripTrackingPermissions()) }

    val currentScreenState = remember(selectedTab, selectedTripSummary) {
        when {
            selectedTab == 0 -> ScreenState.Home
            selectedTripSummary != null -> ScreenState.Detail(selectedTripSummary!!)
            else -> ScreenState.History
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasRequiredPermissions = context.hasTripTrackingPermissions()
    }

    LaunchedEffect(Unit) {
        if (!hasRequiredPermissions) {
            permissionLauncher.launch(tripTrackingPermissions())
        }
    }

    LaunchedEffect(hasRequiredPermissions) {
        if (hasRequiredPermissions) {
            ActivityTransitionHelper.registerForActivityTransitions(context)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBar(
                onBack = if (selectedTab == 1 && selectedTripSummary != null) {
                    { selectedTripSummary = null }
                } else {
                    null
                }
            )
        },
        bottomBar = {
            TraqoraBottomTabs(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
        }
    ) { innerPadding ->
        LaunchedEffect(selectedTab) {
            if (selectedTab == 1) {
                viewModel.refreshLatestTrip()
            }
        }

        LaunchedEffect(isTracking) {
            if (isTracking) {
                isStartingTrip = false
            } else if (isStoppingTrip) {
                delay(TRIP_STOP_REFRESH_DELAY_MS)
                viewModel.refreshLatestTrip()
                isStoppingTrip = false
            }
        }

        LaunchedEffect(uiState.tripSummaries, selectedTripSummary?.tripId) {
            val selectedId = selectedTripSummary?.tripId ?: return@LaunchedEffect
            selectedTripSummary = uiState.tripSummaries.firstOrNull { it.tripId == selectedId }
        }

        AnimatedContent(
            targetState = currentScreenState,
            transitionSpec = {
                val initial = initialState
                val target = targetState
                when {
                    initial is ScreenState.Home && target is ScreenState.History -> {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(400)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Left,
                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(400))
                    }
                    initial is ScreenState.History && target is ScreenState.Home -> {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(400)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Right,
                            animationSpec = tween(400, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(400))
                    }
                    initial is ScreenState.History && target is ScreenState.Detail -> {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(450, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(450)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Up,
                            animationSpec = tween(450, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(450))
                    }
                    initial is ScreenState.Detail && target is ScreenState.History -> {
                        slideIntoContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(450, easing = FastOutSlowInEasing)
                        ) + fadeIn(animationSpec = tween(450)) togetherWith
                        slideOutOfContainer(
                            towards = AnimatedContentTransitionScope.SlideDirection.Down,
                            animationSpec = tween(450, easing = FastOutSlowInEasing)
                        ) + fadeOut(animationSpec = tween(450))
                    }
                    else -> {
                        fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
                    }
                }
            },
            label = "ScreenTransition",
            modifier = Modifier.fillMaxSize()
        ) { state ->
            val scrollState = rememberScrollState()
            TrackScrollHaptics(scrollState)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(innerPadding)
                    .padding(horizontal = 18.dp)
                    .padding(top = 82.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                when (state) {
                    is ScreenState.Home -> {
                        HomeTab(
                            isTracking = isTracking,
                            isStartingTrip = isStartingTrip,
                            isStoppingTrip = isStoppingTrip,
                            liveTripState = liveTripState,
                            hasRequiredPermissions = hasRequiredPermissions,
                            onStart = {
                                isStartingTrip = true
                                context.startTripTrackerService()
                            },
                            onStop = {
                                isStoppingTrip = true
                                context.stopTripTrackerService()
                            }
                        )
                    }
                    is ScreenState.History -> {
                        TripHistoryTab(
                            tripSummaries = uiState.tripSummaries,
                            onOpenSummary = { selectedTripSummary = it },
                            onShare = { summary ->
                                context.shareTripCard(summary)
                            },
                            onDelete = { summary ->
                                if (selectedTripSummary?.tripId == summary.tripId) {
                                    selectedTripSummary = null
                                }
                                viewModel.deleteTrip(summary.tripId)
                            }
                        )
                    }
                    is ScreenState.Detail -> {
                        TripSummaryDetailScreen(
                            summary = state.summary,
                            onShare = { context.shareTripCard(state.summary) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppBar(onBack: (() -> Unit)? = null) {
    val hapticFeedback = LocalHapticFeedback.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        if (onBack != null) {
            IconButton(
                onClick = hapticAction(hapticFeedback, onBack),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(48.dp)
            ) {
                BackArrowIcon()
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                        append("Tra")
                    }
                    withStyle(SpanStyle(color = Color(0xFF6D4C9F))) {
                        append("qo")
                    }
                    withStyle(SpanStyle(color = Color(0xFFC2413B))) {
                        append("ra")
                    }
                },
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Driving score",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BackArrowIcon() {
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.size(24.dp)) {
        val strokeWidth = 2.75.dp.toPx()
        val centerY = size.height / 2f
        drawLine(
            color = color,
            start = Offset(size.width * 0.25f, centerY),
            end = Offset(size.width * 0.78f, centerY),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.25f, centerY),
            end = Offset(size.width * 0.48f, size.height * 0.27f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.25f, centerY),
            end = Offset(size.width * 0.48f, size.height * 0.73f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun TraqoraBottomTabs(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val hapticFeedback = LocalHapticFeedback.current

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        val itemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onBackground,
            indicatorColor = Color(0xFFD7F0EF),
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        )

        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onTabSelected(0)
            },
            label = { BottomTabLabel("Home", selected = selectedTab == 0) },
            icon = {},
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                onTabSelected(1)
            },
            label = { BottomTabLabel("Trips history", selected = selectedTab == 1) },
            icon = {},
            colors = itemColors
        )
    }
}

@Composable
private fun BottomTabLabel(text: String, selected: Boolean) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
    )
}

@Composable
private fun HomeTab(
    isTracking: Boolean,
    isStartingTrip: Boolean,
    isStoppingTrip: Boolean,
    liveTripState: LiveTripState,
    hasRequiredPermissions: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        ScoreSection(
            isTracking = isTracking,
            liveTripState = liveTripState
        )
        CircularMetrics(
            isTracking = isTracking,
            liveTripState = liveTripState
        )
        Spacer(modifier = Modifier.height(18.dp))
        TrackingControls(
            isTracking = isTracking,
            isStartingTrip = isStartingTrip,
            isStoppingTrip = isStoppingTrip,
            canStart = hasRequiredPermissions,
            onStart = onStart,
            onStop = onStop
        )
    }
}

@Composable
private fun ScoreSection(
    isTracking: Boolean,
    liveTripState: LiveTripState
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScoreRing(
            score = if (isTracking) liveTripState.score else null,
            modifier = Modifier.size(190.dp)
        )
    }
}

@Composable
private fun ScoreRing(score: Int?, modifier: Modifier = Modifier) {
    val progressColor = MaterialTheme.colorScheme.primary
    val trackColor = Color(0xFFD8E0E2)
    val targetProgress = ((score ?: 0).coerceIn(0, 100) / 100f)
    val progress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "ScoreProgress"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = Size(size.width - stroke.width, size.height - stroke.width)

            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke
            )
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = progress * 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = stroke
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val displayScore = if (score != null) (progress * 100).toInt() else null
            Text(
                text = displayScore?.toString() ?: "--",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "score",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CircularMetrics(
    isTracking: Boolean,
    liveTripState: LiveTripState
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val metricSize = ((maxWidth - 20.dp) / 3).coerceAtMost(118.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            CircularMetric(
                label = "Speed",
                value = if (isTracking) formatMph(liveTripState.speedMps) else "-- mph",
                isPulseEnabled = isTracking,
                modifier = Modifier.size(metricSize)
            )
            CircularMetric(
                label = "Distance",
                valueColor = Color(0xFF6D4C9F),
                value = if (isTracking) formatMiles(liveTripState.distanceMeters) else "-- mi",
                isPulseEnabled = isTracking,
                modifier = Modifier.size(metricSize)
            )
            CircularMetric(
                label = "Harsh events",
                valueColor = Color(0xFFC2413B),
                value = if (isTracking) liveTripState.harshEventCount.toString() else "--",
                isPulseEnabled = isTracking,
                modifier = Modifier.size(metricSize)
            )
        }
    }
}

@Composable
private fun CircularMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    isPulseEnabled: Boolean = false
) {
    val scale = if (isPulseEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val animatedScale by infiniteTransition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.03f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scalePulse"
        )
        animatedScale
    } else {
        1f
    }

    Card(
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale),
        shape = CircleShape,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = valueColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TrackingControls(
    isTracking: Boolean,
    isStartingTrip: Boolean,
    isStoppingTrip: Boolean,
    canStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    val isBusy = isStartingTrip || isStoppingTrip
    val buttonText = when {
        isStoppingTrip -> "Ending..."
        isTracking -> "End trip"
        isStartingTrip -> "Starting..."
        else -> "Start trip"
    }
    val buttonEnabled = if (isTracking) {
        !isStoppingTrip
    } else {
        canStart && !isBusy
    }
    val buttonAction = if (isTracking) onStop else onStart

    val targetButtonColor = when {
        !buttonEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        isTracking -> Color(0xFFC2413B)
        else -> MaterialTheme.colorScheme.primary
    }
    val buttonColor by animateColorAsState(
        targetValue = targetButtonColor,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "ButtonColor"
    )

    Button(
        onClick = hapticAction(hapticFeedback, buttonAction),
        enabled = buttonEnabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = buttonColor,
            disabledContainerColor = buttonColor
        )
    ) {
        Text(
            text = buttonText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TripHistoryTab(
    tripSummaries: List<TripSummary>,
    onOpenSummary: (TripSummary) -> Unit,
    onShare: (TripSummary) -> Unit,
    onDelete: (TripSummary) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Trips history",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (tripSummaries.isEmpty()) {
            Text(
                text = "No trips recorded yet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val groupedTrips = remember(tripSummaries) {
                groupTripSummariesByWeekAndDay(tripSummaries)
            }

            groupedTrips.forEach { weekGroup ->
                TripWeekSection(
                    weekGroup = weekGroup,
                    onOpenSummary = onOpenSummary,
                    onShare = onShare,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun TripWeekSection(
    weekGroup: TripWeekGroup,
    onOpenSummary: (TripSummary) -> Unit,
    onShare: (TripSummary) -> Unit,
    onDelete: (TripSummary) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = weekGroup.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.primary
        )

        weekGroup.days.forEach { dayGroup ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = dayGroup.label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                dayGroup.trips.forEach { summary ->
                    TripSummaryCard(
                        summary = summary,
                        onOpenSummary = { onOpenSummary(summary) },
                        onShare = { onShare(summary) },
                        onDelete = { onDelete(summary) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TripSummaryCard(
    summary: TripSummary,
    onOpenSummary: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .hapticClickable(hapticFeedback, onClick = onOpenSummary),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = formatDate(summary.startedAtEpochMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = summary.status.replaceFirstChar { it.titlecase(Locale.US) },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${summary.score}",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            SummaryRow("Distance", formatMiles(summary.distanceMeters))
            SummaryRow("Harsh events", summary.harshEventCount.toString())
            SummaryRow("Average speed", summary.averageSpeedMps?.let(::formatMph) ?: "Not available")
            SummaryRow("Ended", summary.endedAtEpochMs?.let(::formatDate) ?: "In progress")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = hapticAction(hapticFeedback, onShare),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Share trip",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                OutlinedButton(
                    onClick = hapticAction(hapticFeedback, onDelete),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Delete",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF9C2D2D)
                    )
                }
            }
        }
    }
}

@Composable
private fun TripSummaryDetailScreen(
    summary: TripSummary,
    onShare: () -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Trip Summary",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            ScoreRing(score = summary.score, modifier = Modifier.size(170.dp))
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryRow("Status", summary.status.replaceFirstChar { it.titlecase(Locale.US) })
                SummaryRow("Distance", formatMiles(summary.distanceMeters))
                SummaryRow("Harsh events", summary.harshEventCount.toString())
                SummaryRow("Average speed", summary.averageSpeedMps?.let(::formatMph) ?: "Not available")
                SummaryRow("Location samples", summary.locationSampleCount.toString())
                SummaryRow("Started", formatDate(summary.startedAtEpochMs))
                SummaryRow("Ended", summary.endedAtEpochMs?.let(::formatDate) ?: "In progress")
            }
        }

        OutlinedButton(
            onClick = hapticAction(hapticFeedback, onShare),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "Share trip",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TrackScrollHaptics(scrollState: ScrollState) {
    val hapticFeedback = LocalHapticFeedback.current

    LaunchedEffect(scrollState) {
        var isFirstBucket = true
        snapshotFlow { scrollState.value / SCROLL_HAPTIC_STEP_PX }
            .distinctUntilChanged()
            .collect {
                if (isFirstBucket) {
                    isFirstBucket = false
                } else {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
    }
}

private fun hapticAction(
    hapticFeedback: HapticFeedback,
    onClick: () -> Unit
): () -> Unit = {
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    onClick()
}

private fun Modifier.hapticClickable(
    hapticFeedback: HapticFeedback,
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = clickable(enabled = enabled) {
    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
    onClick()
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private val TraqoraTypography = Typography(
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 48.sp,
        lineHeight = 52.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 31.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 23.sp,
        lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 21.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
)

@Composable
private fun TraqoraTheme(content: @Composable () -> Unit) {
    val colorScheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF006D77),
        onPrimary = Color.White,
        secondary = Color(0xFF7D5A50),
        background = Color(0xFFF5F7F8),
        onBackground = Color(0xFF142022),
        surface = Color.White,
        onSurface = Color(0xFF142022),
        onSurfaceVariant = Color(0xFF637074),
        outline = Color(0xFFD8E0E2)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TraqoraTypography,
        content = content
    )
}

private fun Context.startTripTrackerService() {
    val intent = Intent(this, TripTrackerService::class.java).apply {
        action = TripTrackerService.ACTION_START
    }

    ContextCompat.startForegroundService(this, intent)
}

private fun Context.stopTripTrackerService() {
    val intent = Intent(this, TripTrackerService::class.java).apply {
        action = TripTrackerService.ACTION_STOP
    }

    startService(intent)
}

private fun Context.shareTripCard(summary: TripSummary) {
    val imageFile = createTripShareImage(summary)
    val imageUri = FileProvider.getUriForFile(
        this,
        "$packageName.fileprovider",
        imageFile
    )

    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_SUBJECT, "Traqora trip summary")
        putExtra(Intent.EXTRA_TITLE, "Traqora trip summary")
        putExtra(Intent.EXTRA_STREAM, imageUri)
        clipData = ClipData.newUri(contentResolver, "Traqora trip summary", imageUri)
        setDataAndType(imageUri, "image/png")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val receiverActivities = packageManager.queryIntentActivities(
        shareIntent,
        PackageManager.MATCH_DEFAULT_ONLY
    )
    receiverActivities.forEach { resolveInfo ->
        grantUriPermission(
            resolveInfo.activityInfo.packageName,
            imageUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    startActivity(
        Intent.createChooser(shareIntent, "Share trip summary").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}

private fun Context.createTripShareImage(summary: TripSummary): File {
    val width = 1080
    val height = 1350
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(245, 247, 248)
        style = Paint.Style.FILL
    }
    val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }
    val tealPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0, 109, 119)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 24f
    }
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(216, 224, 226)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 24f
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(20, 32, 34)
        textSize = 58f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(99, 112, 116)
        textSize = 34f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(20, 32, 34)
        textSize = 42f
        textAlign = Paint.Align.RIGHT
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(20, 32, 34)
        textSize = 112f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }
    val scoreLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(99, 112, 116)
        textSize = 34f
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
    }

    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)
    canvas.drawRoundRect(RectF(64f, 64f, 1016f, 1286f), 48f, 48f, cardPaint)
    canvas.drawText("Traqora", 108f, 158f, titlePaint)
    canvas.drawText("Driving score trip summary", 108f, 212f, labelPaint)

    val ringRect = RectF(342f, 300f, 738f, 696f)
    val progress = summary.score.coerceIn(0, 100) / 100f
    canvas.drawArc(ringRect, -90f, 360f, false, trackPaint)
    canvas.drawArc(ringRect, -90f, progress * 360f, false, tealPaint)
    canvas.drawText(summary.score.toString(), 540f, 506f, scorePaint)
    canvas.drawText("score", 540f, 568f, scoreLabelPaint)

    val started = formatDate(summary.startedAtEpochMs)
    val ended = summary.endedAtEpochMs?.let(::formatDate) ?: "In progress"
    drawShareMetric(canvas, labelPaint, valuePaint, "Started", started, 800f)
    drawShareMetric(canvas, labelPaint, valuePaint, "Distance", formatMiles(summary.distanceMeters), 898f)
    drawShareMetric(canvas, labelPaint, valuePaint, "Average speed", summary.averageSpeedMps?.let(::formatMph) ?: "Not available", 996f)
    drawShareMetric(canvas, labelPaint, valuePaint, "Harsh events", summary.harshEventCount.toString(), 1094f)
    drawShareMetric(canvas, labelPaint, valuePaint, "Ended", ended, 1192f)

    val imageDir = File(cacheDir, "shared_trip_images").apply { mkdirs() }
    val imageFile = File(imageDir, "traqora-trip-${summary.tripId}.png")
    FileOutputStream(imageFile).use { output ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
    }
    bitmap.recycle()

    return imageFile
}

private fun drawShareMetric(
    canvas: AndroidCanvas,
    labelPaint: Paint,
    valuePaint: Paint,
    label: String,
    value: String,
    baseline: Float
) {
    canvas.drawText(label, 108f, baseline, labelPaint)
    canvas.drawText(value, 972f, baseline, valuePaint)
}

private const val TRIP_STOP_REFRESH_DELAY_MS = 750L
private const val SCROLL_HAPTIC_STEP_PX = 96

private data class TripWeekGroup(
    val label: String,
    val days: List<TripDayGroup>
)

private data class TripDayGroup(
    val label: String,
    val trips: List<TripSummary>
)

private fun groupTripSummariesByWeekAndDay(tripSummaries: List<TripSummary>): List<TripWeekGroup> {
    val orderedTrips = tripSummaries.sortedByDescending { it.startedAtEpochMs }
    return orderedTrips
        .groupBy { weekStartEpochMs(it.startedAtEpochMs) }
        .toSortedMap(compareByDescending { it })
        .map { (weekStartEpochMs, weekTrips) ->
            TripWeekGroup(
                label = formatWeekLabel(weekStartEpochMs),
                days = weekTrips
                    .groupBy { dayStartEpochMs(it.startedAtEpochMs) }
                    .toSortedMap(compareByDescending { it })
                    .map { (dayStartEpochMs, dayTrips) ->
                        TripDayGroup(
                            label = formatDayGroupLabel(dayStartEpochMs),
                            trips = dayTrips.sortedByDescending { it.startedAtEpochMs }
                        )
                    }
            )
        }
}

private fun weekStartEpochMs(epochMs: Long): Long {
    return calendarAtStartOfDay(epochMs).apply {
        firstDayOfWeek = Calendar.MONDAY
        while (get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            add(Calendar.DAY_OF_MONTH, -1)
        }
    }.timeInMillis
}

private fun dayStartEpochMs(epochMs: Long): Long {
    return calendarAtStartOfDay(epochMs).timeInMillis
}

private fun calendarAtStartOfDay(epochMs: Long): Calendar {
    return Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
}

private fun formatWeekLabel(weekStartEpochMs: Long): String {
    val now = System.currentTimeMillis()
    val currentWeekStart = weekStartEpochMs(now)
    val previousWeekStart = Calendar.getInstance().apply {
        timeInMillis = currentWeekStart
        add(Calendar.DAY_OF_MONTH, -7)
    }.timeInMillis

    return when (weekStartEpochMs) {
        currentWeekStart -> "This week"
        previousWeekStart -> "Last week"
        else -> {
            val start = Date(weekStartEpochMs)
            val endCalendar = Calendar.getInstance().apply {
                timeInMillis = weekStartEpochMs
                add(Calendar.DAY_OF_MONTH, 6)
            }
            val monthDayFormatter = SimpleDateFormat("MMM d", Locale.US)
            val endFormatter = SimpleDateFormat("MMM d, yyyy", Locale.US)
            "${monthDayFormatter.format(start)} - ${endFormatter.format(endCalendar.time)}"
        }
    }
}

private fun formatDayGroupLabel(dayStartEpochMs: Long): String {
    val todayStart = dayStartEpochMs(System.currentTimeMillis())
    val yesterdayStart = Calendar.getInstance().apply {
        timeInMillis = todayStart
        add(Calendar.DAY_OF_MONTH, -1)
    }.timeInMillis

    return when (dayStartEpochMs) {
        todayStart -> "Today"
        yesterdayStart -> "Yesterday"
        else -> SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(dayStartEpochMs))
    }
}

private fun Context.hasTripTrackingPermissions(): Boolean {
    return tripTrackingPermissions().all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun tripTrackingPermissions(): Array<String> {
    return buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

private fun formatMiles(distanceMeters: Double): String {
    val miles = distanceMeters * 0.000621371
    return String.format(Locale.US, "%.2f mi", miles)
}

private fun formatMph(speedMps: Float?): String {
    val mph = speedMps?.times(2.2369363f) ?: return "-- mph"
    return String.format(Locale.US, "%.0f mph", mph)
}

private fun formatDate(epochMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMs))
}

@Preview(showBackground = true)
@Composable
private fun TraqoraDashboardPreview() {
    TraqoraTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AppBar()
                HomeTab(
                    isTracking = false,
                    isStartingTrip = false,
                    isStoppingTrip = false,
                    liveTripState = LiveTripState(),
                    hasRequiredPermissions = true,
                    onStart = {},
                    onStop = {}
                )
            }
        }
    }
}

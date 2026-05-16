package com.example.traqora

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.traqora.data.TraqoraDatabase
import com.example.traqora.data.TripRepository
import com.example.traqora.data.TripSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripDashboardUiState(
    val latestTripSummary: TripSummary? = null,
    val tripSummaries: List<TripSummary> = emptyList(),
    val shareText: String? = null,
    val isLoadingTripSummary: Boolean = false
)

class TripDashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = TripRepository(
        TraqoraDatabase.getInstance(application).tripDao()
    )

    private val mutableUiState = MutableStateFlow(TripDashboardUiState())
    val uiState: StateFlow<TripDashboardUiState> = mutableUiState.asStateFlow()

    init {
        refreshLatestTrip()
    }

    fun refreshLatestTrip() {
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoadingTripSummary = true) }
            val summaries = repository.getRecentTripSummaries()
            val summary = summaries.firstOrNull() ?: repository.getLatestTripSummary()
            mutableUiState.update {
                it.copy(
                    latestTripSummary = summary,
                    tripSummaries = summaries,
                    shareText = summary?.let(repository::buildShareText),
                    isLoadingTripSummary = false
                )
            }
        }
    }

    fun buildShareText(summary: TripSummary): String {
        return repository.buildShareText(summary)
    }

    fun closeStaleActiveTrips() {
        viewModelScope.launch {
            repository.completeActiveTrips()
            refreshLatestTrip()
        }
    }

    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            repository.deleteTrip(tripId)
            refreshLatestTrip()
        }
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(TripDashboardViewModel::class.java)) {
                return TripDashboardViewModel(application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

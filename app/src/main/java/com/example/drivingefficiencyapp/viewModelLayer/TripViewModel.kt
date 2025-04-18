package com.example.drivingefficiencyapp.viewModelLayer

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.drivingefficiencyapp.modelLayer.obd.EfficiencyCalculator
import com.example.drivingefficiencyapp.modelLayer.trip.TripRepository
import com.example.drivingefficiencyapp.modelLayer.trip.Trip
import kotlinx.coroutines.launch

class TripViewModel : ViewModel() {
    private val tripRepository = TripRepository()
    private val _trips = MutableLiveData<List<Trip>>()
    val trips: LiveData<List<Trip>> = _trips
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading
    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _selectedTrip = MutableLiveData<Trip>()

    fun loadTrips() {
        _isLoading.value = true
        _error.value = null

        viewModelScope.launch {
            tripRepository.getTrips()
                .onSuccess { tripsList ->
                    //process trips to ensure efficiency scores are calculated
                    processTrips(tripsList)
                    val sortedTrips = tripsList.sortedByDescending {
                        it.timestamp?.seconds ?: 0
                    }

                    _trips.value = sortedTrips
                    _isLoading.value = false
                }
                .onFailure { exception ->
                    _error.value = "Failed to load trips: ${exception.message}"
                    _isLoading.value = false
                }
        }
    }

    fun deleteTrip(tripId: String) {
        _isLoading.value = true
        viewModelScope.launch {
            tripRepository.deleteTrip(tripId)
                .onSuccess {
                    loadTrips()
                }
                .onFailure { exception ->
                    _error.value = "Failed to delete trip: ${exception.message}"
                    _isLoading.value = false
                }
        }
    }

    fun selectTrip(trip: Trip) {
        _selectedTrip.value = trip
    }

    private fun processTrips(trips: List<Trip>) {
        trips.forEach { trip ->
            if (trip.efficiencyScore == -1) {
                trip.efficiencyScore = EfficiencyCalculator.calculateEfficiencyScore(trip)
                //update the efficiency score in Firestore
                updateEfficiencyScore(trip.id, trip.efficiencyScore)
            }
        }
    }

    private fun updateEfficiencyScore(tripId: String, score: Int) {
        viewModelScope.launch {
            tripRepository.updateTripEfficiencyScore(tripId, score)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
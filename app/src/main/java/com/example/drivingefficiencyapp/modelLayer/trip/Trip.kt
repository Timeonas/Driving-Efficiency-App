package com.example.drivingefficiencyapp.modelLayer.trip

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.Timestamp

/**
 * Data class representing a trip with a date and duration.
 * Includes a companion object to store all trips in memory.
 *
 * @author Tim Samoska
 * @since January 17, 2025
 */

data class Trip(
    @DocumentId
    val id: String = "",
    val date: String = "",
    val duration: String = "",
    val timestamp: Timestamp? = null,
    val averageSpeed: Float = 0f,
    val distanceTraveled: Float = 0f,
    val averageFuelConsumption: Float = 0f,
    val fuelUsed: Float = 0f,
    val maxRPM: Int = 0,
    val avgRPM: Float = 0f,
    var efficiencyScore: Int = -1
)
package com.example.drivingefficiencyapp.modelLayer.trip

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.Timestamp
import java.util.Date
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class TripRepository {
    private val db = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    suspend fun saveTrip(
        date: String,
        duration: String,
        tripSummary: TripSummary
    ): Result<Unit> = suspendCoroutine { continuation ->
        val userId = auth.currentUser?.uid

        if (userId == null) {
            continuation.resumeWithException(Exception("User not authenticated"))
            return@suspendCoroutine
        }

        val tripData = hashMapOf(
            "date" to date,
            "duration" to duration,
            "timestamp" to Timestamp(Date()),
            "averageSpeed" to tripSummary.averageSpeed,
            "distanceTraveled" to tripSummary.distanceTraveled,
            "averageFuelConsumption" to tripSummary.averageFuelConsumption,
            "fuelUsed" to tripSummary.fuelUsed,
            "tripDuration" to tripSummary.tripDuration,
            "maxRPM" to tripSummary.maxRPM,
            "avgRPM" to tripSummary.avgRPM,
            "efficiencyScore" to tripSummary.efficiencyScore
        )

        db.collection("users")
            .document(userId)
            .collection("trips")
            .add(tripData)
            .addOnSuccessListener {
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e ->
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun getTrips(): Result<List<Trip>> = suspendCoroutine { continuation ->
        val userId = auth.currentUser?.uid

        if (userId == null) {
            continuation.resumeWithException(Exception("User not authenticated"))
            return@suspendCoroutine
        }

        db.collection("users")
            .document(userId)
            .collection("trips")
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                val trips = result.documents.mapNotNull { document ->
                    val data = document.data ?: return@mapNotNull null

                    Trip(
                        id = document.id,
                        date = data["date"] as? String ?: "",
                        duration = data["duration"] as? String ?: "",
                        timestamp = (data["timestamp"] as? Timestamp) ?: Timestamp.now(),
                        averageSpeed = (data["averageSpeed"] as? Number)?.toFloat() ?: 0f,
                        distanceTraveled = (data["distanceTraveled"] as? Number)?.toFloat() ?: 0f,
                        averageFuelConsumption = (data["averageFuelConsumption"] as? Number)?.toFloat() ?: 0f,
                        fuelUsed = (data["fuelUsed"] as? Number)?.toFloat() ?: 0f,
                        maxRPM = (data["maxRPM"] as? Number)?.toInt() ?: 0,
                        avgRPM = (data["avgRPM"] as? Number)?.toFloat() ?: 0f,
                        efficiencyScore = (data["efficiencyScore"] as? Number)?.toInt() ?: -1
                    )
                }
                continuation.resume(Result.success(trips))
            }
            .addOnFailureListener { e ->
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun deleteTrip(tripId: String): Result<Unit> = suspendCoroutine { continuation ->
        val userId = auth.currentUser?.uid

        if (userId == null) {
            continuation.resumeWithException(Exception("User not authenticated"))
            return@suspendCoroutine
        }

        db.collection("users")
            .document(userId)
            .collection("trips")
            .document(tripId)
            .delete()
            .addOnSuccessListener {
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e ->
                continuation.resume(Result.failure(e))
            }
    }

    suspend fun updateTripEfficiencyScore(tripId: String, score: Int): Result<Unit> = suspendCoroutine { continuation ->
        val userId = auth.currentUser?.uid

        if (userId == null) {
            continuation.resumeWithException(Exception("User not authenticated"))
            return@suspendCoroutine
        }

        db.collection("users")
            .document(userId)
            .collection("trips")
            .document(tripId)
            .update("efficiencyScore", score)
            .addOnSuccessListener {
                continuation.resume(Result.success(Unit))
            }
            .addOnFailureListener { e ->
                continuation.resume(Result.failure(e))
            }
    }
}
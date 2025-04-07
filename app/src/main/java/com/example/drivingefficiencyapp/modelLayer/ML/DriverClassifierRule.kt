package com.example.drivingefficiencyapp.modelLayer.ML
import android.util.Log
import com.example.drivingefficiencyapp.modelLayer.trip.Trip

class DriverClassifierRule {
    companion object {
        private const val TAG = "ML"
    }

    fun classifyDriver(trips: List<Trip>): DriverCategory {
        Log.d(TAG, "Rule-based classification starting on ${trips.size} trips")

        if (trips.size < 3) {
            return DriverCategory.BALANCED
        }

        val avgFuelConsumption = trips.map { it.averageFuelConsumption }.average()
        val avgRPM = trips.map { it.avgRPM }.average()
        val maxRPMAvg = trips.map { it.maxRPM }.average()
        val avgSpeed = trips.map { it.averageSpeed }.average()
        val avgEfficiencyScore = trips.map { it.efficiencyScore }.average()

        //logging the metrics for debugging
        Log.d(
            TAG, "Trip metrics: avgFuel=$avgFuelConsumption, avgRPM=$avgRPM, " +
                "maxRPMAvg=$maxRPMAvg, avgSpeed=$avgSpeed, avgScore=$avgEfficiencyScore")

        //Rulebased classification logic
        val category = when {
            avgEfficiencyScore >= 85 && avgFuelConsumption < 5.5 && avgRPM < 2000 &&
                    avgSpeed > 45 && avgSpeed < 80 -> DriverCategory.ECO_FRIENDLY

            avgEfficiencyScore >= 70 && avgFuelConsumption < 6.5 && avgRPM < 2300 &&
                    avgSpeed > 40 && avgSpeed < 85 -> DriverCategory.ECO_FRIENDLY

            avgEfficiencyScore >= 55 && avgFuelConsumption < 8.0 && avgRPM < 2600 -> DriverCategory.BALANCED

            avgEfficiencyScore < 55 || (avgFuelConsumption > 8.0 && maxRPMAvg > 4500) ||
                    avgSpeed > 90 -> DriverCategory.AGGRESSIVE

            else -> DriverCategory.MODERATE
        }

        Log.d(TAG, "Rule-based classification result: ${category.label}")
        return category
    }

    //personalised feedback based on category and trip data
    fun getPersonalisedFeedback(category: DriverCategory, trip: Trip): String {
        Log.d(TAG, "Generating personalized feedback for category: ${category.label}")

        return when(category) {
            DriverCategory.ECO_FRIENDLY -> {
                "Excellent driving efficiency! You're maximising fuel economy with gentle acceleration and optimal RPM range"
            }

            DriverCategory.BALANCED -> {
                val feedback = if (trip.maxRPM > 3000) {
                    "Your driving is reasonably efficient but try to avoid high RPM peaks to improve fuel economy."
                } else if (trip.averageFuelConsumption > 7.0) {
                    "Consider gentler acceleration and maintaining more consistent speeds to reduce fuel consumption"
                } else {
                    "You have balanced driving habits. Small improvements in acceleration could boost efficiency."
                }
                feedback
            }

            DriverCategory.MODERATE -> {
                "Your driving patterns vary considerably. Focus on maintaining steady speeds and consistent acceleration."
            }

            DriverCategory.AGGRESSIVE -> {
                val feedback = if (trip.maxRPM > 4500) {
                    "High RPM driving is significantly increasing your fuel consumption. Try shifting earlier."
                } else {
                    "Your driving style tends to be aggressive. Smoother acceleration and deceleration would improve efficiency"
                }
                feedback
            }
        }
    }
}

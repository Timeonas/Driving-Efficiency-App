package com.example.drivingefficiencyapp.modelLayer.obd

class GearCalculator(
    wheelDiameter: Double = 0.6096,
    private val finalDriveRatio: Double = 3.611,  //For all gears
    private val idleRpmUpperThreshold: Int = 1000,
    private val speedThreshold: Int = 3,
    private val rpmChangeThreshold: Int = 200,
    private val gearRatios: Map<Int, Double> = mapOf(
        1 to 3.727,
        2 to 2.048,
        3 to 1.258,
        4 to 0.919,
        5 to 0.738,
        6 to 0.622
    )
) {
    private val wheelCircumference = Math.PI * wheelDiameter
    private val secondsPerMinute = 60.0
    private val kmhConversionFactor = 3.6
    private var lastRpm = 0
    private var lastSpeed = 0.0
    private var neutralCounter = 0

    fun calculateGear(rpm: Int, speedKmh: Double): String {
        //check if engine is off
        if (rpm <= 0) return "-"

        //check for neutral
        if (isNeutral(rpm, speedKmh)) {
            return "N"
        } else {
            neutralCounter = 0
        }

        //If speed is 0 but engine is running, in neutral
        if (speedKmh <= 0.0) return "N"

        //calculate theoretical speed for each gear
        val gearSpeeds = gearRatios.mapValues { (_, ratio) ->
            (rpm * wheelCircumference * kmhConversionFactor) / (ratio * finalDriveRatio * secondsPerMinute)
        }

        //find the gear with the closest calculated speed to actual speed
        val gear = gearSpeeds.entries.minByOrNull { (_, calculatedSpeed) ->
            Math.abs(calculatedSpeed - speedKmh)
        }?.key

        lastRpm = rpm
        lastSpeed = speedKmh

        return gear?.toString() ?: "N" //return "N" if no gear is found
    }
    private fun isNeutral(rpm: Int, speedKmh: Double): Boolean {
        if (rpm in 600..idleRpmUpperThreshold && speedKmh < speedThreshold) {
            neutralCounter++
            return neutralCounter >= 2
        }

        val rpmDelta = Math.abs(rpm - lastRpm)
        val speedDelta = Math.abs(speedKmh - lastSpeed)
        //clutch-in scenario. Keep returning neutral
        if (rpmDelta > rpmChangeThreshold && speedDelta < speedThreshold && speedKmh < speedThreshold) {
            neutralCounter++
            return true
        }

        return false
    }
}
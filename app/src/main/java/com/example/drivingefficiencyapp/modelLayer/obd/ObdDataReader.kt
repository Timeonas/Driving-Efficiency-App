package com.example.drivingefficiencyapp.modelLayer.obd

import android.util.Log
import com.example.drivingefficiencyapp.modelLayer.trip.TripData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class ObdDataReader(
    private val scope: CoroutineScope,
    private val outputStream: OutputStream,
    private val inputStream: InputStream,
    private val gearCalculator: GearCalculator,
) {

    private var readingJob: Job? = null
    private var currentRpm: Int = 0
    private var currentSpeed: Double = 0.0
    private var currentFuelRate: Double = 0.0
    private var currentGear: String = "-"

    private val standardAfrDiesel = 25.0
    private val fuelDensityDiesel = 840.0
    // --- Data Storage and Initialisation ---
    private var totalDistance: Double = 0.0
    private var totalFuelUsed: Double = 0.0
    private var lastTimestamp: Long = 0
    private var tripStartTime: Long = 0
    private var firstIteration = true
    
    //RPM stats
    private var rpmReadings = mutableListOf<Int>()
    private var maxRPM = 0

    private var hasFuelRatePid: Boolean? = null

    data class ObdData(
        val rpm: String = "- RPM",
        val speed: String = "- km/h",
        val gear: String = "-",
        val temperature: String = "- °C",
        val instantFuelRate: String = "- L/h",
        val averageFuelConsumption: String = "- L/100km",
        val averageSpeed: String = "- km/h",
        val distanceTraveled: String = "- km",
        val fuelUsed: String = "- L",
        val maf: String = "- g/s",
    )

    private val _obdData = MutableStateFlow(ObdData())
    val obdData: StateFlow<ObdData> = _obdData

    private suspend fun tryGetDirectFuelFlow(): Pair<Double?, String> {
        if (hasFuelRatePid == false) return Pair(null, "Calculated (MAF)")

        try {
            val fuelRateStr = sendAndParseCommand("015E")
            val fuelRateValue = parseNumericValue(fuelRateStr)

            if (fuelRateValue > 0) {
                hasFuelRatePid = true
                return Pair(fuelRateValue, "Direct (PID 015E)")
            } else {
                if (fuelRateStr.contains("No Data") || fuelRateStr.contains("Error")) {
                    hasFuelRatePid = false
                }
            }
        } catch (e: Exception) {
            Log.e("ObdDataReader", "Error getting fuel rate: ${e.message}")
            hasFuelRatePid = false
        }

        return Pair(null, "Calculated (MAF)")
    }

    suspend fun startContinuousReading() {
        readingJob = scope.launch(Dispatchers.IO) {
            tripStartTime = System.currentTimeMillis()
            lastTimestamp = tripStartTime
            delay(100)
            while (isActive) {
                try {
                    val rpmStr = sendAndParseCommand("010C")
                    val speedStr = sendAndParseCommand("010D")
                    val tempStr = sendAndParseCommand("0105")
                    val mafStr = sendAndParseCommand("0110")
                    val rpmValue = parseNumericValue(rpmStr).toInt()
                    val speedValue = parseNumericValue(speedStr)
                    val mafValue: Double = parseNumericValue(mafStr)

                    currentRpm = rpmValue          //Update currentRpm
                    currentSpeed = speedValue      //Update currentSpeed

                    onNewRpmValue(rpmValue)

                    if (firstIteration) {
                        firstIteration = false
                        delay(200)
                        lastTimestamp = System.currentTimeMillis()
                        continue
                    }
                    val currentTime = System.currentTimeMillis()
                    val deltaTimeSeconds = (currentTime - lastTimestamp) / 1000.0

                    if (deltaTimeSeconds <= 0.001) {
                        Log.w("ObdDataReader", "Skipping: small/negative deltaTime: $deltaTimeSeconds")
                        continue
                    }

                    val instantDistance = if (currentSpeed > 2.0) {
                        currentSpeed * deltaTimeSeconds / 3600.0
                    } else 0.0

                    val (directFuelRate, fuelRateSource) = tryGetDirectFuelFlow()
                    val instantFuelRate = directFuelRate ?: calculateInstantFuelRate(mafValue)


                    val instantFuelLiters = if (hasFuelRatePid == true) {
                        (instantFuelRate / 3600.0) * deltaTimeSeconds
                    } else {
                        calculateInstantFuel(mafValue, deltaTimeSeconds)
                    }

                    currentFuelRate = instantFuelRate

                    totalDistance += instantDistance
                    totalFuelUsed += instantFuelLiters

                    //Calculate raw average fuel consumption
                    val rawAverageFuelConsumption = if (totalDistance > 0) {
                        (totalFuelUsed / totalDistance) * 100.0
                    } else {
                        0.0
                    }

                    val tripDurationSeconds = (currentTime - tripStartTime) / 1000.0
                    val averageSpeed = if (tripDurationSeconds > 0) {
                        totalDistance / (tripDurationSeconds / 3600.0)
                    } else {
                        0.0
                    }

                    lastTimestamp = currentTime
                    Log.d("FuelCalcDebug", "RPM: $currentRpm, MAF: $mafValue g/s, Speed: $speedValue km/h, Source: $fuelRateSource, Calculated Rate: $instantFuelRate L/h")
                    _obdData.emit(
                        ObdData(
                            rpm = "$currentRpm RPM",
                            speed = String.format("%.1f km/h", currentSpeed),
                            gear = currentGear,
                            temperature = tempStr,
                            instantFuelRate = String.format("%.2f L/h", instantFuelRate),
                            averageFuelConsumption = String.format("%.2f L/100km", rawAverageFuelConsumption),
                            averageSpeed = String.format("%.2f km/h", averageSpeed),
                            distanceTraveled = String.format("%.3f km", totalDistance),
                            fuelUsed = String.format("%.4f L", totalFuelUsed),
                            maf = String.format("%.2f g/s", mafValue),
                        )
                    )
                    delay(100)

                } catch (e: CancellationException) {
                    Log.d("ObdDataReader", "Reading loop cancelled")
                    break
                } catch (e: Exception) {
                    Log.e("ObdDataReader", "Error: ${e.message}", e)
                    _obdData.emit(
                        ObdData( //Consistent error handling
                            rpm = "- RPM (Error)",
                            speed = "- km/h (Error)",
                            gear = "Gear: (Error)",
                            temperature = "- °C (Error)",
                            instantFuelRate = "- L/h (Error)",
                            averageFuelConsumption = "- L/100km (Error)",
                            averageSpeed = "- km/h (Error)",
                            distanceTraveled = "- km (Error)",
                            fuelUsed = "- L (Error)",
                            maf = "- g/s (Error)",
                        )
                    )
                    delay(500)
                }
            }
        }
    }


    private fun calculateInstantFuel(mafGramsPerSecond: Double, deltaTimeSeconds: Double): Double {
        if (currentRpm <= 0 || mafGramsPerSecond <= 0) { return 0.0 }

        //calculate with the same effective AFR
        val effectiveAfr: Double = if (currentRpm < 900) {
            standardAfrDiesel * 1.7
        } else {
            standardAfrDiesel
        }

        //instantaneous fuel consumption rate (l/s)
        val fuelMassFlowRate: Double = mafGramsPerSecond / effectiveAfr
        val fuelVolumeFlowRate: Double = fuelMassFlowRate / fuelDensityDiesel

        //return fuel used during this time interval
        return fuelVolumeFlowRate * deltaTimeSeconds
    }

    private fun calculateInstantFuelRate(mafGramsPerSecond: Double): Double {
        if (currentRpm <= 0 || mafGramsPerSecond <= 0) { return 0.0 }

        // higher AFR ratio during idle conditions
        val effectiveAfr = if (currentRpm < 900) {
            standardAfrDiesel * 1.7  // AFR at idle
        } else {
            standardAfrDiesel
        }

        //calculation using the formula
        val fuelMassFlowRate: Double = mafGramsPerSecond / effectiveAfr  // g/s
        val fuelVolumeFlowRate: Double = fuelMassFlowRate / fuelDensityDiesel  // l/s
        return fuelVolumeFlowRate * 3600.0  // Convert to l/h
    }

    private fun parseNumericValue(valueStr: String): Double {
        val parts = valueStr.split(" ")
        return parts[0].toDoubleOrNull() ?: 0.0
    }

    private suspend fun sendAndParseCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            val buffer = ByteArray(1024)
            try {
                Log.d("SendCommand", "Sending: $command")
                val response = withTimeoutOrNull(2000) {
                    outputStream.write((command + "\r").toByteArray())
                    delay(50)
                    val bytesRead = withTimeoutOrNull(1000) { inputStream.read(buffer) } ?: 0
                    if (bytesRead > 0) String(buffer, 0, bytesRead) else ""
                } ?: ""
                parseObdResponse(command, response)
            } catch (e: IOException) {
                Log.e("ObdDataReader", "IO Error: ${e.message}")
                ""
            }
        }
    }
    private fun parseObdResponse(command: String, response: String): String {
        try {
            val cleanResponse = response.replace(">", "").trim()
            Log.d("OBD_PARSER", "Raw: $command: $cleanResponse")

            if (cleanResponse.isEmpty() || cleanResponse.uppercase() == "NO DATA") {
                return "- (No Data)"
            }
            if (cleanResponse.contains("ERROR") || cleanResponse.contains("CAN ERROR") || cleanResponse.startsWith("?")) {
                return "- (Error)"}
            if (cleanResponse.contains("SEARCHING")) { return "- (Searching...)" }
            if (cleanResponse.contains("BUS INIT")) { return "- (BUS INIT Error)" }
            val pid = command.substring(2)
            val pidIndex = cleanResponse.indexOf(pid, ignoreCase = true)
            if (pidIndex == -1) { return "- (Unexpected Response)" }

            var dataBytes = cleanResponse.substring(pidIndex + pid.length).trim()
            dataBytes = dataBytes.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }
            Log.d("OBD_PARSER", "Cleaned: $command: $dataBytes")

            return when (command) {
                "010C" -> { //RPM
                    if (dataBytes.length >= 4) {
                        val a = Integer.parseInt(dataBytes.substring(0, 2), 16)
                        val b = Integer.parseInt(dataBytes.substring(2, 4), 16)
                        val rpm = ((256 * a) + b) / 4
                        "$rpm"
                    } else { "- (Invalid Data - Short)" }
                }
                "010D" -> { //speed
                    if (dataBytes.length >= 2) {
                        val speed = Integer.parseInt(dataBytes.substring(0, 2), 16).toDouble()
                        //calculate gear the driver is in
                        currentGear = gearCalculator.calculateGear(currentRpm, speed)
                        return "$speed"
                    } else {
                        "- (Invalid Data - Short)"
                    }
                }
                "0105" -> { //Coolant Temp
                    if (dataBytes.length >= 2) {
                        val temp = Integer.parseInt(dataBytes.substring(0, 2), 16) - 40
                        "$temp°C"  // Return only the numeric temperature
                    } else { "- (Invalid Data - Short)" }
                }
                "0110" -> { //MAF
                    if (dataBytes.length >= 4) {
                        val a = Integer.parseInt(dataBytes.substring(0, 2), 16)
                        val b = Integer.parseInt(dataBytes.substring(2, 4), 16)
                        val maf = ((256 * a) + b) / 100.0
                        "$maf"  //Return only the numeric MAF value
                    } else { "- (Invalid Data - Short)" }
                }
                else -> "Unknown command: $command"
            }
        } catch (e: Exception) {
            Log.e("OBD_PARSER", "Parse error: ${e.message}, response: $response")
            return "- (Parse Error)"
        }
    }

    fun getTripSummary(): TripData {
        val currentData = obdData.value

        return TripData(
            averageSpeed = parseFloat(currentData.averageSpeed),
            distance = parseFloat(currentData.distanceTraveled),
            duration = System.currentTimeMillis() - tripStartTime,
            fuelUsed = parseFloat(currentData.fuelUsed),
            averageFuelConsumption = parseFloat(currentData.averageFuelConsumption),
            avgRPM = calculateAvgRPM().toFloat(),
            maxRPM = maxRPM
        )
    }

    private fun parseFloat(value: String): Float {
        return value.replace("km/h", "")
            .replace("RPM", "")
            .replace("L/100km", "")
            .replace("L", "")
            .replace("km", "")
            .replace("Gear:", "")
            .trim()
            .toFloatOrNull() ?: 0f
    }

    fun resetTripData() {
        totalDistance = 0.0
        totalFuelUsed = 0.0
        lastTimestamp = System.currentTimeMillis()
        tripStartTime = System.currentTimeMillis()
        rpmReadings.clear()
        maxRPM = 0
    }

    private fun onNewRpmValue(rpm: Int) {
        //RPM reading
        rpmReadings.add(rpm)

        // max RPM if higher
        if (rpm > maxRPM) {
            maxRPM = rpm
        }
    }

    // Calculate average RPM
    private fun calculateAvgRPM(): Double {
        if (rpmReadings.isEmpty()) return 0.0
        return rpmReadings.average()
    }
}
package com.example.drivingefficiencyapp.viewLayer.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.drivingefficiencyapp.modelLayer.obd.EfficiencyCalculator
import com.example.drivingefficiencyapp.modelLayer.LocationService
import com.example.drivingefficiencyapp.R
import com.example.drivingefficiencyapp.databinding.StartDriveActivityBinding
import com.example.drivingefficiencyapp.modelLayer.obd.ObdConnectionManager
import com.example.drivingefficiencyapp.modelLayer.obd.ObdDataReader
import com.example.drivingefficiencyapp.modelLayer.trip.Trip
import com.example.drivingefficiencyapp.modelLayer.trip.TripRepository
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.drivingefficiencyapp.modelLayer.trip.TripSummary
import java.text.SimpleDateFormat
import java.util.*

class StartDriveActivity : AppCompatActivity(), OnMapReadyCallback, SensorEventListener {
    private lateinit var binding: StartDriveActivityBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var sensorManager: SensorManager
    private var googleMap: GoogleMap? = null
    private var pendingLocation: LatLng? = null
    private var currentBearing: Float = 0f
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val tripRepository = TripRepository()
    private var accelerometerValues = FloatArray(3)
    private var magnetometerValues = FloatArray(3)
    private var hasAccelerometerData = false
    private var hasMagnetometerData = false

    //OBD data collection
    private var obdDataCollectionJob: Job? = null
    private var obdDataReader: ObdDataReader? = null
    private var previousLocation: Location? = null
    private var bearingQueue = ArrayDeque<Float>(5)
    private var isFirstLocationUpdate = true
    private val rotationMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)
    private var sensorBearing = 0f

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 1002
        private const val TAG = "StartDriveActivity"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBasicUI()

        if (!checkGooglePlayServices()) return

        initializeComponents()
        checkAndRequestPermissions()
        //OBD connection
        initializeObdConnection()
    }

    private fun setupBasicUI() {
        supportActionBar?.hide()
        binding = StartDriveActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
    private fun initializeComponents() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setupLocationCallback()
        setupMapFragment()
        setupDateAndTimer()
        setupEndDriveButton()
    }

    private fun setupMapFragment() {
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.mapView) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    private fun setupLocationCallback() {
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                val location = locationResult.lastLocation ?: return

                if (isFirstLocationUpdate) {
                    //just center the map
                    isFirstLocationUpdate = false
                    previousLocation = location
                    updateMapLocation(LatLng(location.latitude, location.longitude), false)
                    return
                }

                //bearing from location changes over time
                val bearing = calculateBearingFromLocations(previousLocation, location)

                if (bearing != null && location.speed > 1.0) {
                    //bearing to queue for smoothing
                    bearingQueue.add(bearing)
                    if (bearingQueue.size > 5) bearingQueue.removeFirst()
                    //average bearing for smoother transitions
                    currentBearing = bearingQueue.average().toFloat()

                    updateMapLocation(LatLng(location.latitude, location.longitude), true)
                } else {
                    //update position without changing orientation
                    updateMapLocation(LatLng(location.latitude, location.longitude), false)
                }

                previousLocation = location
            }
        }
    }

    private fun calculateBearingFromLocations(start: Location?, end: Location): Float? {
        if (start == null) return null
        val distance = start.distanceTo(end)
        if (distance < 5) return null //Ignore tiny movements

        return start.bearingTo(end)
    }

    private fun updateMapLocation(location: LatLng, updateBearing: Boolean) {
        if (googleMap == null) {
            pendingLocation = location
            return
        }
        val cameraPositionBuilder = CameraPosition.Builder()
            .target(location)
            .zoom(18f)
            .tilt(60f)

        if (updateBearing) {
            cameraPositionBuilder.bearing(currentBearing)
        }

        val cameraPosition = cameraPositionBuilder.build()
        val animationDuration = if (updateBearing) 1000 else 500

        googleMap?.animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition),
            animationDuration,
            null
        )
    }


    private fun setupDateAndTimer() {
        binding.dateTextView.text = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault())
            .format(Date())
        startTime = System.currentTimeMillis()
        isRunning = true
        startTimer()
    }

    private fun startTimer() {
        handler.post(object : Runnable {
            override fun run() {
                if (!isRunning) return
                val elapsedTime = System.currentTimeMillis() - startTime
                val seconds = (elapsedTime / 1000).toInt()
                val minutes = seconds / 60
                val hours = minutes / 60
                binding.timerTextView.text = String.format(
                    Locale.getDefault(),
                    "%02d:%02d:%02d",
                    hours,
                    minutes % 60,
                    seconds % 60
                )

                handler.postDelayed(this, 1000)
            }
        })
    }

    private fun setupEndDriveButton() {
        binding.endDriveButton.setOnClickListener {
            //Get trip summary data and show the dialog
            getTripSummaryData { tripSummary ->
                showTripSummaryDialog(tripSummary)
            }
        }
    }

    //OBD Connection Methods
    private fun initializeObdConnection() {
        if (!ObdConnectionManager.connectionState.value) {
            showToast("OBD not connected. Data display limited.")
            return
        }
        lifecycleScope.launch {
            binding.liveSpeedText.text = "Initialising..."
            ObdConnectionManager.initializeObd { success ->
                if (success) {
                    startObdDataCollection()
                } else {
                    showToast("OBD initialization failed")
                    binding.liveSpeedText.text = "- km/h"
                    binding.liveRpmText.text = "- RPM"
                    binding.liveGearText.text = "- Gear"
                    binding.liveTempText.text = "- °C"
                    binding.liveFuelRateText.text = "- L/h"
                }
            }
        }
    }

    private fun startObdDataCollection() {
        //resetexisting trip data in OBD reader
        ObdConnectionManager.resetTripData()
        //get data reader and start collecting data
        obdDataReader = ObdConnectionManager.startContinuousReading(lifecycleScope)

        obdDataCollectionJob = lifecycleScope.launch {
            obdDataReader?.obdData?.collect { data ->
                binding.liveSpeedText.text = data.speed
                binding.liveRpmText.text = data.rpm
                binding.liveGearText.text = data.gear
                binding.liveTempText.text = data.temperature
                binding.liveFuelRateText.text = data.instantFuelRate
            }
        }
    }

    private fun getTripSummaryData(callback: (TripSummary) -> Unit) {
        // OBD is connectedget trip data from OBD system
        if (ObdConnectionManager.connectionState.value && obdDataReader != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val tripData = ObdConnectionManager.getTripSummary()
                // Create a TripSummary object with the data
                val tripSummary = TripSummary(
                    averageSpeed = tripData.averageSpeed,
                    distanceTraveled = tripData.distance,
                    averageFuelConsumption = tripData.averageFuelConsumption,
                    fuelUsed = tripData.fuelUsed,
                    tripDuration = calculateDuration(),
                    date = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date()),
                    maxRPM = tripData.maxRPM,
                    avgRPM = tripData.avgRPM
                )

                withContext(Dispatchers.Main) {
                    callback(tripSummary)
                }
            }
        } else {
            //if OBD not connected in a test scenario, create testing dummy data
            val duration = calculateDuration()
            val date = SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date())
            //TripSummary with estimated test data
            val tripSummary = TripSummary(
                averageSpeed = 60.0f,
                distanceTraveled = 100.3f,
                averageFuelConsumption = 10.8f,
                fuelUsed = 1.86f,
                tripDuration = duration,
                date = date,
                maxRPM = 3000,
                avgRPM = 2700.0f
            )
            callback(tripSummary)
        }
    }

    private fun showTripSummaryDialog(tripSummary: TripSummary) {
        val dialogView = layoutInflater.inflate(R.layout.trip_summary_dialog, null)
        //references to views in the dialog
        val avgSpeedText = dialogView.findViewById<TextView>(R.id.avgSpeedText)
        val distanceText = dialogView.findViewById<TextView>(R.id.distanceText)
        val fuelConsumptionText = dialogView.findViewById<TextView>(R.id.fuelConsumptionText)
        val fuelUsedText = dialogView.findViewById<TextView>(R.id.fuelUsedText)
        val tripDurationText = dialogView.findViewById<TextView>(R.id.tripDurationText)
        val estimatedCostText = dialogView.findViewById<TextView>(R.id.estimatedCostText)

        //set values to dialog views
        avgSpeedText.text = "${formatFloat(tripSummary.averageSpeed)} km/h"
        distanceText.text = "${formatFloat(tripSummary.distanceTraveled)} km"
        fuelConsumptionText.text = "${formatFloat(tripSummary.averageFuelConsumption)} L/100km"
        fuelUsedText.text = "${formatFloat(tripSummary.fuelUsed)} L"
        tripDurationText.text = tripSummary.tripDuration

        //calculate estimated cost
        val fuelPrice = 1.77 //price per liter in Euro
        val estimatedCost = tripSummary.fuelUsed * fuelPrice
        estimatedCostText.text = getString(R.string.estimated_cost_format, estimatedCost)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<TextView>(R.id.feedbackText).visibility = View.GONE
        dialogView.findViewById<TextView>(R.id.feedbackTitle).visibility = View.GONE
        val scoreTextView = dialogView.findViewById<TextView>(R.id.efficiencyScoreText)

        val trip = Trip(
            date = tripSummary.date,
            duration = tripSummary.tripDuration,
            averageSpeed = tripSummary.averageSpeed,
            distanceTraveled = tripSummary.distanceTraveled,
            averageFuelConsumption = tripSummary.averageFuelConsumption,
            fuelUsed = tripSummary.fuelUsed,
            maxRPM = tripSummary.maxRPM,
            avgRPM = tripSummary.avgRPM
        )

        val efficiencyScore = EfficiencyCalculator.calculateEfficiencyScore(trip)
        scoreTextView.setTextColor(getScoreColor(efficiencyScore))
        scoreTextView.text = efficiencyScore.toString()

        tripSummary.efficiencyScore = efficiencyScore

        //button click listeners
        dialogView.findViewById<Button>(R.id.saveButton).setOnClickListener {
            //save trip
            saveTripToDatabase(tripSummary)
            dialog.dismiss()
            finish() //return
        }

        dialogView.findViewById<Button>(R.id.dismissButton).setOnClickListener {
            dialog.dismiss()
            finish()
        }

        dialog.show()
    }

    private fun saveTripToDatabase(tripSummary: TripSummary) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                withContext(Dispatchers.IO) {
                    tripRepository.saveTrip(tripSummary.date, tripSummary.tripDuration, tripSummary)
                        .onSuccess {
                            withContext(Dispatchers.Main) {
                                showToast("Trip saved successfully ")
                                cleanup()
                                finish()
                            }
                        }
                        .onFailure { e ->
                            withContext(Dispatchers.Main) {
                                showToast("Failed to save trip: ${e.message}")
                            }
                        }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error saving trip: ${e.message}")
                }
            }
        }
    }

    private fun calculateDuration(): String {
        val elapsedTime = System.currentTimeMillis() - startTime
        val seconds = (elapsedTime / 1000).toInt()
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> {
                val remainingMinutes = minutes % 60
                if (remainingMinutes > 0) "$hours hours $remainingMinutes minutes"
                else "$hours hours"
            }
            minutes > 0 -> "$minutes minutes"
            else -> "$seconds seconds"
        }
    }

    //format floats to one decimal place
    private fun formatFloat(value: Float): String {
        return String.format("%.1f", value)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun checkAndRequestPermissions() {
        when {
            !hasLocationPermission() -> requestLocationPermission()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasNotificationPermission() -> requestNotificationPermission()
            else -> {
                startLocationService()
                startLocationUpdates()
            }
        }
    }

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return
        }

        try {
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(500L) //Minimum 0.5 second between updates
                .setMaxUpdateDelayMillis(2000L) //Maximum 2 seconds delay
                .build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Error starting location updates", e)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        setupMapSettings()
        if (hasLocationPermission()) {
            enableLocationFeatures()
        }
    }

    private fun setupMapSettings() {
        googleMap?.apply {
            uiSettings.apply {
                isTiltGesturesEnabled = true
                isMyLocationButtonEnabled = true
            }
        }
    }

    private fun enableLocationFeatures() {
        try {
            googleMap?.isMyLocationEnabled = true
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    updateMapLocation(LatLng(it.latitude, it.longitude), false)
                }
            }
            pendingLocation?.let { location ->
                //dont update bearing for pending locations
                updateMapLocation(location, false)
                pendingLocation = null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error enabling location features", e)
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        //use sensors when GPS bearing is not available or speed is very low
        if (previousLocation?.speed ?: 0f > 1.0f) return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                System.arraycopy(event.values, 0, accelerometerValues, 0, 3)
                hasAccelerometerData = true
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(event.values, 0, magnetometerValues, 0, 3)
                hasMagnetometerData = true

                //sensor accuracy
                if (event.accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) {
                    return
                }
            }
        }

        if (hasAccelerometerData && hasMagnetometerData) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magnetometerValues)) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                val degrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                val newBearing = if (degrees < 0) degrees + 360f else degrees
                sensorBearing = lowPassFilter(sensorBearing, newBearing, 0.05f)

                //use sensor bearing when GPS bearing is unavailable
                if (bearingQueue.isEmpty() && previousLocation?.speed ?: 0f < 1.0f) {
                    currentBearing = sensorBearing
                    if (googleMap != null && pendingLocation != null) {
                        updateMapLocation(pendingLocation!!, true)
                        pendingLocation = null
                    }
                }
            }
        }
    }

    private fun lowPassFilter(oldValue: Float, newValue: Float, factor: Float): Float {
        return oldValue + factor * ((newValue - oldValue + 180) % 360 - 180)
    }
    private fun Collection<Float>.average(): Double {
        if (isEmpty()) return 0.0

        var sumSin = 0.0
        var sumCos = 0.0
        //convert to radians
        forEach { bearing ->
            val rad = Math.toRadians(bearing.toDouble())
            sumSin += Math.sin(rad)
            sumCos += Math.cos(rad)
        }

        val avg = Math.toDegrees(Math.atan2(sumSin, sumCos))
        return if (avg < 0) avg + 360 else avg
    }

    private fun cleanup() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        stopLocationService()
        sensorManager.unregisterListener(this)
        //Cancel OBD data collection
        obdDataCollectionJob?.cancel()
        obdDataCollectionJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions()
        } else {
            val message = when (requestCode) {
                LOCATION_PERMISSION_REQUEST_CODE ->
                    "Location permission is required for tracking your drive"
                NOTIFICATION_PERMISSION_REQUEST_CODE ->
                    "Notification permission is required for tracking in background"
                else -> "Required permission was denied"
            }
            showToast(message)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    private fun checkGooglePlayServices(): Boolean {
        val availability = GoogleApiAvailability.getInstance()
        val resultCode = availability.isGooglePlayServicesAvailable(this)

        if (resultCode != ConnectionResult.SUCCESS) {
            if (availability.isUserResolvableError(resultCode)) {
                availability.getErrorDialog(this, resultCode, 9000)?.show()
            }
            showToast("Google Play Services required")
            return false
        }
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startLocationService() {
        startForegroundService(Intent(this, LocationService::class.java))
    }
    private fun stopLocationService() {
        stopService(Intent(this, LocationService::class.java))
    }

    private fun stopLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
    private fun getScoreColor(score: Int): Int {
        return when {
            score >= 85 -> getColor(android.R.color.holo_green_dark)
            score >= 70 -> getColor(android.R.color.holo_blue_dark)
            score >= 50 -> getColor(android.R.color.holo_orange_dark)
            else -> getColor(android.R.color.holo_red_dark)
        }
    }
}

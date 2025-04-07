package com.example.drivingefficiencyapp.viewLayer.ui

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
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

class StartDriveActivity : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var binding: StartDriveActivityBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var googleMap: GoogleMap? = null
    private var pendingLocation: LatLng? = null
    private var currentBearing: Float = 0f
    private var startTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private val tripRepository = TripRepository()

    //OBD data collection
    private var obdDataCollectionJob: Job? = null
    private var obdDataReader: ObdDataReader? = null
    private var previousLocation: Location? = null

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
                val currentLatLng = LatLng(location.latitude, location.longitude)
                var newBearing: Float? = null // Use nullable Float to indicate if bearing was determined

                if (location.hasBearing() && location.speed > 0.5f) { // Maybe lower threshold slightly?
                    newBearing = location.bearing
                } else if (previousLocation != null && location.speed > 0.5f) { // Fallback calculation
                    if (location.latitude != previousLocation!!.latitude || location.longitude != previousLocation!!.longitude) {
                        newBearing = previousLocation!!.bearingTo(location) // Calculate bearing manually
                    }
                }
                if (newBearing != null) {
                    // Optional: Add smoothing logic here (see below)
                    currentBearing = newBearing // Update the current bearing
                    updateMapCamera(currentLatLng, true)
                } else {
                    // Update position only if no reliable bearing found
                    updateMapCamera(currentLatLng, false)
                }

                previousLocation = location // Store for the next iteration
            }
        }
    }

    private fun updateMapCamera(location: LatLng, updateBearing: Boolean) {
        val cameraPositionBuilder = CameraPosition.Builder()
            .target(location)
            .zoom(15f)
            .tilt(45f)  // Lower tilt for better visibility

        if (updateBearing) {
            cameraPositionBuilder.bearing(currentBearing)
        }

        googleMap?.animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()),
            500,  // Simple animation duration
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
                    updateMapCamera(LatLng(it.latitude, it.longitude), false)
                }
            }
            pendingLocation?.let { location ->
                //dont update bearing for pending locations
                updateMapCamera(location, false)
                pendingLocation = null
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Error enabling location features", e)
        }
    }

    private fun cleanup() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopLocationUpdates()
        stopLocationService()
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
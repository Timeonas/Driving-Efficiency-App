package com.example.drivingefficiencyapp.viewLayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.drivingefficiencyapp.databinding.MainMenuActivityBinding
import com.example.drivingefficiencyapp.viewLayer.obd.ObdConnectActivity
import com.example.drivingefficiencyapp.modelLayer.obd.ObdConnectionManager
import com.example.drivingefficiencyapp.viewLayer.profile.ProfileActivity
import com.example.drivingefficiencyapp.viewLayer.trip.TripsActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainMenuActivity : AppCompatActivity() {
    private lateinit var binding: MainMenuActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        binding = MainMenuActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        observeObdConnection()
    }

    private fun setupButtons() {
        //OBD button
        binding.obdConnectButton.setOnClickListener {
            if (ObdConnectionManager.connectionState.value) {
                //if already connected, show OBD screen
                val intent = Intent(this, ObdConnectActivity::class.java)
                startActivity(intent)
            } else {
                //if not connected, try to connect in background
                binding.connectionStatusText.text = "Connecting to OBD..."

                //timeout to reset the status if connection is taking too long
                lifecycleScope.launch {
                    delay(6000) // Wait slightly longer than connection timeout
                    if (binding.connectionStatusText.text == "Connecting to OBD...") {
                        binding.connectionStatusText.text = "Connection timed out"
                        delay(2000) // Show timeout message briefly
                        binding.connectionStatusText.text = "OBD Not Connected"
                    }
                }
            }
        }

        //start drive button
        binding.startDriveButton.setOnClickListener {
            val intent = Intent(this, StartDriveActivity::class.java)
            startActivity(intent)
        }

        //view trips button
        binding.viewTripsButton.setOnClickListener {
            val intent = Intent(this, TripsActivity::class.java)
            startActivity(intent)
        }

        //profile button
        binding.profileButton.setOnClickListener {
            val intent = Intent(this, ProfileActivity::class.java)
            startActivity(intent)
        }

        //OBD Test button (for testing purposes)
        binding.obdTestButton.setOnClickListener {
            val intent = Intent(this, ObdConnectActivity::class.java)
            startActivity(intent)
        }

        updateButtonState(false)
    }

    private fun observeObdConnection() {
        lifecycleScope.launchWhenStarted {
            ObdConnectionManager.connectionState.collect { isConnected ->
                updateButtonState(isConnected)
                updateConnectionStatus(isConnected)
                //connect button text based on connection state
                binding.obdConnectButton.text = if (isConnected) {
                    "OBD Settings"
                } else {
                    "Connect to OBD"
                }
            }
        }
    }

    private fun updateButtonState(isConnected: Boolean) {
        //Start Drive button should depend on OBD connection
        binding.startDriveButton.isEnabled = isConnected
        binding.startDriveButton.alpha = if (isConnected) 1.0f else 0.5f

        //View Trips should always be enabled
        binding.viewTripsButton.isEnabled = true
        binding.viewTripsButton.alpha = 1.0f

        //update connection indicator
        binding.connectionStatusView.visibility = View.VISIBLE
        binding.connectionStatusView.setImageResource(
            if (isConnected) android.R.drawable.presence_online
            else android.R.drawable.presence_offline
        )
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        binding.connectionStatusText.text = if (isConnected) {
            "OBD Connected"
        } else {
            "OBD Not Connected"
        }

        //prompt text based on connection status
        binding.promptText.text = if (isConnected) {
            "You're all set! Start driving or view past trips."
        } else {
            "Connect to your OBD adapter to start using the app."
        }
    }

    override fun onResume() {
        super.onResume()
        //check OBD connection state has changed
        updateButtonState(ObdConnectionManager.connectionState.value)
        updateConnectionStatus(ObdConnectionManager.connectionState.value)
    }
}
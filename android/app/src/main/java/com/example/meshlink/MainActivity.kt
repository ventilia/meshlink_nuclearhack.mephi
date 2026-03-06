package com.example.meshlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.example.meshlink.network.wifidirect.WiFiDirectService
import com.example.meshlink.ui.MeshLinkApp
import com.example.meshlink.ui.theme.MeshLinkTheme
import com.example.meshlink.util.NotificationHelper

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            Log.w(TAG, "Denied permissions: $denied")
            val criticalDenied = denied.filter {
                it == Manifest.permission.ACCESS_FINE_LOCATION ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                it == Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (criticalDenied.isNotEmpty()) {
                Toast.makeText(
                    this,
                    "Location/Nearby permission required for peer discovery",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        startWiFiDirectService()
    }

    private lateinit var app: MeshLinkApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")

        WindowCompat.setDecorFitsSystemWindows(window, false)

        app = application as MeshLinkApp
        app.initializeContainer(this)
        app.notifyServiceContainerReady()

        NotificationHelper.createChannels(this)

        checkServices()
        requestPermissions()

        setContent {
            MeshLinkTheme {
                MeshLinkApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")

        AppForegroundTracker.setForeground(true)
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause — service keeps running in background")

        AppForegroundTracker.setForeground(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "onDestroy — service continues independently")
        AppForegroundTracker.setForeground(false)
    }

    private fun requestPermissions() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            add(Manifest.permission.RECORD_AUDIO)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isNotEmpty()) {
            Log.i(TAG, "Requesting ${needed.size} permission(s): $needed")
            permissionsLauncher.launch(needed.toTypedArray())
        } else {
            Log.i(TAG, "All permissions already granted")
            startWiFiDirectService()
        }
    }

    private fun startWiFiDirectService() {
        Log.i(TAG, "Starting WiFiDirectService")
        val intent = Intent(this, WiFiDirectService::class.java).apply {
            action = WiFiDirectService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun checkServices() {
        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            Log.w(TAG, "Wi-Fi is disabled")
            Toast.makeText(this, "Please enable Wi-Fi for peer discovery", Toast.LENGTH_LONG).show()
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled && !networkEnabled) {
            Log.w(TAG, "Location is disabled")
            Toast.makeText(this, "Please enable location for Wi-Fi Direct", Toast.LENGTH_LONG).show()
        }
    }
}
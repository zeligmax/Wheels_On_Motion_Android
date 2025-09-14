package com.example.wheels_on_motion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.log
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity(), SensorEventListener, OnMapReadyCallback {

    private lateinit var btnRecord: Button
    private lateinit var textLatitude: TextView
    private lateinit var textLongitude: TextView
    private lateinit var textAltitude: TextView
    private lateinit var textAccelX: TextView
    private lateinit var textAccelY: TextView
    private lateinit var textAccelZ: TextView
    private var isRecording = false

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback
    private var mMap: GoogleMap? = null
    private var currentMarker: Marker? = null

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f

    private val REQUEST_LOCATION_PERMISSIONS = 1001

    private lateinit var viewModel: MainActivityViewModel



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tempViewModel: MainActivityViewModel by viewModels()
        viewModel = tempViewModel

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect {
                    // Update UI elements
                    isRecording = it.isRecording

                    if (it.isRecording) {
                        btnRecord.text = "⏹ Parar y Guardar"
                    } else {
                        btnRecord.text = "▶ Empezar Grabación"
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(2.seconds)

                    val lastRecording = RecordingRepository.getLastRecording()

                    if (lastRecording != null) {
                        textLatitude.text = "Latitud: ${lastRecording.latitude}"
                        textLongitude.text = "Longitud: ${lastRecording.longitude}"
                        textAltitude.text = "Altitud: ${lastRecording.altitude} m"

                        textAccelX.text = "X: ${lastRecording.accelX}"
                        textAccelY.text = "Y: ${lastRecording.accelY}"
                        textAccelZ.text = "Z: ${lastRecording.accelZ}"
                    }
                }
            }
        }

        btnRecord = findViewById(R.id.btnRecord)

        textLatitude = findViewById(R.id.textLatitud)
        textLongitude = findViewById(R.id.textLongitud)
        textAltitude = findViewById(R.id.textAltitud)

        textAccelX = findViewById(R.id.textAccelX)
        textAccelY = findViewById(R.id.textAccelY)
        textAccelZ = findViewById(R.id.textAccelZ)

        // Inicializar sensores
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Inicializar FusedLocation
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Inicializar mapa
        val mapFragment = findViewById<MapView>(R.id.mapView)
        mapFragment.getMapAsync(this)

        // Configurar LocationRequest
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000)
            .setMinUpdateDistanceMeters(0f)
            .build()

        // Configurar LocationCallback
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                mMap?.let {
                    for (location in result.locations) {
                        val latLng = LatLng(location.latitude, location.longitude)
                        if (currentMarker == null) {
                            currentMarker = it.addMarker(
                                MarkerOptions().position(latLng).title("Mi ubicación")
                            )
                            it.moveCamera(CameraUpdateFactory.newLatLngZoom(latLng, 16f))
                        } else {
                            currentMarker?.position = latLng
                            it.animateCamera(CameraUpdateFactory.newLatLng(latLng))
                        }
                    }
                }
            }
        }

        // Botón de grabación
        btnRecord.setOnClickListener {
            if (isRecording) {
                stopService(Intent(this, RecordingService::class.java))
                viewModel.setRecording(false)
            } else {
                checkAndStartService()
                viewModel.setRecording(true)
            }
        }
    }

    private fun checkAndStartService() {
        val neededPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.FOREGROUND_SERVICE_LOCATION
        )

        val missing = neededPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_LOCATION_PERMISSIONS)
        } else {
            startRecordingService()
        }
    }

    private fun startRecordingService() {

        viewModel.setRecording(true)

        val intent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Activar acelerómetro
        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Activar ubicación
        startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                mainLooper
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startRecordingService()
        } else {
            Toast.makeText(this, "Permisos necesarios para grabar y usar GPS", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mMap?.isMyLocationEnabled = true
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                accelX = it.values[0]
                accelY = it.values[1]
                accelZ = it.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

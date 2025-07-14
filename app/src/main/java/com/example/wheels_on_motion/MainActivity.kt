package com.example.wheels_on_motion

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import java.io.File

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    private var _accelX by mutableFloatStateOf(0f)
    private var _accelY by mutableFloatStateOf(0f)
    private var _accelZ by mutableFloatStateOf(0f)
    private var _latitude by mutableDoubleStateOf(0.0)
    private var _longitude by mutableDoubleStateOf(0.0)
    private var _altitude by mutableDoubleStateOf(0.0)

    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    private var isRecording by mutableStateOf(false)
    private val recordedData = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicializa sensores
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        // Inicializa GPS
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        requestPermissions()

        // Temporizador de grabación
        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                val timestamp = System.currentTimeMillis()
                val dataLine = "$timestamp,$_latitude,$_longitude,$_altitude,$_accelX,$_accelY,$_accelZ"
                recordedData.add(dataLine)
                handler.postDelayed(this, 2000)
            }
        }

        setContent {
            Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    Text("📍 GPS:")
                    Text("Latitud: $_latitude")
                    Text("Longitud: $_longitude")
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("📡 Acelerómetro:")
                    Text("X: $_accelX")
                    Text("Y: $_accelY")
                    Text("Z: $_accelZ")
                    Text("Altitud: $_altitude m")
                    Spacer(modifier = Modifier.height(32.dp))

                    // BOTÓN VISIBLE Y COLORIDO
                    Button(
                        onClick = {
                            if (isRecording) {
                                stopRecordingAndSave()
                            } else {
                                startRecording()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(red = 0f, green = 0.7f, blue = 0.7f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(64.dp)
                    ) {
                        Text(
                            if (isRecording) "⏹️ Parar y Guardar" else "▶️ Empezar Grabación",
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                }
            }
        }
    }

    // Permisos
    private fun requestPermissions() {
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
            if (granted) {
                getLastLocation()
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            getLastLocation()
        }
    }

    // Obtener ubicación
    private fun getLastLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location: Location? ->
                    location?.let {
                        _latitude = it.latitude
                        _longitude = it.longitude
                        _altitude = it.altitude
                    }
                }
        }
    }

    // Iniciar grabación
    private fun startRecording() {
        isRecording = true
        recordedData.clear()
        handler.postDelayed(runnable, 0)
    }

    // Parar y guardar
    private fun stopRecordingAndSave() {
        isRecording = false
        handler.removeCallbacks(runnable)

        if (recordedData.isNotEmpty()) {
            val fileName = "wheels_data_${System.currentTimeMillis()}.txt"
            val file = File(filesDir, fileName)
            file.writeText(recordedData.joinToString("\n"))
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        handler.removeCallbacks(runnable)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            _accelX = it.values[0]
            _accelY = it.values[1]
            _accelZ = it.values[2]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No necesario
    }
}

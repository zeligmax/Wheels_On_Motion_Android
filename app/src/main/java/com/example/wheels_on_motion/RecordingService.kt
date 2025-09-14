package com.example.wheels_on_motion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Half.EPSILON
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.sin
import kotlin.math.sqrt

const val RECORDING_DELAY = 1500L

class RecordingService : Service(), SensorEventListener {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var latitude = 0.0
    private var longitude = 0.0
    private var altitude = 0.0

    private var sensorTimestamp = 0L

    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        Log.d("RecordingService", "Servicio creado")

        // Inicializar servicios
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        handler = Handler(Looper.getMainLooper())

        // Configuración de ubicación (cada 2s o 5m)
        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, RECORDING_DELAY
        ).setMinUpdateDistanceMeters(0f).build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    latitude = location.latitude
                    longitude = location.longitude
                    altitude = location.altitude
                    Log.d("RecordingService", "Ubicación: $latitude,$longitude,$altitude")
                }
            }
        }

        runnable = object : Runnable {
            override fun run() {
                if (latitude == 0.0 || longitude == 0.0 || altitude == 0.0) {
                    Log.w("RecordingService", "Ubicación no disponible")
                    handler.postDelayed(this, RECORDING_DELAY)
                    return
                }

                val timestamp = System.currentTimeMillis()
                val dataLine = "$timestamp,$latitude,$longitude,$altitude,$accelX,$accelY,$accelZ"
                Log.d("RecordingService", "Guardando datos: $dataLine")

                RecordingRepository.addRecording(
                    Recording(
                        latitude,
                        longitude,
                        altitude,
                        accelX,
                        accelY,
                        accelZ
                    )
                )
                handler.postDelayed(this, RECORDING_DELAY)
            }
        }

        startForegroundService()
        startRecording()
    }

    private fun startForegroundService() {
        val channelId = "recording_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Grabación en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Grabación en curso")
            .setContentText("Recolectando datos de GPS y acelerómetro")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ con tipo de servicio en primer plano
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(1, notification)
        }
    }

    private fun startRecording() {
        Log.d("RecordingService", "Iniciando grabación...")
        handler.post(runnable)

        accelSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } else {
            Log.w("RecordingService", "No se tienen permisos de localización")
        }
    }

    private fun stopRecording() {
        Log.d("RecordingService", "Deteniendo grabación...")
        handler.removeCallbacks(runnable)
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        RecordingRepository.saveRecordings(this)
    }

    private fun enviarUDP(mensaje: String, ipDestino: String = "192.168.1.75", puerto: Int = 5005) {
        Thread {
            try {
                val socket = DatagramSocket()
                val buffer = mensaje.toByteArray()
                val address = InetAddress.getByName(ipDestino)
                val packet = DatagramPacket(buffer, buffer.size, address, puerto)
                socket.send(packet)
                socket.close()
                Log.d("RecordingService", "UDP enviado: $mensaje")
            } catch (e: Exception) {
                Log.e("RecordingService", "Error enviando UDP", e)
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        Log.d("RecordingService", "Servicio destruido")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_GYROSCOPE) {
                if (sensorTimestamp != 0L) {
                    val dT = (event.timestamp - sensorTimestamp) * NS2S

                    var axisX = event.values[0]
                    var axisY = event.values[1]
                    var axisZ = event.values[2]

                    val omegaMagnitude = sqrt(axisX*axisX + axisY*axisY + axisZ*axisZ)

                    if (omegaMagnitude > EPSILON) {
                        axisX /= omegaMagnitude
                        axisY /= omegaMagnitude
                        axisZ /= omegaMagnitude
                    }

                    val thetaOverTwo = omegaMagnitude * dT / 2.0f
                    val sinThetaOverTwo = sin(thetaOverTwo)

                    accelX = sinThetaOverTwo * axisX
                    accelY = sinThetaOverTwo * axisY
                    accelZ = sinThetaOverTwo * axisZ
                }
                else {
                    sensorTimestamp = event.timestamp
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

private const val NS2S = 1.0f / 1000000000.0f;
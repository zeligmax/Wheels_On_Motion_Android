package com.example.wheels_on_motion

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.abs

class MainActivity : ComponentActivity(), SensorEventListener {

    // Constantes del acelerómetro
    private val motionThreshold = 0.5f  // Sensibilidad al movimiento
    private var lastAccelX = 0f
    private var lastAccelY = 0f
    private var lastAccelZ = 0f

    private val alpha = 0.8f  // Suavizado
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var smoothedZ = 0f

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    // Estados observables para Compose
    private var accelX by mutableFloatStateOf(0f)
    private var accelY by mutableFloatStateOf(0f)
    private var accelZ by mutableFloatStateOf(0f)
    private var latitude by mutableDoubleStateOf(0.0)
    private var longitude by mutableDoubleStateOf(0.0)
    private var altitude by mutableDoubleStateOf(0.0)

    private lateinit var handler: Handler
    private lateinit var runnable: Runnable

    private var isRecording by mutableStateOf(false)
    private val recordedData = mutableListOf<String>()
    private val recordedLocations = mutableStateListOf<Location>() // Para el mapa

    // Launcher para solicitar permisos
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var fineLocationGranted = false
        permissions[Manifest.permission.ACCESS_FINE_LOCATION]?.let {
            fineLocationGranted = it
        }

        if (fineLocationGranted) {
            getLastLocation()
        } else {
            Toast.makeText(this, "El permiso de ubicación fina es necesario para obtener la posición precisa.", Toast.LENGTH_LONG).show()
            // Considerar si se quiere intentar con COARSE si FINE no se da, o manejar de otra forma.
            if (permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                getLastLocation() // Intentar con coarse si está disponible
            }
        }
        // El permiso de escritura WRITE_EXTERNAL_STORAGE no es necesario para filesDir.
        // Si se necesita para la exportación a Descargas en versiones antiguas o casos específicos:
        // permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE]?.let { granted ->
        //     if (granted) {
        //         // Lógica si el permiso de escritura es concedido
        //     } else {
        //         Toast.makeText(this, "Permiso de escritura necesario para exportar.", Toast.LENGTH_SHORT).show()
        //     }
        // }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        requestLocationAndStoragePermissions() // Solicitar permisos al inicio

        handler = Handler(Looper.getMainLooper())
        runnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    getLastLocation()
                    val timestamp = System.currentTimeMillis()
                    val dataLine = "$timestamp,$latitude,$longitude,$altitude,$accelX,$accelY,$accelZ"
                    recordedData.add(dataLine)
                    enviarUDP(dataLine) // Enviar al PC en tiempo real
                    handler.postDelayed(this, 2000)
                }
            }
        }

        setContent {
            WheelsOnMotionTheme { // Asegúrate de tener un tema definido
                Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
                    Column(
                        modifier = Modifier
                            .padding(paddingValues)
                            .padding(16.dp)
                            .fillMaxSize() // La Column ocupa todo el espacio
                    ) {
                        Text("📍 GPS:")
                        Text("Latitud: $latitude")
                        Text("Longitud: $longitude")
                        Text("Altitud: $altitude m")
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("📡 Acelerómetro:")
                        Text("X: $accelX")
                        Text("Y: $accelY")
                        Text("Z: $accelZ")
                        Spacer(modifier = Modifier.height(16.dp))

                        // Mapa - darle un peso para que ocupe el espacio restante
                        Box(modifier = Modifier.weight(1f)) {
                            MapScreen(recordedLocations.toList())
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                if (isRecording) {
                                    stopRecordingAndSave()
                                } else {
                                    startRecording()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
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
    }

    private fun requestLocationAndStoragePermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Para exportar a Downloads en Android < 10, WRITE_EXTERNAL_STORAGE puede ser necesario.
        // En Android 10+ se usa MediaStore o es posible si la app creó el archivo.
        // Por simplicidad, no lo pediremos a menos que sea estrictamente necesario y se maneje el Scoped Storage.
        // if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        //     if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        //         permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        //     }
        // }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Si ya tenemos los permisos, obtenemos la ubicación
            getLastLocation()
        }
    }

    private fun getLastLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            // Si no hay permisos, solicitarlos (aunque requestLocationAndStoragePermissions ya debería haberlo hecho)
            requestLocationAndStoragePermissions()
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                location?.let {
                    latitude = it.latitude
                    longitude = it.longitude
                    altitude = it.altitude

                    if (isRecording) {
                        recordedLocations.add(it) // Añadir a la lista para el mapa
                    }
                } ?: run {
                    // Toast.makeText(this, "No se pudo obtener la última ubicación conocida (null).", Toast.LENGTH_SHORT).show()
                    // Aquí podrías querer iniciar actualizaciones de ubicación si lastLocation es null
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error al obtener la ubicación: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Se requieren permisos de ubicación para grabar.", Toast.LENGTH_SHORT).show()
            requestLocationAndStoragePermissions()
            return
        }
        isRecording = true
        recordedData.clear()
        recordedLocations.clear()
        handler.post(runnable) // Iniciar el runnable
        Toast.makeText(this, "Grabación iniciada.", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecordingAndSave() {
        isRecording = false
        handler.removeCallbacks(runnable) // Detener el runnable
        Toast.makeText(this, "Grabación detenida.", Toast.LENGTH_SHORT).show()

        if (recordedData.isNotEmpty()) {
            val fileName = "wheels_data_${System.currentTimeMillis()}.txt"
            val file = File(filesDir, fileName) // Guardar en almacenamiento interno
            try {
                file.writeText(recordedData.joinToString("\n"))
                Toast.makeText(this, "Archivo guardado en: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                exportFileToDownloads(fileName) // Intentar exportar
            } catch (e: IOException) {
                e.printStackTrace()
                Toast.makeText(this, "Error al guardar el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No hay datos para guardar.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportFileToDownloads(fileName: String) {
        val sourceFile = File(filesDir, fileName)
        if (!sourceFile.exists()) {
            Toast.makeText(this, "Archivo fuente no existe para exportar.", Toast.LENGTH_SHORT).show()
            return
        }

        // Comprobar si tenemos permiso para escribir en almacenamiento externo (relevante para Android < 10)
        // En Android 10+ esto se maneja de forma diferente (MediaStore o acceso directo si la app creó la carpeta).
        // Por ahora, asumimos que si llegamos aquí, queremos intentar la copia.

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) {
            if (!downloadsDir.mkdirs()) {
                Toast.makeText(this, "No se pudo crear el directorio de Descargas.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        val destFile = File(downloadsDir, fileName)

        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            // MediaStore para que sea visible en gestores de archivos inmediatamente
            // val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            // mediaScanIntent.data = Uri.fromFile(destFile)
            // sendBroadcast(mediaScanIntent)
            Toast.makeText(this, "Archivo exportado a Descargas: ${destFile.name}", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Error al exportar archivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enviarUDP(mensaje: String, ipDestino: String = "192.168.1.75", puerto: Int = 5005) {
        Thread {
            try {
                val socket = java.net.DatagramSocket()
                val buffer = mensaje.toByteArray()
                val address = java.net.InetAddress.getByName(ipDestino)
                val packet = java.net.DatagramPacket(buffer, buffer.size, address, puerto)
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    @Composable
    fun MapScreen(locations: List<Location>) {
        val defaultLocation = LatLng(0.0, 0.0) // Ubicación por defecto si no hay otra disponible

        val initialCameraPosition = when {
            locations.isNotEmpty() -> LatLng(locations.first().latitude, locations.first().longitude)
            latitude != 0.0 || longitude != 0.0 -> LatLng(latitude, longitude)
            else -> defaultLocation
        }

        val cameraPositionState = rememberCameraPositionState {
            position = CameraPosition.fromLatLngZoom(initialCameraPosition, 15f)
        }

        LaunchedEffect(locations, latitude, longitude) {
            val targetPosition = when {
                locations.isNotEmpty() -> LatLng(locations.last().latitude, locations.last().longitude)
                latitude != 0.0 || longitude != 0.0 -> LatLng(latitude, longitude)
                else -> null // No mover si no hay ubicación válida
            }
            targetPosition?.let {
                // Considera usar animate en lugar de cambiar directamente la posición para una transición suave
                // cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(it, 15f))
                cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 15f)
            }
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(), // El Box contenedor ya gestiona el tamaño/peso
            cameraPositionState = cameraPositionState,
            uiSettings = MapUiSettings(zoomControlsEnabled = true) // Habilitar controles de zoom
        ) {
            if (locations.isNotEmpty()) {
                Polyline(
                    points = locations.map { LatLng(it.latitude, it.longitude) },
                    color = Color.Red,
                    width = 5f // Hacer la línea un poco más gruesa
                )
                Marker(
                    state = rememberMarkerState(position = LatLng(locations.last().latitude, locations.last().longitude)),
                    title = "Última ubicación grabada"
                )
            }
            // Marcador para la ubicación actual (si no se está grabando o al inicio)
            if (locations.isEmpty() && (latitude != 0.0 || longitude != 0.0)) {
                Marker(
                    state = rememberMarkerState(position = LatLng(latitude, longitude)),
                    title = "Ubicación Actual"
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        accelSensor?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        // Obtener ubicación si los permisos están concedidos y la app vuelve al primer plano
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            getLastLocation()
        }
        // Si estaba grabando, reanudar el handler (aunque stopRecordingAndSave lo detiene)
        // Esta lógica podría necesitar ajuste según el comportamiento deseado al pausar/reanudar durante una grabación.
        // if (isRecording) {
        //     handler.post(runnable)
        // }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        // Detener el handler si la app se pausa para evitar trabajo en segundo plano no deseado.
        // Si la grabación debe continuar en segundo plano, se necesitaría un Foreground Service.
        handler.removeCallbacks(runnable)
        // No cambiar isRecording a false aquí, para poder reanudar si es necesario,
        // a menos que la lógica sea detener la grabación al pausar.
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                val rawX = it.values[0]
                val rawY = it.values[1]
                val rawZ = it.values[2]

                // Aplicar filtro de paso bajo para suavizar
                smoothedX = alpha * rawX + (1 - alpha) * lastAccelX // Corregido: usar lastAccelX para el suavizado previo
                smoothedY = alpha * rawY + (1 - alpha) * lastAccelY
                smoothedZ = alpha * rawZ + (1 - alpha) * lastAccelZ

                val deltaX = abs(smoothedX - lastAccelX)
                val deltaY = abs(smoothedY - lastAccelY)
                val deltaZ = abs(smoothedZ - lastAccelZ)

                // Actualizar valores solo si el cambio supera el umbral
                if (deltaX > motionThreshold || deltaY > motionThreshold || deltaZ > motionThreshold) {
                    accelX = smoothedX
                    accelY = smoothedY
                    accelZ = smoothedZ
                }
                // Actualizar siempre los últimos valores para el próximo cálculo del delta y suavizado
                lastAccelX = smoothedX
                lastAccelY = smoothedY
                lastAccelZ = smoothedZ
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No es común necesitar implementar esto para el acelerómetro en muchos casos de uso.
    }
}

// Define un tema básico si no lo tienes en ui.theme/Theme.kt
@Composable
fun WheelsOnMotionTheme(content: @Composable () -> Unit) {
    MaterialTheme( // o tu tema personalizado
        // Define colores, tipografía, etc. si es necesario
        content = content
    )
}

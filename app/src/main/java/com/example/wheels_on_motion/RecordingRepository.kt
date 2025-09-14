package com.example.wheels_on_motion

import android.content.Context
import android.os.Environment
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class Recording(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val accelX: Float,
    val accelY: Float,
    val accelZ: Float
)

object RecordingRepository {
    private val recordedData = mutableListOf<Recording>()

    fun addRecording(recording: Recording) {
        recordedData.add(recording)
    }

    fun saveRecordings(context: Context) {
        if (recordedData.isEmpty()) {
            Toast.makeText(context, "No hay datos para guardar", Toast.LENGTH_SHORT).show()
            return
        }
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloadsDir, "WheelsOnMotion")
        if (!dir.exists()) dir.mkdirs()

        val fileName = "wheels_data_${System.currentTimeMillis()}.csv"
        val file = File(dir, fileName)

        try {
            FileOutputStream(file).use { output ->
                output.write("Latitude,Longitude,Altitude,Ax,Ay,Az\n".toByteArray())
                output.write(
                    recordedData.joinToString("\n") {
                        String.format(
                            Locale.getDefault(),
                            "%.2f,%.2f,%.2f,%f,%f,%f",
                            it.latitude,
                            it.longitude,
                            it.altitude,
                            it.accelX,
                            it.accelY,
                            it.accelZ,
                        )
                    }.toByteArray()
                )
            }
            Toast.makeText(context, "Archivo guardado en: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            Log.d("RecordingService", "Archivo guardado en: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("RecordingService", "Error guardando archivo", e)
        }

        this.recordedData.clear()
    }

    fun getLastRecording(): Recording? {
        return recordedData.lastOrNull()
    }
}
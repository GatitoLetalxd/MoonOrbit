package online.moondev.orbit.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import online.moondev.orbit.data.api.LocationPointDto
import java.io.File

class LocationQueueManager(context: Context) {
    private val queueFile = File(context.filesDir, "location_queue.json")
    private val gson = Gson()
    private val maxQueueSize = 50

    @Synchronized
    fun enqueue(point: LocationPointDto) {
        val current = getPendingPoints().toMutableList()
        current.add(point)
        // Keep at most 50 newest points
        val trimmed = if (current.size > maxQueueSize) {
            current.takeLast(maxQueueSize)
        } else {
            current
        }
        save(trimmed)
    }

    @Synchronized
    fun getPendingPoints(): List<LocationPointDto> {
        if (!queueFile.exists()) return emptyList()
        return try {
            val json = queueFile.readText()
            val type = object : TypeToken<List<LocationPointDto>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun clear() {
        if (queueFile.exists()) {
            queueFile.delete()
        }
    }

    private fun save(points: List<LocationPointDto>) {
        try {
            queueFile.writeText(gson.toJson(points))
        } catch (e: Exception) {}
    }
}

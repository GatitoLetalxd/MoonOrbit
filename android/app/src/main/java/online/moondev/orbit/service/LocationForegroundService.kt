package online.moondev.orbit.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.MainActivity
import online.moondev.orbit.R
import online.moondev.orbit.data.api.LocationPointDto
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.local.LocationQueueManager
import online.moondev.orbit.data.local.PreferencesManager
import java.text.SimpleDateFormat
import java.util.*

class LocationForegroundService : Service() {

    private val TAG = "LocationService"
    private val CHANNEL_ID = "moonorbit_location_channel"
    private val NOTIFICATION_ID = 1001

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefsManager: PreferencesManager
    private lateinit var queueManager: LocationQueueManager
    private val api = MoonOrbitApi()

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastLocationSentTime = 0L

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        queueManager = LocationQueueManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        setupLocationUpdates()
        startHeartbeatTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Location service onStartCommand")
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.location_service_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.location_notification_title))
            .setContentText(getString(R.string.location_notification_text))
            .setSmallIcon(R.drawable.ic_notification_moon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startHeartbeatTimer() {
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60 * 1000L) // 10 minutes
                val elapsed = System.currentTimeMillis() - lastLocationSentTime
                if (elapsed >= 9 * 60 * 1000L) {
                    val token = prefsManager.sessionToken.firstOrNull() ?: continue
                    val serverUrl = prefsManager.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
                    api.setBaseUrl(serverUrl)
                    api.setAuthToken(token)

                    val (batteryLevel, isCharging) = getBatteryInfo()
                    try {
                        api.sendHeartbeat(batteryLevel, isCharging)
                        Log.d(TAG, "Periodic heartbeat sent successfully")

                        // If there are pending points, try to flush them
                        val pending = queueManager.getPendingPoints()
                        if (pending.isNotEmpty()) {
                            api.sendLocation(pending)
                            queueManager.clear()
                            Log.d(TAG, "Pending queue flushed during heartbeat (${pending.size} points)")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send periodic heartbeat", e)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60 * 1000L // 60 seconds interval
        ).apply {
            setMinUpdateDistanceMeters(5f) // 5 meters movement trigger
            setMinUpdateIntervalMillis(30 * 1000L)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onNewLocation(loc)
            }
        }

        try {
            // Immediate location fix on service launch
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    Log.d(TAG, "Initial lastLocation reported immediately: ${loc.latitude}, ${loc.longitude}")
                    onNewLocation(loc)
                }
            }

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.d(TAG, "Initial currentLocation reported immediately: ${loc.latitude}, ${loc.longitude}")
                        onNewLocation(loc)
                    }
                }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location updates", e)
        }
    }

    private fun onNewLocation(location: Location) {
        val now = System.currentTimeMillis()
        lastLocationSentTime = now

        serviceScope.launch {
            val token = prefsManager.sessionToken.firstOrNull() ?: return@launch
            val serverUrl = prefsManager.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
            api.setBaseUrl(serverUrl)
            api.setAuthToken(token)

            val (batteryLevel, isCharging) = getBatteryInfo()
            val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

            val point = LocationPointDto(
                lat = location.latitude,
                lng = location.longitude,
                accuracyM = location.accuracy.toDouble(),
                batteryLevel = batteryLevel,
                isCharging = isCharging,
                recordedAt = isoFormat.format(Date(location.time))
            )

            val pending = queueManager.getPendingPoints()
            val batch = (pending + point).takeLast(50)

            try {
                api.sendLocation(batch)
                queueManager.clear()
                Log.d(TAG, "Location batch of ${batch.size} sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location batch, enqueuing point", e)
                queueManager.enqueue(point)
            }
        }
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus: Intent? = registerReceiver(null, ifilter)

        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 50

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

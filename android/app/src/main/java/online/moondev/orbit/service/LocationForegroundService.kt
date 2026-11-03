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

    companion object {
        private const val TAG = "LocationService"
        const val CHANNEL_ID = "moonorbit_location_channel"
        const val CHANNEL_EMERGENCY_ID = "moonorbit_sos_channel"
        const val NOTIFICATION_ID = 1001
        const val EMERGENCY_NOTIFICATION_ID = 1002

        const val ACTION_TOGGLE_SOS = "online.moondev.orbit.action.TOGGLE_SOS"
        const val ACTION_UPDATE_NOTIF_INFO = "online.moondev.orbit.action.UPDATE_NOTIF_INFO"

        const val EXTRA_SELF_SOS = "extra_self_sos"
        const val EXTRA_FAV_ID = "extra_fav_id"
        const val EXTRA_FAV_NAME = "extra_fav_name"
        const val EXTRA_FAV_DIST = "extra_fav_dist"
        const val EXTRA_FAV_BATTERY = "extra_fav_battery"
        const val EXTRA_EMERGENCY_NAME = "extra_emergency_name"
        const val EXTRA_EMERGENCY_DIST = "extra_emergency_dist"
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefsManager: PreferencesManager
    private lateinit var queueManager: LocationQueueManager
    private val api = MoonOrbitApi()

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastLocationSentTime = 0L

    // Notification state variables
    private var isSelfSos: Boolean = false
    private var favoriteMemberName: String? = null
    private var favoriteMemberDistance: String? = null
    private var favoriteMemberBattery: Int? = null
    private var emergencyMemberName: String? = null
    private var emergencyMemberDistance: String? = null

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
        queueManager = LocationQueueManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannels()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        setupLocationUpdates()
        startHeartbeatTimer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_SOS -> {
                val newSos = !isSelfSos
                isSelfSos = newSos
                updateForegroundNotification()

                serviceScope.launch {
                    val token = prefsManager.sessionToken.firstOrNull() ?: return@launch
                    val serverUrl = prefsManager.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
                    api.setBaseUrl(serverUrl)
                    api.setAuthToken(token)

                    try {
                        api.updateStatus(if (newSos) "sos" else "active", null, null)
                        Log.d(TAG, "Status updated from notification toggle to: ${if (newSos) "sos" else "active"}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to toggle SOS from notification", e)
                    }
                }
            }
            ACTION_UPDATE_NOTIF_INFO -> {
                if (intent.hasExtra(EXTRA_SELF_SOS)) {
                    isSelfSos = intent.getBooleanExtra(EXTRA_SELF_SOS, isSelfSos)
                }
                favoriteMemberName = intent.getStringExtra(EXTRA_FAV_NAME)
                favoriteMemberDistance = intent.getStringExtra(EXTRA_FAV_DIST)
                favoriteMemberBattery = if (intent.hasExtra(EXTRA_FAV_BATTERY)) {
                    intent.getIntExtra(EXTRA_FAV_BATTERY, -1)
                } else null

                val newEmergencyName = intent.getStringExtra(EXTRA_EMERGENCY_NAME)
                val newEmergencyDist = intent.getStringExtra(EXTRA_EMERGENCY_DIST)

                val emergencyStarted = !newEmergencyName.isNullOrBlank() && (emergencyMemberName != newEmergencyName)
                emergencyMemberName = newEmergencyName
                emergencyMemberDistance = newEmergencyDist

                updateForegroundNotification()

                if (emergencyStarted) {
                    postEmergencyNotification(newEmergencyName!!, newEmergencyDist)
                } else if (newEmergencyName.isNullOrBlank()) {
                    cancelEmergencyNotification()
                }
            }
            else -> {
                Log.d(TAG, "Location service onStartCommand default")
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.location_service_channel_desc)
                setShowBadge(false)
            }

            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY_ID,
                getString(R.string.emergency_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.emergency_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setShowBadge(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(locationChannel)
            manager.createNotificationChannel(emergencyChannel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sosIntent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_TOGGLE_SOS
        }
        val pendingSosIntent = PendingIntent.getService(
            this,
            1,
            sosIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sosActionTitle = if (isSelfSos) "Desactivar SOS" else "Activar SOS"
        val sosAction = NotificationCompat.Action.Builder(
            0,
            sosActionTitle,
            pendingSosIntent
        ).build()

        val mapAction = NotificationCompat.Action.Builder(
            0,
            "Ver mapa",
            pendingOpenIntent
        ).build()

        // 3-Level Priority:
        // Level 1: Self SOS Active
        // Level 2: Family member in SOS Emergency
        // Level 3: Followed Family Member selected
        // Level 4: Standard default
        val (title, text) = when {
            isSelfSos -> {
                Pair(
                    "Alerta SOS activa",
                    "Tu grupo familiar ha recibido tu alerta de emergencia con tu ubicación en tiempo real."
                )
            }
            !emergencyMemberName.isNullOrBlank() -> {
                val distText = if (!emergencyMemberDistance.isNullOrBlank()) " (A $emergencyMemberDistance)" else ""
                Pair(
                    "Alerta SOS: $emergencyMemberName",
                    "$emergencyMemberName necesita auxilio urgente$distText. Toca para ver en el mapa."
                )
            }
            !favoriteMemberName.isNullOrBlank() -> {
                val details = buildString {
                    if (!favoriteMemberDistance.isNullOrBlank()) {
                        append("A $favoriteMemberDistance")
                    }
                    if (favoriteMemberBattery != null && favoriteMemberBattery!! >= 0) {
                        if (isNotEmpty()) append(" • ")
                        append("Batería $favoriteMemberBattery%")
                    }
                }
                Pair(
                    "Siguiendo a $favoriteMemberName",
                    if (details.isNotEmpty()) details else "Monitoreando ubicación en tiempo real"
                )
            }
            else -> {
                Pair(
                    getString(R.string.location_notification_title),
                    getString(R.string.location_notification_text)
                )
            }
        }

        val priority = if (isSelfSos || !emergencyMemberName.isNullOrBlank()) {
            NotificationCompat.PRIORITY_HIGH
        } else {
            NotificationCompat.PRIORITY_LOW
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_moonorbit)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .setPriority(priority)
            .addAction(sosAction)
            .addAction(mapAction)
            .build()
    }

    private fun updateForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun postEmergencyNotification(name: String, dist: String?) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val distStr = if (!dist.isNullOrBlank()) " (A $dist de ti)" else ""
        val notif = NotificationCompat.Builder(this, CHANNEL_EMERGENCY_ID)
            .setSmallIcon(R.drawable.ic_stat_moonorbit)
            .setContentTitle("Alerta SOS: $name")
            .setContentText("$name ha activado una alerta de auxilio$distStr")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$name ha activado una alerta de auxilio inmediato$distStr. Abre MoonOrbit para coordinar la ayuda.")
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(EMERGENCY_NOTIFICATION_ID, notif)
    }

    private fun cancelEmergencyNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(EMERGENCY_NOTIFICATION_ID)
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

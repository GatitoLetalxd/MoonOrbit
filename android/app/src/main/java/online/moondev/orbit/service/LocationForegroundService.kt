package online.moondev.orbit.service

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.MainActivity
import online.moondev.orbit.R
import online.moondev.orbit.data.api.GroupMemberDto
import online.moondev.orbit.data.api.LocationPointDto
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.MoonOrbitWebSocket
import online.moondev.orbit.data.api.WsEvent
import online.moondev.orbit.data.local.LocationQueueManager
import online.moondev.orbit.data.local.PreferencesManager
import java.text.SimpleDateFormat
import java.util.*

class LocationForegroundService : Service() {

    companion object {
        private const val TAG = "LocationService"
        const val CHANNEL_ID = "moonorbit_location_channel"
        const val CHANNEL_EMERGENCY_ID = "moonorbit_sos_channel_v2"
        const val NOTIFICATION_ID = 1001
        const val EMERGENCY_NOTIFICATION_ID = 1002

        const val ACTION_TOGGLE_SOS = "online.moondev.orbit.action.TOGGLE_SOS"
        const val ACTION_UPDATE_NOTIF_INFO = "online.moondev.orbit.action.UPDATE_NOTIF_INFO"
        const val ACTION_HEARTBEAT = "online.moondev.orbit.action.HEARTBEAT"
        const val ACTION_STOP_SERVICE = "online.moondev.orbit.action.STOP_SERVICE"

        // 6 minutes interval: safely below server's 25-minute no_signal threshold
        private const val HEARTBEAT_INTERVAL_MS = 6 * 60 * 1000L

        const val EXTRA_SELF_SOS = "extra_self_sos"
        const val EXTRA_FAV_ID = "extra_fav_id"
        const val EXTRA_FAV_NAME = "extra_fav_name"
        const val EXTRA_FAV_DIST = "extra_fav_dist"
        const val EXTRA_FAV_BATTERY = "extra_fav_battery"
        const val EXTRA_EMERGENCY_NAME = "extra_emergency_name"
        const val EXTRA_EMERGENCY_DIST = "extra_emergency_dist"

        fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val results = FloatArray(1)
            Location.distanceBetween(lat1, lng1, lat2, lng2, results)
            return results[0]
        }

        fun formatDistance(meters: Float): String {
            return if (meters < 1000f) {
                "${meters.toInt()} m"
            } else {
                val km = meters / 1000f
                if (km >= 100f) {
                    "${km.toInt()} km"
                } else {
                    val formatted = String.format(Locale.US, "%.1f", km)
                    if (formatted.endsWith(".0")) {
                        "${km.toInt()} km"
                    } else {
                        "$formatted km"
                    }
                }
            }
        }
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var prefsManager: PreferencesManager
    private lateinit var queueManager: LocationQueueManager
    private val api = MoonOrbitApi()
    private val ws = MoonOrbitWebSocket.getInstance()

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastLocationSentTime = 0L
    private var isExplicitStop = false

    // Cache of all members in current group for background distance & SOS tracking
    private val membersCache = Collections.synchronizedMap(mutableMapOf<String, GroupMemberDto>())
    private var currentUserId: String? = null
    private var currentGroupId: String? = null
    private var currentFavoriteMemberId: String? = null
    private var lastKnownLocation: Location? = null

    // Notification state variables
    private var isSelfSos: Boolean = false
    private var emergencyMemberId: String? = null
    private var emergencyMemberName: String? = null
    private var emergencyMemberDistance: String? = null
    private var favoriteMemberName: String? = null
    private var favoriteMemberDistance: String? = null
    private var favoriteMemberBattery: Int? = null

    override fun onCreate() {
        super.onCreate()
        isExplicitStop = false
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
        scheduleNextHeartbeat()
        startBackgroundWebSocket()
        observePreferences()
    }

    private fun startBackgroundWebSocket() {
        // 1. Observe current user ID
        serviceScope.launch {
            prefsManager.userId.collectLatest { uid ->
                currentUserId = uid
                evaluateNotificationState()
            }
        }

        // 2. Observe current group ID
        serviceScope.launch {
            prefsManager.groupId.collectLatest { gid ->
                currentGroupId = gid
            }
        }

        // 3. Connect WebSocket & seed initial snapshot
        serviceScope.launch {
            prefsManager.sessionToken.collectLatest { token ->
                if (token.isNullOrBlank()) {
                    return@collectLatest
                }

                val serverUrl = prefsManager.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
                val wsUrl = PreferencesManager.httpToWsUrl(serverUrl)
                ws.setWsUrl(wsUrl)
                api.setBaseUrl(serverUrl)
                api.setAuthToken(token)

                val gId = prefsManager.groupId.firstOrNull() ?: currentGroupId
                if (!gId.isNullOrBlank()) {
                    try {
                        val snap = api.getSnapshot(gId)
                        synchronized(membersCache) {
                            membersCache.clear()
                            snap.members.forEach { m -> membersCache[m.userId] = m }
                        }
                        evaluateNotificationState()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching initial snapshot in service", e)
                    }
                }

                ws.connect(token)
            }
        }

        // 4. Listen to real-time events in the background
        serviceScope.launch {
            ws.events.collect { event ->
                handleWsEvent(event)
            }
        }
    }

    private fun observePreferences() {
        serviceScope.launch {
            prefsManager.favoriteMemberId.collectLatest { favId ->
                currentFavoriteMemberId = favId
                evaluateNotificationState()
            }
        }
    }

    private fun handleWsEvent(event: WsEvent) {
        when (event) {
            is WsEvent.Snapshot -> {
                synchronized(membersCache) {
                    membersCache.clear()
                    event.snapshot.members.forEach { m -> membersCache[m.userId] = m }
                }
                evaluateNotificationState()
            }
            is WsEvent.MemberState -> {
                synchronized(membersCache) {
                    val existing = membersCache[event.userId]
                    if (existing != null) {
                        val updatedState = existing.memberState.copy(
                            status = event.status,
                            lat = event.lat ?: existing.memberState.lat,
                            lng = event.lng ?: existing.memberState.lng,
                            accuracyM = event.accuracyM ?: existing.memberState.accuracyM,
                            batteryLevel = event.batteryLevel ?: existing.memberState.batteryLevel,
                            isCharging = event.isCharging ?: existing.memberState.isCharging,
                            recordedAt = event.recordedAt ?: existing.memberState.recordedAt,
                            lastSeenAt = event.lastSeenAt ?: existing.memberState.lastSeenAt
                        )
                        membersCache[event.userId] = existing.copy(state = updatedState)
                    } else {
                        val gId = currentGroupId
                        if (!gId.isNullOrBlank()) {
                            serviceScope.launch {
                                try {
                                    val snap = api.getSnapshot(gId)
                                    synchronized(membersCache) {
                                        membersCache.clear()
                                        snap.members.forEach { m -> membersCache[m.userId] = m }
                                    }
                                    evaluateNotificationState()
                                } catch (e: Exception) {}
                            }
                        }
                    }
                }
                evaluateNotificationState()
            }
            is WsEvent.MemberJoined -> {
                synchronized(membersCache) {
                    membersCache[event.member.userId] = event.member
                }
                evaluateNotificationState()
            }
            is WsEvent.MemberLeft -> {
                synchronized(membersCache) {
                    membersCache.remove(event.userId)
                }
                evaluateNotificationState()
            }
            is WsEvent.ProfileUpdated -> {
                synchronized(membersCache) {
                    val existing = membersCache[event.userId]
                    if (existing != null) {
                        membersCache[event.userId] = existing.copy(
                            displayName = event.displayName,
                            avatarVersion = event.avatarVersion
                        )
                    }
                }
                evaluateNotificationState()
            }
            else -> {}
        }
    }

    private fun evaluateNotificationState() {
        val myId = currentUserId
        val myLoc = lastKnownLocation

        val allMembers = synchronized(membersCache) { membersCache.values.toList() }

        // 1. Self SOS state
        val selfMember = allMembers.find { it.userId == myId }
        if (selfMember != null) {
            isSelfSos = (selfMember.memberState.status == "sos")
        }

        // 2. Family emergency check (any other member with status == "sos")
        val emergencyMember = allMembers.find { it.userId != myId && it.memberState.status == "sos" }
        val previousEmergencyId = emergencyMemberId

        if (emergencyMember != null) {
            val emgName = emergencyMember.displayName
            val emgLat = emergencyMember.memberState.lat
            val emgLng = emergencyMember.memberState.lng
            val dist = if (myLoc != null && emgLat != null && emgLng != null) {
                val distM = calculateDistanceMeters(myLoc.latitude, myLoc.longitude, emgLat, emgLng)
                formatDistance(distM)
            } else null

            val wasNew = (previousEmergencyId != emergencyMember.userId)
            emergencyMemberId = emergencyMember.userId
            emergencyMemberName = emgName
            emergencyMemberDistance = dist

            if (wasNew) {
                Log.d(TAG, "New emergency detected from: $emgName. Triggering alarm notification!")
                postEmergencyNotification(emgName, dist)
            }
        } else {
            if (emergencyMemberId != null) {
                Log.d(TAG, "Emergency cleared for member: $emergencyMemberName")
                emergencyMemberId = null
                emergencyMemberName = null
                emergencyMemberDistance = null
                cancelEmergencyNotification()
            }
        }

        // 3. Followed / Favorite member
        val favId = currentFavoriteMemberId
        val favMember = if (favId != null) allMembers.find { it.userId == favId } else null
        if (favMember != null) {
            favoriteMemberName = favMember.displayName
            favoriteMemberBattery = favMember.memberState.batteryLevel
            val fLat = favMember.memberState.lat
            val fLng = favMember.memberState.lng
            favoriteMemberDistance = if (myLoc != null && fLat != null && fLng != null) {
                val distM = calculateDistanceMeters(myLoc.latitude, myLoc.longitude, fLat, fLng)
                formatDistance(distM)
            } else null
        } else {
            favoriteMemberName = null
            favoriteMemberDistance = null
            favoriteMemberBattery = null
        }

        updateForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HEARTBEAT -> {
                Log.d(TAG, "Heartbeat alarm triggered by AlarmManager")
                performHeartbeat()
                scheduleNextHeartbeat()
            }
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "ACTION_STOP_SERVICE received. Shutting down foreground service cleanly.")
                isExplicitStop = true
                cancelHeartbeatAlarm()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
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

                if (newEmergencyName != null) {
                    val emergencyStarted = !newEmergencyName.isBlank() && (emergencyMemberName != newEmergencyName)
                    emergencyMemberName = newEmergencyName
                    emergencyMemberDistance = newEmergencyDist

                    if (emergencyStarted) {
                        postEmergencyNotification(newEmergencyName, newEmergencyDist)
                    }
                } else if (intent.hasExtra(EXTRA_EMERGENCY_NAME)) {
                    emergencyMemberName = null
                    emergencyMemberDistance = null
                    cancelEmergencyNotification()
                }

                updateForegroundNotification()
            }
            else -> {
                Log.d(TAG, "Location service onStartCommand sticky start")
                scheduleNextHeartbeat()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (isExplicitStop) return

        Log.d(TAG, "onTaskRemoved: app task swiped from recents. Arming restart alarm to ensure continuous background service.")
        val restartIntent = Intent(applicationContext, LocationForegroundService::class.java).apply {
            setPackage(packageName)
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            1001,
            restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        alarmManager?.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }

    private fun scheduleNextHeartbeat() {
        if (isExplicitStop) return
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getService(
            this,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = SystemClock.elapsedRealtime() + HEARTBEAT_INTERVAL_MS

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling heartbeat alarm", e)
        }
    }

    private fun cancelHeartbeatAlarm() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(this, LocationForegroundService::class.java).apply {
            action = ACTION_HEARTBEAT
        }
        val pendingIntent = PendingIntent.getService(
            this,
            999,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun performHeartbeat() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoonOrbit:HeartbeatWakeLock")
        wakeLock?.acquire(15000L) // Auto release after 15s

        serviceScope.launch {
            try {
                val token = prefsManager.sessionToken.firstOrNull() ?: return@launch
                val serverUrl = prefsManager.serverUrl.firstOrNull() ?: PreferencesManager.DEFAULT_SERVER_URL
                api.setBaseUrl(serverUrl)
                api.setAuthToken(token)

                val (batteryLevel, isCharging) = getBatteryInfo()
                api.sendHeartbeat(batteryLevel, isCharging)
                lastLocationSentTime = System.currentTimeMillis()
                Log.d(TAG, "Keep-alive heartbeat sent to server successfully")

                // Flush pending offline location points
                val pending = queueManager.getPendingPoints()
                if (pending.isNotEmpty()) {
                    api.sendLocation(pending)
                    queueManager.clear()
                    Log.d(TAG, "Pending queue flushed during heartbeat (${pending.size} points)")
                }

                // Check and reconnect WebSocket if needed
                if (!ws.isConnected()) {
                    val wsUrl = PreferencesManager.httpToWsUrl(serverUrl)
                    ws.setWsUrl(wsUrl)
                    ws.connect(token)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send keep-alive heartbeat", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {}
            }
        }
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

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val emergencyChannel = NotificationChannel(
                CHANNEL_EMERGENCY_ID,
                getString(R.string.emergency_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.emergency_channel_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                setSound(soundUri, audioAttributes)
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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

        // 4-Level Priority:
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(EMERGENCY_NOTIFICATION_ID, notif)
    }

    private fun cancelEmergencyNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(EMERGENCY_NOTIFICATION_ID)
    }

    @SuppressLint("MissingPermission")
    private fun setupLocationUpdates() {
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            60 * 1000L // 60 seconds interval
        ).apply {
            setMinUpdateDistanceMeters(5f) // 5 meters movement trigger
            setMinUpdateIntervalMillis(30 * 1000L)
            setWaitForAccurateLocation(false)
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
                    lastKnownLocation = loc
                    evaluateNotificationState()
                    onNewLocation(loc)
                }
            }

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        Log.d(TAG, "Initial currentLocation reported immediately: ${loc.latitude}, ${loc.longitude}")
                        lastKnownLocation = loc
                        evaluateNotificationState()
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
        lastKnownLocation = location
        evaluateNotificationState()

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

        if (!isExplicitStop) {
            Log.w(TAG, "Service destroyed unexpectedly. Re-arming alarm to revive background service.")
            scheduleNextHeartbeat()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

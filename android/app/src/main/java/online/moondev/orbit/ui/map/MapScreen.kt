package online.moondev.orbit.ui.map

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import java.util.Locale
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.GroupMemberDto
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.MoonOrbitWebSocket
import online.moondev.orbit.data.api.WsEvent
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.service.LocationForegroundService
import online.moondev.orbit.theme.*

// Helper functions for distance
private fun calculateDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
    val results = FloatArray(1)
    android.location.Location.distanceBetween(lat1, lng1, lat2, lng2, results)
    return results[0]
}

private fun formatDistance(meters: Float): String {
    return if (meters < 1000f) {
        "${meters.toInt()} m"
    } else {
        String.format(Locale.US, "%.1f km", meters / 1000f)
    }
}

// Dark Map Style JSON (Clean, low contrast dark palette for OLED/Space aesthetic with clear administrative boundaries and city hierarchy)
const val DARK_MAP_STYLE_JSON = """
[
  { "elementType": "geometry", "stylers": [ { "color": "#0d1524" } ] },
  { "elementType": "labels.text.fill", "stylers": [ { "color": "#94a3b8" } ] },
  { "elementType": "labels.text.stroke", "stylers": [ { "color": "#060913" } ] },
  { "featureType": "administrative.country", "elementType": "geometry.stroke", "stylers": [ { "color": "#475569" }, { "weight": 1.5 } ] },
  { "featureType": "administrative.country", "elementType": "labels.text.fill", "stylers": [ { "color": "#e2e8f0" } ] },
  { "featureType": "administrative.province", "elementType": "geometry.stroke", "stylers": [ { "color": "#334155" }, { "weight": 1.0 } ] },
  { "featureType": "administrative.province", "elementType": "labels.text.fill", "stylers": [ { "color": "#94a3b8" } ] },
  { "featureType": "administrative.locality", "elementType": "labels.text.fill", "stylers": [ { "color": "#cbd5e1" } ] },
  { "featureType": "road", "elementType": "geometry", "stylers": [ { "color": "#16233a" } ] },
  { "featureType": "road", "elementType": "geometry.stroke", "stylers": [ { "color": "#1e293b" } ] },
  { "featureType": "water", "elementType": "geometry", "stylers": [ { "color": "#050b14" } ] },
  { "featureType": "water", "elementType": "labels.text.fill", "stylers": [ { "color": "#3b82f6" } ] },
  { "featureType": "poi", "stylers": [ { "visibility": "off" } ] }
]
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    api: MoonOrbitApi,
    ws: MoonOrbitWebSocket,
    prefs: PreferencesManager,
    groupId: String,
    onNavigateToAdmin: () -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("Mi Familia") }
    var userRole by remember { mutableStateOf("member") }
    var currentUserId by remember { mutableStateOf("") }
    var isSharingPaused by remember { mutableStateOf(false) }
    var isSosActive by remember { mutableStateOf(false) }
    var showSosDialog by remember { mutableStateOf(false) }
    val favMemberId by prefs.favoriteMemberId.collectAsState(initial = null)

    var members by remember { mutableStateOf<List<GroupMemberDto>>(emptyList()) }
    var selectedMember by remember { mutableStateOf<GroupMemberDto?>(null) }

    val defaultPosition = LatLng(-12.0464, -77.0428) // Lima default
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(defaultPosition, 14f)
    }

    val context = LocalContext.current

    // Request immediate local GPS fix to center the camera instantly
    LaunchedEffect(Unit) {
        try {
            val fused = LocationServices.getFusedLocationProviderClient(context)
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(
                        LatLng(loc.latitude, loc.longitude),
                        15f
                    )
                }
            }
        } catch (e: SecurityException) {}
    }

    // Load initial snapshot and user prefs
    LaunchedEffect(groupId) {
        prefs.groupName.collectLatest { if (it != null) groupName = it }
    }
    LaunchedEffect(Unit) {
        prefs.role.collectLatest { if (it != null) userRole = it }
    }
    LaunchedEffect(Unit) {
        prefs.userId.collectLatest { if (it != null) currentUserId = it }
    }

    LaunchedEffect(groupId) {
        try {
            val snap = api.getSnapshot(groupId)
            members = snap.members
            groupName = snap.group.name

            val myUserId = prefs.userId.firstOrNull() ?: currentUserId
            val self = snap.members.find { it.userId == myUserId }
            val selfSt = self?.memberState
            if (selfSt != null) {
                val lat = selfSt.lat
                val lng = selfSt.lng
                if (lat != null && lng != null) {
                    cameraPositionState.position = CameraPosition.fromLatLngZoom(
                        LatLng(lat, lng),
                        15f
                    )
                }
            }
        } catch (e: Exception) {}
    }

    // Listen to real-time WebSocket events
    LaunchedEffect(Unit) {
        ws.events.collectLatest { event ->
            when (event) {
                is WsEvent.Snapshot -> {
                    members = event.snapshot.members
                    groupName = event.snapshot.group.name
                }
                is WsEvent.MemberState -> {
                    members = members.map { m ->
                        if (m.userId == event.userId) {
                            val curState = m.memberState
                            m.copy(
                                state = curState.copy(
                                    status = event.status,
                                    lat = event.lat ?: curState.lat,
                                    lng = event.lng ?: curState.lng,
                                    accuracyM = event.accuracyM ?: curState.accuracyM,
                                    batteryLevel = event.batteryLevel ?: curState.batteryLevel,
                                    isCharging = event.isCharging ?: curState.isCharging,
                                    recordedAt = event.recordedAt ?: curState.recordedAt,
                                    lastSeenAt = event.lastSeenAt ?: curState.lastSeenAt
                                )
                            )
                        } else m
                    }
                }
                is WsEvent.MemberJoined -> {
                    if (members.none { it.userId == event.member.userId }) {
                        members = members + event.member
                    }
                }
                is WsEvent.MemberLeft -> {
                    members = members.filter { it.userId != event.userId }
                }
                is WsEvent.GroupUpdated -> {
                    groupName = event.name
                }
                else -> {}
            }
        }
    }

    LaunchedEffect(members, currentUserId) {
        val self = members.find { it.userId == currentUserId }
        if (self != null) {
            isSosActive = self.memberState.status == "sos"
            isSharingPaused = self.memberState.status == "paused"
        }
    }

    LaunchedEffect(members, favMemberId, isSosActive, currentUserId) {
        val self = members.find { it.userId == currentUserId }
        val myLat = self?.memberState?.lat
        val myLng = self?.memberState?.lng

        // 1. Favorited focus member
        val favMember = members.find { it.userId == favMemberId }
        val favName = favMember?.displayName
        val favBattery = favMember?.memberState?.batteryLevel
        val favDist = if (favMember != null && myLat != null && myLng != null &&
            favMember.memberState.lat != null && favMember.memberState.lng != null
        ) {
            val distM = calculateDistanceMeters(myLat, myLng, favMember.memberState.lat!!, favMember.memberState.lng!!)
            formatDistance(distM)
        } else null

        // 2. Emergency alert check (another member is in SOS)
        val emergencyMember = members.find { it.userId != currentUserId && it.memberState.status == "sos" }
        val emergencyName = emergencyMember?.displayName
        val emergencyDist = if (emergencyMember != null && myLat != null && myLng != null &&
            emergencyMember.memberState.lat != null && emergencyMember.memberState.lng != null
        ) {
            val distM = calculateDistanceMeters(myLat, myLng, emergencyMember.memberState.lat!!, emergencyMember.memberState.lng!!)
            formatDistance(distM)
        } else null

        val intent = Intent(context, LocationForegroundService::class.java).apply {
            action = LocationForegroundService.ACTION_UPDATE_NOTIF_INFO
            putExtra(LocationForegroundService.EXTRA_SELF_SOS, isSosActive)
            putExtra(LocationForegroundService.EXTRA_FAV_ID, favMemberId)
            putExtra(LocationForegroundService.EXTRA_FAV_NAME, favName)
            putExtra(LocationForegroundService.EXTRA_FAV_DIST, favDist)
            if (favBattery != null) {
                putExtra(LocationForegroundService.EXTRA_FAV_BATTERY, favBattery)
            }
            putExtra(LocationForegroundService.EXTRA_EMERGENCY_NAME, emergencyName)
            putExtra(LocationForegroundService.EXTRA_EMERGENCY_DIST, emergencyDist)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {}
    }

    val bottomSheetScaffoldState = rememberBottomSheetScaffoldState()

    BottomSheetScaffold(
        scaffoldState = bottomSheetScaffoldState,
        sheetPeekHeight = 140.dp,
        sheetContainerColor = DarkSurface,
        sheetContentColor = DarkTextPrimary,
        sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = groupName,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = DarkTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = "${members.size} miembros activos",
                            style = MaterialTheme.typography.bodySmall.copy(color = DarkTextSecondary)
                        )
                    }
                },
                actions = {
                    // SOS Emergency Button
                    IconButton(onClick = { showSosDialog = true }) {
                        Icon(
                            imageVector = if (isSosActive) Icons.Default.Warning else Icons.Default.Sos,
                            contentDescription = "Alerta SOS",
                            tint = if (isSosActive) DarkError else DarkTextSecondary
                        )
                    }

                    // Pause Sharing Toggle
                    IconButton(onClick = {
                        val newPaused = !isSharingPaused
                        isSharingPaused = newPaused
                        scope.launch {
                            try {
                                api.updateStatus(if (newPaused) "paused" else "active", null, null)
                            } catch (e: Exception) {}
                        }
                    }) {
                        Icon(
                            imageVector = if (isSharingPaused) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                            contentDescription = "Pausar ubicación",
                            tint = if (isSharingPaused) DarkWarning else DarkSuccess
                        )
                    }

                    // Admin Pending/Invite Screen Button (only for admin)
                    if (userRole == "admin") {
                        IconButton(onClick = onNavigateToAdmin) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = "Panel de Administrador",
                                tint = DarkPrimary
                            )
                        }
                    }

                    // Profile Button
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Mi Perfil",
                            tint = DarkTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        sheetContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                // Drag handle indicator
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DarkBorder)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Miembros de la Familia",
                    style = MaterialTheme.typography.titleMedium.copy(
                        color = DarkTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(members) { member ->
                        val isSelf = member.userId == currentUserId
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedMember = member
                                    val st = member.memberState
                                    val lat = st.lat
                                    val lng = st.lng
                                    if (lat != null && lng != null) {
                                        cameraPositionState.position = CameraPosition.fromLatLngZoom(
                                            LatLng(lat, lng),
                                            16f
                                        )
                                    }
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelf) DarkSurfaceVariant else DarkBackground
                            ),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val memberSt = member.memberState
                                // Avatar circle with initials
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (member.role == "admin") DarkPrimary.copy(alpha = 0.2f)
                                            else DarkTertiary.copy(alpha = 0.2f)
                                        )
                                        .border(
                                            2.dp,
                                            when (memberSt.status) {
                                                "sos" -> DarkError
                                                "active" -> DarkSuccess
                                                "paused" -> DarkWarning
                                                "location_disabled" -> DarkError
                                                else -> DarkTextSecondary
                                            },
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (member.avatarVersion > 0) {
                                        AsyncImage(
                                            model = api.getAvatarUrl(member.userId, member.avatarVersion),
                                            contentDescription = member.displayName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = member.displayName.take(1).uppercase(),
                                            color = DarkTextPrimary,
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                val self = members.find { it.userId == currentUserId }
                                val selfSt = self?.memberState
                                val myLat = selfSt?.lat
                                val myLng = selfSt?.lng
                                val distanceText = if (!isSelf && myLat != null && myLng != null && memberSt.lat != null && memberSt.lng != null) {
                                    val distM = calculateDistanceMeters(myLat, myLng, memberSt.lat, memberSt.lng)
                                    formatDistance(distM)
                                } else null

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (isSelf) "${member.displayName} (Tú)" else member.displayName,
                                            color = DarkTextPrimary,
                                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        if (member.role == "admin") {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(DarkPrimary.copy(alpha = 0.2f))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "Admin",
                                                    color = DarkPrimary,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
                                                )
                                            }
                                        }
                                        if (memberSt.status == "sos") {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = DarkError,
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "SOS",
                                                    color = DarkTextPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = when (memberSt.status) {
                                                "sos" -> "¡ALERTA SOS!"
                                                "active" -> "En línea"
                                                "paused" -> "En pausa"
                                                "location_disabled" -> "GPS apagado"
                                                else -> "Sin señal"
                                            },
                                            color = when (memberSt.status) {
                                                "sos" -> DarkError
                                                "active" -> DarkSuccess
                                                "paused" -> DarkWarning
                                                "location_disabled" -> DarkError
                                                else -> DarkTextSecondary
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )

                                        if (distanceText != null) {
                                            Text(
                                                text = " • A $distanceText",
                                                color = DarkPrimary,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                                            )
                                        }
                                    }
                                }

                                // Battery percentage and Focus / Favorite heart button
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (memberSt.batteryLevel != null) {
                                        Icon(
                                            imageVector = if (memberSt.isCharging == true) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                                            contentDescription = null,
                                            tint = if (memberSt.batteryLevel <= 15) DarkError else DarkSuccess,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "${memberSt.batteryLevel}%",
                                            color = DarkTextSecondary,
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                        )
                                    }

                                    if (!isSelf) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val isFav = favMemberId == member.userId
                                        IconButton(
                                            onClick = {
                                                scope.launch {
                                                    if (isFav) {
                                                        prefs.setFavoriteMemberId(null)
                                                    } else {
                                                        prefs.setFavoriteMemberId(member.userId)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = if (isFav) "Dejar de seguir en la barra de estado" else "Seguir en la barra de estado",
                                                tint = if (isFav) DarkError else DarkTextSecondary.copy(alpha = 0.5f),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = false,
                    myLocationButtonEnabled = false,
                    compassEnabled = true
                ),
                properties = MapProperties(
                    mapStyleOptions = MapStyleOptions(DARK_MAP_STYLE_JSON)
                )
            ) {
                // Render custom avatar markers for all members who have coordinates
                members.forEach { m ->
                    key(m.userId) {
                        MemberMapMarker(
                            member = m,
                            api = api,
                            currentUserId = currentUserId,
                            onSelect = { selectedMember = it }
                        )
                    }
                }
            }

            // Floating Action Buttons (Recenter & Fit All Family)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 150.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Button to frame/fit all family members on the map
                SmallFloatingActionButton(
                    onClick = {
                        val activeWithCoords = members.filter { it.memberState.lat != null && it.memberState.lng != null }
                        if (activeWithCoords.isNotEmpty()) {
                            if (activeWithCoords.size == 1) {
                                val first = activeWithCoords.first().memberState
                                cameraPositionState.position = CameraPosition.fromLatLngZoom(
                                    LatLng(first.lat!!, first.lng!!),
                                    15f
                                )
                            } else {
                                val builder = LatLngBounds.builder()
                                activeWithCoords.forEach { m ->
                                    val st = m.memberState
                                    builder.include(LatLng(st.lat!!, st.lng!!))
                                }
                                val bounds = builder.build()
                                scope.launch {
                                    try {
                                        cameraPositionState.animate(
                                            CameraUpdateFactory.newLatLngBounds(bounds, 150)
                                        )
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    },
                    containerColor = DarkSurfaceVariant,
                    contentColor = DarkPrimary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.People, contentDescription = "Ver a toda la familia")
                }

                // Recenter on My Location
                FloatingActionButton(
                    onClick = {
                        val self = members.find { it.userId == currentUserId }
                        val selfSt = self?.memberState
                        val lat = selfSt?.lat
                        val lng = selfSt?.lng
                        if (lat != null && lng != null) {
                            cameraPositionState.position = CameraPosition.fromLatLngZoom(
                                LatLng(lat, lng),
                                16f
                            )
                        } else {
                            try {
                                val fused = LocationServices.getFusedLocationProviderClient(context)
                                fused.lastLocation.addOnSuccessListener { loc ->
                                    if (loc != null) {
                                        cameraPositionState.position = CameraPosition.fromLatLngZoom(
                                            LatLng(loc.latitude, loc.longitude),
                                            16f
                                        )
                                    }
                                }
                            } catch (e: SecurityException) {}
                        }
                    },
                    containerColor = DarkPrimary,
                    contentColor = DarkBackground,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Mi Ubicación")
                }
            }
        }
    }

    // SOS EMERGENCY DIALOG
    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = {
                Text(
                    text = if (isSosActive) "¿Desactivar Alerta SOS?" else "¿Activar Alerta de Emergencia SOS?",
                    color = if (isSosActive) DarkTextPrimary else DarkError,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isSosActive) {
                        "Se notificará a toda tu familia que la situación de emergencia ha finalizado y tu marcador volverá a la normalidad."
                    } else {
                        "Tu ubicación se resaltará en rojo en los dispositivos de toda tu familia con alta prioridad para que sepan que requieres auxilio inmediato."
                    },
                    color = DarkTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val newSos = !isSosActive
                        isSosActive = newSos
                        showSosDialog = false
                        scope.launch {
                            try {
                                api.updateStatus(if (newSos) "sos" else "active", null, null)
                            } catch (e: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSosActive) DarkPrimary else DarkError
                    )
                ) {
                    Text(
                        text = if (isSosActive) "Desactivar SOS" else "Activar SOS",
                        color = if (isSosActive) DarkBackground else DarkTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
fun MemberMapMarker(
    member: GroupMemberDto,
    api: MoonOrbitApi,
    currentUserId: String,
    onSelect: (GroupMemberDto) -> Unit
) {
    val context = LocalContext.current
    val st = member.memberState
    val lat = st.lat ?: return
    val lng = st.lng ?: return
    val isSos = st.status == "sos"
    val isSelf = member.userId == currentUserId

    val mPos = remember(lat, lng) { LatLng(lat, lng) }
    val markerState = remember(member.userId) { MarkerState(mPos) }
    markerState.position = mPos

    var markerIcon by remember(member.userId, member.avatarVersion, st.status, isSos, isSelf) {
        mutableStateOf(
            AvatarMarkerHelper.createMarkerIcon(
                context = context,
                displayName = member.displayName,
                avatarBitmap = null,
                isSos = isSos,
                isSelf = isSelf,
                status = st.status
            )
        )
    }

    LaunchedEffect(member.userId, member.avatarVersion, isSos, isSelf, st.status) {
        if (member.avatarVersion > 0) {
            try {
                val req = coil.request.ImageRequest.Builder(context)
                    .data(api.getAvatarUrl(member.userId, member.avatarVersion))
                    .allowHardware(false)
                    .build()
                val result = coil.Coil.imageLoader(context).execute(req)
                val drawable = result.drawable
                if (drawable is android.graphics.drawable.BitmapDrawable) {
                    markerIcon = AvatarMarkerHelper.createMarkerIcon(
                        context = context,
                        displayName = member.displayName,
                        avatarBitmap = drawable.bitmap,
                        isSos = isSos,
                        isSelf = isSelf,
                        status = st.status
                    )
                }
            } catch (e: Exception) {}
        }
    }

    Marker(
        state = markerState,
        title = member.displayName,
        icon = markerIcon,
        anchor = Offset(0.5f, 1.0f),
        snippet = if (isSos) "¡ALERTA SOS ACTIVA! | Batería: ${st.batteryLevel ?: "?"}%"
                  else "Estado: ${st.status} | Batería: ${st.batteryLevel ?: "?"}%",
        onClick = {
            onSelect(member)
            false
        }
    )

    // Accuracy circle
    if (st.accuracyM != null && st.accuracyM > 0) {
        Circle(
            center = LatLng(lat, lng),
            radius = if (isSos) maxOf(st.accuracyM, 35.0) else st.accuracyM,
            fillColor = if (isSos) DarkError.copy(alpha = 0.25f) else DarkPrimary.copy(alpha = 0.15f),
            strokeColor = if (isSos) DarkError else DarkPrimary.copy(alpha = 0.5f),
            strokeWidth = if (isSos) 3f else 2f
        )
    }
}

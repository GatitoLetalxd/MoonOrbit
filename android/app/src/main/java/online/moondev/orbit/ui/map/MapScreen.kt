package online.moondev.orbit.ui.map

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.GroupMemberDto
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.MoonOrbitWebSocket
import online.moondev.orbit.data.api.WsEvent
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.theme.*

// Dark Map Style JSON (Clean, low contrast dark palette for OLED/Space aesthetic)
const val DARK_MAP_STYLE_JSON = """
[
  { "elementType": "geometry", "stylers": [ { "color": "#0d1524" } ] },
  { "elementType": "labels.text.fill", "stylers": [ { "color": "#94a3b8" } ] },
  { "elementType": "labels.text.stroke", "stylers": [ { "color": "#060913" } ] },
  { "featureType": "road", "elementType": "geometry", "stylers": [ { "color": "#16233a" } ] },
  { "featureType": "road", "elementType": "geometry.stroke", "stylers": [ { "color": "#1e293b" } ] },
  { "featureType": "water", "elementType": "geometry", "stylers": [ { "color": "#050b14" } ] },
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
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = when (memberSt.status) {
                                            "active" -> "En línea"
                                            "paused" -> "Ubicación en pausa"
                                            "location_disabled" -> "GPS apagado"
                                            else -> "Sin señal reciente"
                                        },
                                        color = when (memberSt.status) {
                                            "active" -> DarkSuccess
                                            "paused" -> DarkWarning
                                            "location_disabled" -> DarkError
                                            else -> DarkTextSecondary
                                        },
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                // Battery percentage
                                if (memberSt.batteryLevel != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                // Render markers for all members who have coordinates
                members.forEach { m ->
                    val st = m.memberState
                    val lat = st.lat
                    val lng = st.lng
                    if (lat != null && lng != null) {
                        Marker(
                            state = MarkerState(position = LatLng(lat, lng)),
                            title = m.displayName,
                            snippet = "Estado: ${st.status} | Batería: ${st.batteryLevel ?: "?"}%"
                        )

                        // Accuracy circle
                        if (st.accuracyM != null && st.accuracyM > 0) {
                            Circle(
                                center = LatLng(lat, lng),
                                radius = st.accuracyM,
                                fillColor = DarkPrimary.copy(alpha = 0.15f),
                                strokeColor = DarkPrimary.copy(alpha = 0.5f),
                                strokeWidth = 2f
                            )
                        }
                    }
                }
            }

            // Recenter Floating Action Button
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
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 150.dp),
                containerColor = DarkPrimary,
                contentColor = DarkBackground,
                shape = CircleShape
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Mi Ubicación")
            }
        }
    }
}

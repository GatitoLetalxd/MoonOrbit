package online.moondev.orbit.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.MoonOrbitWebSocket
import online.moondev.orbit.data.api.WsEvent
import online.moondev.orbit.data.local.PreferencesManager
import online.moondev.orbit.theme.*

@Composable
fun PendingApprovalScreen(
    api: MoonOrbitApi,
    ws: MoonOrbitWebSocket,
    prefs: PreferencesManager,
    onApproved: () -> Unit,
    onLogout: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(false) }

    // Listen for WebSocket approval event in real-time
    LaunchedEffect(Unit) {
        ws.events.collectLatest { event ->
            if (event is WsEvent.MemberApproved) {
                prefs.updateGroup(event.groupId, null, "member", "active")
                onApproved()
            }
        }
    }

    fun checkStatus() {
        isChecking = true
        scope.launch {
            try {
                val me = api.getMe()
                if (me.group != null && me.group.status == "active") {
                    prefs.updateGroup(me.group.id, me.group.name, me.group.role, "active")
                    onApproved()
                } else {
                    android.widget.Toast.makeText(context, "Tu solicitud sigue en revisión por el admin.", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
            isChecking = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(DarkWarning.copy(alpha = 0.15f))
                    .border(2.dp, DarkWarning, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.HourglassTop,
                    contentDescription = null,
                    tint = DarkWarning,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Esperando Aprobación",
                style = MaterialTheme.typography.headlineMedium.copy(
                    color = DarkTextPrimary,
                    fontWeight = FontWeight.Bold
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Tu solicitud ha sido enviada al administrador del grupo familiar. En cuanto te apruebe, accederás automáticamente al mapa en tiempo real.",
                style = MaterialTheme.typography.bodyMedium.copy(color = DarkTextSecondary),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedButton(
                onClick = { checkStatus() },
                enabled = !isChecking,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkPrimary),
                border = BorderStroke(1.dp, DarkPrimary)
            ) {
                if (isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = DarkPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verificar estado ahora")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                onClick = {
                    scope.launch {
                        try { api.logout() } catch (e: Exception) {}
                        prefs.clearSession()
                        onLogout()
                    }
                }
            ) {
                Text(
                    text = "Cerrar sesión",
                    color = DarkError,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

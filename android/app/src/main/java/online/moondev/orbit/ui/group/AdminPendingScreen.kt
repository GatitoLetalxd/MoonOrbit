package online.moondev.orbit.ui.group

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.MoonOrbitApi
import online.moondev.orbit.data.api.PendingMemberDto
import online.moondev.orbit.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPendingScreen(
    api: MoonOrbitApi,
    groupId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var inviteCode by remember { mutableStateOf("...") }
    var expiresAt by remember { mutableStateOf("...") }
    var pendingMembers by remember { mutableStateOf<List<PendingMemberDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    fun loadData() {
        isLoading = true
        scope.launch {
            try {
                val inv = api.getInviteCode(groupId)
                inviteCode = inv.inviteCode
                expiresAt = inv.expiresAt

                val p = api.getPendingMembers(groupId)
                pendingMembers = p.pending
            } catch (e: Exception) {}
            isLoading = false
        }
    }

    LaunchedEffect(groupId) {
        loadData()
    }

    fun copyCodeToClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("MoonOrbit Invite", inviteCode)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Código copiado: $inviteCode", Toast.LENGTH_SHORT).show()
    }

    fun regenerateCode() {
        scope.launch {
            try {
                val res = api.regenerateInviteCode(groupId)
                inviteCode = res.inviteCode
                expiresAt = res.expiresAt
                Toast.makeText(context, "Nuevo código generado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administración del Grupo", color = DarkTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = DarkTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
        ) {
            // Invite Code Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, DarkPrimary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Código de Invitación Vigente",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = DarkTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Comparte este código con tus familiares para que soliciten unirse al grupo.",
                        style = MaterialTheme.typography.bodySmall.copy(color = DarkTextSecondary)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = inviteCode,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = DarkPrimary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.sp
                            )
                        )

                        Row {
                            IconButton(onClick = { copyCodeToClipboard() }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = DarkPrimary)
                            }
                            IconButton(onClick = { regenerateCode() }) {
                                Icon(Icons.Default.Autorenew, contentDescription = "Regenerar", tint = DarkSecondary)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Solicitudes Pendientes (${pendingMembers.size})",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = DarkTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (pendingMembers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No hay solicitudes pendientes en este momento.",
                        color = DarkTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pendingMembers) { member ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = member.displayName,
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            color = DarkTextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Text(
                                        text = "@${member.username}",
                                        style = MaterialTheme.typography.bodySmall.copy(color = DarkTextSecondary)
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    api.approveMember(groupId, member.userId)
                                                    loadData()
                                                    Toast.makeText(context, "Aprobado", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = "Aprobar", tint = DarkSuccess)
                                    }

                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    api.rejectMember(groupId, member.userId)
                                                    loadData()
                                                    Toast.makeText(context, "Rechazado", Toast.LENGTH_SHORT).show()
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = "Rechazar", tint = DarkError)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import online.moondev.orbit.data.api.GroupMemberDto
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

    var selectedTab by remember { mutableIntStateOf(0) }
    var groupName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("...") }
    var expiresAt by remember { mutableStateOf("...") }
    var activeMembers by remember { mutableStateOf<List<GroupMemberDto>>(emptyList()) }
    var pendingMembers by remember { mutableStateOf<List<PendingMemberDto>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }

    // Dialog States
    var memberToKick by remember { mutableStateOf<GroupMemberDto?>(null) }
    var memberToResetPass by remember { mutableStateOf<GroupMemberDto?>(null) }
    var tempPasswordResult by remember { mutableStateOf<String?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newGroupNameInput by remember { mutableStateOf("") }

    fun loadData() {
        isLoading = true
        scope.launch {
            try {
                val inv = api.getInviteCode(groupId)
                inviteCode = inv.inviteCode
                expiresAt = inv.expiresAt

                val p = api.getPendingMembers(groupId)
                pendingMembers = p.pending

                val snap = api.getSnapshot(groupId)
                groupName = snap.group.name
                activeMembers = snap.members
            } catch (e: Exception) {
                // Silently keep cached data if network hiccups
            }
            isLoading = false
        }
    }

    LaunchedEffect(groupId) {
        loadData()
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copiado al portapapeles", Toast.LENGTH_SHORT).show()
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

    fun executeKickMember(member: GroupMemberDto) {
        scope.launch {
            try {
                api.removeMember(groupId, member.userId)
                Toast.makeText(context, "${member.displayName} ha sido expulsado", Toast.LENGTH_SHORT).show()
                memberToKick = null
                loadData()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun executeResetPassword(member: GroupMemberDto) {
        scope.launch {
            try {
                val res = api.resetMemberPassword(groupId, member.userId)
                tempPasswordResult = res.temporaryPassword
                memberToResetPass = null
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun executeRenameGroup() {
        val trimmed = newGroupNameInput.trim()
        if (trimmed.length < 2) {
            Toast.makeText(context, "El nombre debe tener al menos 2 caracteres", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            try {
                val res = api.renameGroup(groupId, trimmed)
                groupName = res.group.name
                showRenameDialog = false
                Toast.makeText(context, "Nombre del grupo actualizado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (groupName.isNotEmpty()) groupName else "Administración del Grupo",
                            color = DarkTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Panel de control familiar",
                            color = DarkTextSecondary,
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás", tint = DarkTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { loadData() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Recargar", tint = DarkPrimary)
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
        ) {
            // Tab Navigation
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBackground,
                contentColor = DarkPrimary,
                indicator = {
                    TabRowDefaults.PrimaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(selectedTab),
                        color = DarkPrimary
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            "Miembros (${activeMembers.size}/12)",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 0) DarkPrimary else DarkTextSecondary
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Solicitudes",
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == 1) DarkPrimary else DarkTextSecondary
                            )
                            if (pendingMembers.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Badge(containerColor = DarkError) {
                                    Text(
                                        "${pendingMembers.size}",
                                        color = DarkTextPrimary,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = {
                        Text(
                            "Ajustes",
                            fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal,
                            color = if (selectedTab == 2) DarkPrimary else DarkTextSecondary
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // TAB 0: MIEMBROS ACTIVOS
            if (selectedTab == 0) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Text(
                            text = "Miembros activos en la familia",
                            color = DarkTextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }

                    items(activeMembers) { member ->
                        val isAdmin = member.role.equals("admin", ignoreCase = true)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (isAdmin) DarkPrimary.copy(alpha = 0.4f) else DarkBorder, RoundedCornerShape(16.dp)),
                            colors = CardDefaults.cardColors(containerColor = DarkSurface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Avatar
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(DarkSurfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val avatarUrl = api.getAvatarUrl(member.userId, member.avatarVersion)
                                    if (member.avatarVersion > 0) {
                                        AsyncImage(
                                            model = avatarUrl,
                                            contentDescription = member.displayName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text(
                                            text = member.displayName.firstOrNull()?.toString()?.uppercase() ?: "U",
                                            color = DarkPrimary,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 20.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = member.displayName,
                                            color = DarkTextPrimary,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (isAdmin) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = DarkPrimary.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "ADMIN",
                                                    color = DarkPrimary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = "@${member.username ?: "usuario"}",
                                        color = DarkTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }

                                // Actions for non-admin members
                                if (!isAdmin) {
                                    Row {
                                        IconButton(
                                            onClick = { memberToResetPass = member }
                                        ) {
                                            Icon(
                                                Icons.Default.VpnKey,
                                                contentDescription = "Resetear Contraseña",
                                                tint = DarkWarning
                                            )
                                        }
                                        IconButton(
                                            onClick = { memberToKick = member }
                                        ) {
                                            Icon(
                                                Icons.Default.PersonRemove,
                                                contentDescription = "Expulsar Miembro",
                                                tint = DarkError
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(30.dp))
                    }
                }
            }

            // TAB 1: SOLICITUDES PENDIENTES
            if (selectedTab == 1) {
                if (pendingMembers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.CheckCircleOutline,
                                contentDescription = null,
                                tint = DarkTextSecondary,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No hay solicitudes pendientes",
                                color = DarkTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Cuando alguien ingrese tu código de invitación, aparecerá aquí para tu aprobación.",
                                color = DarkTextSecondary,
                                fontSize = 13.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = "Solicitudes en espera de aprobación",
                                color = DarkTextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

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

            // TAB 2: AJUSTES Y CÓDIGO DE INVITACIÓN
            if (selectedTab == 2) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Group Name Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Nombre del Grupo Familiar",
                                color = DarkTextSecondary,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = if (groupName.isNotEmpty()) groupName else "Mi Familia",
                                    color = DarkTextPrimary,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                OutlinedButton(
                                    onClick = {
                                        newGroupNameInput = groupName
                                        showRenameDialog = true
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DarkPrimary)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cambiar")
                                }
                            }
                        }
                    }

                    // Invite Code Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkPrimary.copy(alpha = 0.3f), RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurface),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text(
                                text = "Código de Invitación Vigente",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = DarkTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Comparte este código para que tus familiares soliciten unirse.",
                                style = MaterialTheme.typography.bodySmall.copy(color = DarkTextSecondary)
                            )

                            Spacer(modifier = Modifier.height(14.dp))

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
                                    IconButton(onClick = { copyToClipboard("Código de invitación", inviteCode) }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = DarkPrimary)
                                    }
                                    IconButton(onClick = { regenerateCode() }) {
                                        Icon(Icons.Default.Autorenew, contentDescription = "Regenerar", tint = DarkSecondary)
                                    }
                                }
                            }
                        }
                    }

                    // Information Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, DarkBorder.copy(alpha = 0.5f), RoundedCornerShape(20.dp)),
                        colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Shield, contentDescription = null, tint = DarkPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Seguridad y Privacidad MoonOrbit", color = DarkTextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "• Capacidad máxima de 12 miembros activos.\n" +
                                        "• Sin historial almacenado en base de datos: solo se transmite la última posición conocida en tiempo real.\n" +
                                        "• Al expulsar a un miembro se revocan de inmediato sus credenciales y accesos al mapa.",
                                color = DarkTextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // DIALOG: CONFIRM KICK MEMBER
    memberToKick?.let { member ->
        AlertDialog(
            onDismissRequest = { memberToKick = null },
            title = {
                Text("¿Expulsar a ${member.displayName}?", color = DarkTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Esta acción revocará inmediatamente todas sus sesiones activas, desconectará su transmisión en vivo y lo removerá del grupo familiar.",
                    color = DarkTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { executeKickMember(member) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkError)
                ) {
                    Text("Expulsar", color = DarkTextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToKick = null }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // DIALOG: CONFIRM RESET PASSWORD
    memberToResetPass?.let { member ->
        AlertDialog(
            onDismissRequest = { memberToResetPass = null },
            title = {
                Text("¿Restablecer contraseña?", color = DarkTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Se generará una contraseña temporal para ${member.displayName} y se cerrarán sus sesiones actuales para que inicie sesión nuevamente.",
                    color = DarkTextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { executeResetPassword(member) },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkWarning)
                ) {
                    Text("Generar Clave", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToResetPass = null }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }

    // DIALOG: SHOW TEMPORARY PASSWORD RESULT
    tempPasswordResult?.let { tempPass ->
        AlertDialog(
            onDismissRequest = { tempPasswordResult = null },
            title = {
                Text("Contraseña Temporal Generada", color = DarkSuccess, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Entrega esta contraseña al familiar para que inicie sesión:",
                        color = DarkTextSecondary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tempPass,
                                color = DarkPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = 1.sp
                            )
                            IconButton(onClick = { copyToClipboard("Contraseña temporal", tempPass) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", tint = DarkPrimary)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Al iniciar sesión, la aplicación le pedirá que cree una nueva contraseña personal.",
                        color = DarkTextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { tempPasswordResult = null },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkPrimary)
                ) {
                    Text("Entendido", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface
        )
    }

    // DIALOG: RENAME GROUP
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = {
                Text("Renombrar Grupo Familiar", color = DarkTextPrimary, fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text("Ingresa el nuevo nombre para tu grupo familiar:", color = DarkTextSecondary, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newGroupNameInput,
                        onValueChange = { newGroupNameInput = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DarkTextPrimary,
                            unfocusedTextColor = DarkTextPrimary,
                            focusedBorderColor = DarkPrimary,
                            unfocusedBorderColor = DarkBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { executeRenameGroup() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkPrimary)
                ) {
                    Text("Guardar", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancelar", color = DarkTextSecondary)
                }
            },
            containerColor = DarkSurface
        )
    }
}

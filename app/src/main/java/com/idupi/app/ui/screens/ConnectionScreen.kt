package com.idupi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.model.ConnectionMode
import com.idupi.app.ui.theme.*
import com.idupi.app.viewmodel.ConnectionViewModel

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    viewModel: ConnectionViewModel,
    onBack: () -> Unit,
    onContinue: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val profileName by viewModel.profileName.collectAsState()
    val mode by viewModel.connectionMode.collectAsState()
    val host by viewModel.host.collectAsState()
    val port by viewModel.port.collectAsState()
    val token by viewModel.token.collectAsState()
    val useHttps by viewModel.useHttps.collectAsState()
    val testing by viewModel.testingConnection.collectAsState()
    val testSuccess by viewModel.testSuccess.collectAsState()
    val saveSuccess by viewModel.saveSuccess.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadProfile(context)
    }

    LaunchedEffect(saveSuccess) {
        if (saveSuccess) {
            Toast.makeText(context, "Perfil guardado con éxito", Toast.LENGTH_SHORT).show()
            viewModel.resetSaveStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configuración de Conexión", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Volver", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SlateCard,
                    titleContentColor = TextPrimary
                )
            )
        },
        containerColor = SlateBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Ingresá los datos de tu servidor local",
                color = TextSecondary,
                fontSize = 14.sp
            )

            // 1. Profile Name
            OutlinedTextField(
                value = profileName,
                onValueChange = { viewModel.updateName(it) },
                label = { Text("Nombre del Perfil", color = TextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder,
                    focusedContainerColor = SlateCard,
                    unfocusedContainerColor = SlateCard
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 2. Connection Mode Selector
            Text(text = "Modo de Conexión", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ConnectionMode.values().forEach { itemMode ->
                    val isSelected = mode == itemMode
                    val itemBg = if (isSelected) PrimaryIndigo else SlateCard
                    val itemBorderColor = if (isSelected) PrimaryIndigo else SlateBorder
                    val itemTextColor = if (isSelected) TextPrimary else TextSecondary

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(itemBg)
                            .border(1.dp, itemBorderColor, RoundedCornerShape(8.dp))
                            .clickable { viewModel.updateMode(itemMode) }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when(itemMode) {
                                ConnectionMode.TAILSCALE -> "Tailscale VPN"
                                ConnectionMode.LOCAL_LAN -> "Local / LAN"
                                ConnectionMode.TUNNEL_REMOTE -> "Túnel / Dom"
                            },
                            color = itemTextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Tailscale Help Card (appears when TAILSCALE is selected)
            if (mode == ConnectionMode.TAILSCALE) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentPurple.copy(alpha = 0.1f))
                        .border(1.dp, AccentPurple.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "💡 Conexión vía Tailscale (Sin IP pública)",
                            color = AccentPurple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Asegurate de tener la app de Tailscale activa en Android. Ingresá la IP 100.x.y.z o el nombre MagicDNS de tu máquina (ej: mi-pc.tailnet.ts.net).",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // 3. Host
            OutlinedTextField(
                value = host,
                onValueChange = { viewModel.updateHost(it) },
                label = { Text("Dirección IP / Tailscale Host", color = TextSecondary) },
                placeholder = { Text(if (mode == ConnectionMode.TAILSCALE) "ej. 100.x.y.z o mi-pc.ts.net" else "ej. 192.168.1.50", color = TextSecondary.copy(alpha = 0.5f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder,
                    focusedContainerColor = SlateCard,
                    unfocusedContainerColor = SlateCard
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 4. Port
            OutlinedTextField(
                value = port,
                onValueChange = { viewModel.updatePort(it) },
                label = { Text("Puerto del Orquestador", color = TextSecondary) },
                placeholder = { Text("8787", color = TextSecondary.copy(alpha = 0.5f)) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = PrimaryIndigo,
                    unfocusedBorderColor = SlateBorder,
                    focusedContainerColor = SlateCard,
                    unfocusedContainerColor = SlateCard
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // 5. Token
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { viewModel.updateToken(it) },
                    label = { Text("Token de Seguridad (API Key)", color = TextSecondary) },
                    placeholder = { Text("Clave secreta configurada en tu servidor", color = TextSecondary.copy(alpha = 0.5f)) },
                    visualTransformation = PasswordVisualTransformation(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryIndigo,
                        unfocusedBorderColor = SlateBorder,
                        focusedContainerColor = SlateCard,
                        unfocusedContainerColor = SlateCard
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "🔑 Es la clave de acceso de tu servidor. Si estás en modo demo o local sin contraseña, podés dejar la clave por defecto.",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }

            // 6. Use HTTPS toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Usar HTTPS / WSS", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("🔒 Usar solo si tu servidor tiene un certificado SSL configurado. En Tailscale directo, dejalo DESACTIVADO (usa HTTP/WS).", color = TextSecondary, fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = useHttps,
                    onCheckedChange = { viewModel.updateUseHttps(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = TextPrimary,
                        checkedTrackColor = PrimaryIndigo,
                        uncheckedThumbColor = TextSecondary,
                        uncheckedTrackColor = SlateCard
                    )
                )
            }

    val testErrorMessage by viewModel.testErrorMessage.collectAsState()

    // Status message box for connection test
    testSuccess?.let { success ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (success) StatusConnected.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f))
                .border(1.dp, if (success) StatusConnected else StatusError, RoundedCornerShape(8.dp))
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (success) "✅ ¡Conexión Exitosa con el Servidor!" else "❌ Error al conectar con el Servidor",
                    color = if (success) StatusConnected else StatusError,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                if (!success && !testErrorMessage.isNullOrBlank()) {
                    Text(
                        text = testErrorMessage ?: "No se pudo establecer la conexión de red.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Probar Conexión Button
    OutlinedButton(
        onClick = { viewModel.testConnection() },
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = TextPrimary
        ),
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        if (testing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextPrimary, strokeWidth = 2.dp)
        } else {
            val label = when (testSuccess) {
                true -> "✓ Conexión Exitosa"
                false -> "✗ Reintentar Conexión"
                else -> "Probar Conexión Real (HTTP)"
            }
            val color = when (testSuccess) {
                true -> StatusConnected
                false -> StatusError
                else -> TextPrimary
            }
            Text(label, color = color, fontWeight = FontWeight.Bold)
        }
    }

    // Save and Continue row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = { viewModel.saveProfile(context) },
            colors = ButtonDefaults.buttonColors(
                containerColor = SlateCard,
                contentColor = TextPrimary
            ),
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Guardar", fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = {
                viewModel.saveProfile(context)
                onContinue()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryIndigo,
                contentColor = TextPrimary
            ),
            modifier = Modifier.weight(1f).height(48.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Continuar", fontWeight = FontWeight.Bold)
        }
    }
        }
    }
}

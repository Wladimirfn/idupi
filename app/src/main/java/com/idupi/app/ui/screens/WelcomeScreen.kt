package com.idupi.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.ui.theme.*

@Composable
fun WelcomeScreen(
    onEnterDemo: () -> Unit,
    onConfigureConnection: () -> Unit
) {
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SlateBg)
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(30.dp))

            // Central Glowing Logo and Slogan
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Glow text background effect
                Box(
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "IDUPI",
                        color = PrimaryIndigo.copy(alpha = 0.3f),
                        fontSize = 72.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.offset(y = 4.dp)
                    )
                    Text(
                        text = "IDUPI",
                        color = TextPrimary,
                        fontSize = 68.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    text = "Tu conexión privada a Pi CLI",
                    color = TextSecondary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // Options container card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateCard)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Seleccioná un modo de inicio",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // 1. Enter in Demo Mode
                    Button(
                        onClick = onEnterDemo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Entrar en Modo Demo", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    // 2. Configure Connection
                    Button(
                        onClick = onConfigureConnection,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateBorder,
                            contentColor = TextPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Configurar Conexión", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }

                    // 3. Scan QR (Coming Soon)
                    OutlinedButton(
                        onClick = {
                            Toast.makeText(context, "Escanear QR estará disponible próximamente.", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        ),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Escanear QR de Emparejamiento", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    }
                }
            }

            // Bottom Footer
            Text(
                text = "IDUPI · Cliente Seguro y Cifrado",
                color = TextSecondary.copy(alpha = 0.5f),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
    }
}

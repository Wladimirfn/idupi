package com.idupi.app.data.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.idupi.app.ui.theme.AppShapes
import com.idupi.app.ui.theme.AppSpacing
import com.idupi.app.ui.theme.AppTypography
import com.idupi.app.ui.theme.IDUPITheme
import com.idupi.app.ui.theme.PrimaryIndigo
import com.idupi.app.ui.theme.TextPrimary
import com.idupi.app.ui.theme.TextSecondary

/**
 * Health Connect's permission rationale screen (phone-health T2).
 *
 * Health Connect opens it from the permission dialog and from the system
 * health screens. It is the owner-facing explanation of what IDUPI reads and,
 * just as important, what it never does: observation only, nothing is written
 * back to Health Connect.
 */
class HealthPermissionsRationaleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IDUPITheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AppSpacing.screenPadding)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
                ) {
                    Text(
                        text = "Datos de salud en IDUPI",
                        style = AppTypography.titleMedium,
                        color = TextPrimary,
                    )
                    Text(
                        text = "IDUPI lee tus datos de salud con permiso de Health " +
                            "Connect para mostrarte tu día y enviar un resumen a tu PC. " +
                            "Los datos solo viajan a tu computador y nunca se comparten " +
                            "con terceros.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                    Text(
                        text = "IDUPI puede leer pasos, distancia, calorías, frecuencia " +
                            "cardíaca, frecuencia cardíaca en reposo, sueño con sus etapas, " +
                            "entrenamientos, peso y saturación de oxígeno.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                    Text(
                        text = "IDUPI no escribe datos de salud, no modifica tus registros " +
                            "y no entrega indicaciones médicas.",
                        style = AppTypography.bodySmall,
                        color = TextSecondary,
                    )
                    Button(
                        onClick = { finish() },
                        shape = AppShapes.button,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PrimaryIndigo,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Entendido", style = AppTypography.bodySmall)
                    }
                }
            }
        }
    }
}

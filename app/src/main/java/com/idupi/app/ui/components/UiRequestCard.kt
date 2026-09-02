package com.idupi.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.idupi.app.domain.model.UiRequest
import com.idupi.app.domain.model.UiRequestMethod
import com.idupi.app.ui.theme.*

@Composable
fun UiRequestCard(
    request: UiRequest,
    onResponse: (Any) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateCard)
            .border(1.dp, AccentPurple, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(AccentPurple)
                )
                Text(
                    text = request.title,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Message
            Text(
                text = request.message,
                color = TextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )

            // Actions based on request method
            if (request.method == UiRequestMethod.CONFIRM) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    OutlinedButton(
                        onClick = { onResponse(false) },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextSecondary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Rechazar", fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = { onResponse(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPurple,
                            contentColor = TextPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Aceptar", fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                // SELECT mode (render buttons for each option)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    request.options.forEach { option ->
                        Button(
                            onClick = { onResponse(option) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SlateBorder,
                                contentColor = TextPrimary
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(option, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

package com.example.idupi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.idupi.domain.model.ChatMessage
import com.example.idupi.domain.model.MessageSender
import com.example.idupi.ui.theme.*

@Composable
fun ChatBubble(
    message: ChatMessage,
    onUiResponse: (Any) -> Unit = {}
) {
    val bubbleShape = when (message.sender) {
        MessageSender.USER -> RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
        MessageSender.PI -> RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
        else -> RoundedCornerShape(8.dp)
    }

    val bubbleBg = when (message.sender) {
        MessageSender.USER -> PrimaryIndigo
        MessageSender.PI -> SlateCard
        MessageSender.SYSTEM -> SlateCard.copy(alpha = 0.5f)
        MessageSender.TOOL -> SlateBg
        MessageSender.SUBAGENT -> SlateBg
        MessageSender.ERROR -> StatusError.copy(alpha = 0.15f)
    }

    val alignment = when (message.sender) {
        MessageSender.USER -> Alignment.End
        MessageSender.PI -> Alignment.Start
        else -> Alignment.CenterHorizontally
    }

    val textColor = when (message.sender) {
        MessageSender.ERROR -> StatusError
        MessageSender.TOOL -> StatusConnected
        else -> TextPrimary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = alignment
    ) {
        // Bubble label for system/tool messages
        if (message.sender != MessageSender.USER && message.sender != MessageSender.PI) {
            val label = when (message.sender) {
                MessageSender.TOOL -> "HERRAMIENTA [${message.toolName ?: ""}]"
                MessageSender.ERROR -> "SISTEMA - ERROR"
                else -> "SISTEMA"
            }
            Text(
                text = label,
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp, end = 4.dp)
            )
        }

        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .padding(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Parse text to extract code blocks if any
                val textParts = parseMarkdown(message.text)
                
                textParts.forEach { part ->
                    if (part.isCode) {
                        CodeBlock(code = part.text, language = part.language)
                    } else {
                        Text(
                            text = part.text,
                            color = textColor,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    }
                }

                // Render dynamic uiResponse cards if present
                message.uiRequest?.let { request ->
                    Spacer(modifier = Modifier.height(4.dp))
                    UiRequestCard(request = request, onResponse = onUiResponse)
                }
            }
        }
    }
}

// Simple part model for rendering
data class TextPart(val text: String, val isCode: Boolean, val language: String = "kotlin")

private fun parseMarkdown(text: String): List<TextPart> {
    if (!text.contains("```")) {
        return listOf(TextPart(text, false))
    }

    val parts = mutableListOf<TextPart>()
    val tokens = text.split("```")
    
    for (i in tokens.indices) {
        val token = tokens[i]
        if (i % 2 == 1) { // It's a code block
            val lines = token.trim().split("\n")
            val language = lines.firstOrNull()?.trim() ?: "kotlin"
            val code = lines.drop(1).joinToString("\n").trim()
            parts.add(TextPart(code, true, language))
        } else { // Normal text
            if (token.isNotEmpty()) {
                parts.add(TextPart(token, false))
            }
        }
    }
    return parts
}

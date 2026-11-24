package com.idupi.app.data.inbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Capture rules for the four supported apps (phone-inbox T3).
 *
 * Snapshots mirror what each app actually posts on an Android 13/14 phone:
 * WhatsApp and WhatsApp Business use MessagingStyle with one entry per recent
 * message and a conversation title for groups; Gmail and Outlook put the sender
 * in the title and subject + snippet in bigText. No Robolectric: the parser
 * takes plain values on purpose.
 */
class NotificationParserTest {

    private fun snapshot(
        packageName: String = "com.whatsapp",
        key: String = "0|com.whatsapp|1|null|10123",
        postTime: Long = 1_700_000_000_000L,
        isOngoing: Boolean = false,
        isGroupSummary: Boolean = false,
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        conversationTitle: String? = null,
        lines: List<MessagingLine> = emptyList()
    ) = NotificationSnapshot(
        packageName = packageName,
        key = key,
        postTime = postTime,
        isOngoing = isOngoing,
        isGroupSummary = isGroupSummary,
        title = title,
        text = text,
        bigText = bigText,
        conversationTitle = conversationTitle,
        messagingLines = lines
    )

    @Test
    fun `WhatsApp one-to-one prefers the newest MessagingStyle message`() {
        val captured = NotificationParser.parse(
            snapshot(
                title = "Carlos Mendez",
                text = "Hola",
                lines = listOf(
                    MessagingLine("Carlos Mendez", "Buen dia"),
                    MessagingLine("Carlos Mendez", "Se rompio el compresor del taller")
                )
            )
        )

        requireNotNull(captured)
        assertEquals("whatsapp", captured.app)
        assertEquals("com.whatsapp", captured.packageName)
        assertEquals("Carlos Mendez", captured.sender)
        assertEquals("Carlos Mendez", captured.conversation)
        assertEquals("Se rompio el compresor del taller", captured.text)
        assertEquals("2023-11-14T22:13:20Z", captured.postedAt)
        assertEquals("0|com.whatsapp|1|null|10123", captured.notificationKey)
    }

    @Test
    fun `WhatsApp group uses the group as conversation and the participant as sender`() {
        val captured = NotificationParser.parse(
            snapshot(
                title = "Taller",
                conversationTitle = "Taller",
                lines = listOf(
                    MessagingLine("Carlos", "Buen dia"),
                    MessagingLine("Ana", "Necesitamos el service urgente")
                )
            )
        )

        requireNotNull(captured)
        assertEquals("Ana", captured.sender)
        assertEquals("Taller", captured.conversation)
        assertEquals("Necesitamos el service urgente", captured.text)
    }

    @Test
    fun `WhatsApp without MessagingStyle falls back to title and text`() {
        val captured = NotificationParser.parse(
            snapshot(title = "Carlos Mendez", text = "Te llamo en un rato")
        )

        requireNotNull(captured)
        assertEquals("Carlos Mendez", captured.sender)
        assertEquals("Carlos Mendez", captured.conversation)
        assertEquals("Te llamo en un rato", captured.text)
    }

    @Test
    fun `WhatsApp Business is captured under its own app id`() {
        val captured = NotificationParser.parse(
            snapshot(
                packageName = "com.whatsapp.w4b",
                title = "Distribuidora Sur",
                text = "Llego el pedido"
            )
        )

        requireNotNull(captured)
        assertEquals("whatsapp_business", captured.app)
        assertEquals("com.whatsapp.w4b", captured.packageName)
    }

    @Test
    fun `Spanish aggregation row is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(title = "WhatsApp", text = "3 mensajes nuevos")
            )
        )
    }

    @Test
    fun `English aggregation row is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(title = "WhatsApp", text = "12 new messages")
            )
        )
    }

    @Test
    fun `aggregation row is skipped when it arrives as bigText`() {
        assertNull(
            NotificationParser.parse(
                snapshot(title = "WhatsApp", text = "WhatsApp", bigText = "2 mensajes nuevos")
            )
        )
    }

    @Test
    fun `group summary notification is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(
                    isGroupSummary = true,
                    title = "Taller",
                    text = "3 mensajes nuevos",
                    conversationTitle = "Taller",
                    lines = listOf(MessagingLine("Carlos", "hola"))
                )
            )
        )
    }

    @Test
    fun `ongoing notification is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(isOngoing = true, title = "Carlos", text = "Llamada en curso")
            )
        )
    }

    @Test
    fun `blank text is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(title = "Carlos", text = "   ", bigText = "")
            )
        )
    }

    @Test
    fun `unsupported package is ignored`() {
        assertNull(
            NotificationParser.parse(
                snapshot(packageName = "com.telegram.messenger", title = "Alguien", text = "hola")
            )
        )
    }

    @Test
    fun `our own package is ignored`() {
        assertNull(
            NotificationParser.parse(
                snapshot(
                    packageName = NotificationParser.OWN_PACKAGE,
                    title = "IDUPI",
                    text = "APK lista"
                )
            )
        )
    }

    @Test
    fun `Gmail uses the title as sender and bigText as the analyzed text`() {
        val bigBody = "Mantenimiento programado del equipo\nEstimado cliente, el sabado..."
        val captured = NotificationParser.parse(
            snapshot(
                packageName = "com.google.android.gm",
                key = "gmail:42",
                title = "Servicio Tecnico",
                text = "Mantenimiento programado del equipo",
                bigText = bigBody
            )
        )

        requireNotNull(captured)
        assertEquals("gmail", captured.app)
        assertEquals("com.google.android.gm", captured.packageName)
        assertEquals("Servicio Tecnico", captured.sender)
        assertEquals("Servicio Tecnico", captured.conversation)
        assertEquals(bigBody, captured.text)
        assertEquals("gmail:42", captured.notificationKey)
    }

    @Test
    fun `Gmail without bigText falls back to the subject line`() {
        val captured = NotificationParser.parse(
            snapshot(
                packageName = "com.google.android.gm",
                title = "Banco",
                text = "Resumen de cuenta"
            )
        )

        requireNotNull(captured)
        assertEquals("Resumen de cuenta", captured.text)
    }

    @Test
    fun `Outlook is captured with the same mail rules`() {
        val captured = NotificationParser.parse(
            snapshot(
                packageName = "com.microsoft.office.outlook",
                key = "outlook:7",
                title = "Recursos Humanos",
                text = "Recordatorio de capacitacion",
                bigText = "Recordatorio de capacitacion\nLa capacitacion es el lunes."
            )
        )

        requireNotNull(captured)
        assertEquals("outlook", captured.app)
        assertEquals("Recursos Humanos", captured.sender)
        assertEquals("Recordatorio de capacitacion\nLa capacitacion es el lunes.", captured.text)
    }

    @Test
    fun `a mail notification without any body is skipped`() {
        assertNull(
            NotificationParser.parse(
                snapshot(
                    packageName = "com.google.android.gm",
                    title = "Banco",
                    text = null,
                    bigText = "  "
                )
            )
        )
    }

    @Test
    fun `postedAt is the ISO form of the notification post time`() {
        val captured = NotificationParser.parse(
            snapshot(postTime = 0L, title = "Carlos", text = "hola")
        )

        requireNotNull(captured)
        assertEquals("1970-01-01T00:00:00Z", captured.postedAt)
    }
}

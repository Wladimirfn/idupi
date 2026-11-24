package com.idupi.app.ui.screens.commands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the command reference: the screen renders whatever is here and the
 * copy button sends the text to the PC unchanged, so an empty, duplicated or
 * accidentally trimmed entry is a user-visible defect.
 */
class IdupiCommandCatalogTest {

    private val sections = IdupiCommandCatalog.sections

    @Test
    fun `catalog is not empty`() {
        assertTrue(sections.isNotEmpty())
    }

    @Test
    fun `every section has a non-blank title and at least one command`() {
        sections.forEach { section ->
            assertTrue("blank section title", section.title.isNotBlank())
            assertTrue("section '${section.title}' has no commands", section.commands.isNotEmpty())
        }
    }

    @Test
    fun `section titles are unique`() {
        val titles = sections.map { it.title }
        assertEquals(titles.size, titles.distinct().size)
    }

    @Test
    fun `every command has a title, command and description`() {
        sections.flatMap { it.commands }.forEach { item ->
            assertTrue("blank title for command '${item.command}'", item.title.isNotBlank())
            assertTrue("blank command in '${item.title}'", item.command.isNotBlank())
            assertTrue("blank description for command '${item.command}'", item.description.isNotBlank())
        }
    }

    @Test
    fun `command text is not padded with whitespace`() {
        sections.flatMap { it.commands }.forEach { item ->
            assertEquals(item.command, item.command.trim())
        }
    }

    @Test
    fun `commands are unique across the catalog`() {
        val commands = sections.flatMap { it.commands }.map { it.command }
        assertEquals(commands.size, commands.distinct().size)
    }

    @Test
    fun `catalog keeps the documented commands`() {
        val commands = sections.flatMap { it.commands }.map { it.command }.toSet()
        val expected = setOf(
            "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\start-server.ps1",
            "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\start-server.ps1 -Restart",
            "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\install-autostart.ps1",
            "powershell -ExecutionPolicy Bypass -File idupi-server\\scripts\\install-sensors-helper.ps1",
            "idupi-term",
            "idupi-term ls",
            "idupi-term new --cwd C:\\ruta\\proyecto",
            "idupi-term attach 1",
            "idupi-term web",
            "idupi-push app\\build\\outputs\\apk\\release\\app-release.apk",
            "idupi-push ls",
            "idupi",
            "idu-pi status",
            "idu-pi quota",
            ".\\gradlew :app:assembleRelease"
        )
        assertEquals(expected, commands)
    }
}

package com.idupi.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display fallbacks of the Terminales card and the terminal header. Pure
 * mapping (no Compose), so every fallback the owner sees is pinned here:
 * project -> cwd segment -> shell name for the title, live cwd over the launch
 * cwd, agent vs muted shell, and the detached-HEAD branch style.
 */
class PtyDisplayTest {

    private fun pty(
        cwd: String? = null,
        shell: String? = null,
        context: PtyContext? = null
    ) = PtyInfo(id = "pty-1", pid = 42, cwd = cwd, shell = shell, context = context)

    @Test
    fun `project, branch and agent come from the live context`() {
        val display = ptyDisplay(
            pty(
                cwd = "C:\\Users\\owner",
                shell = "powershell.exe -NoLogo",
                context = PtyContext(
                    cwd = "C:\\Users\\owner\\AndroidStudioProjects\\IDUPI\\app",
                    project = "IDUPI",
                    branch = "feat/terminal-context",
                    agent = PtyAgent(id = "claude", name = "Claude Code", pid = 777)
                )
            )
        )

        assertEquals("IDUPI", display.title)
        assertTrue(display.pathLine!!.startsWith("…"))
        assertTrue(display.pathLine!!.endsWith("IDUPI\\app"))
        assertEquals("Claude Code", display.agentLabel)
        assertEquals("claude", display.agentId)
        assertEquals("feat/terminal-context", display.branchLabel)
        assertFalse(display.detached)
    }

    @Test
    fun `without context the launch cwd folder is the title`() {
        val display = ptyDisplay(
            pty(cwd = "C:\\Users\\owner\\AndroidStudioProjects\\IDUPI", shell = "powershell.exe")
        )

        assertEquals("IDUPI", display.title)
        assertTrue(display.pathLine!!.endsWith("AndroidStudioProjects\\IDUPI"))
        assertEquals(SHELL_AGENT_LABEL, display.agentLabel)
        assertNull(display.agentId)
        assertNull(display.branchLabel)
        assertFalse(display.detached)
    }

    @Test
    fun `without any path the shell executable is the title`() {
        val display = ptyDisplay(pty(shell = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe -NoLogo"))

        assertEquals("powershell.exe", display.title)
        assertNull(display.pathLine)
    }

    @Test
    fun `nothing known falls back to Terminal`() {
        assertEquals("Terminal", ptyDisplay(pty()).title)
    }

    @Test
    fun `the live cwd wins over the launch cwd`() {
        val display = ptyDisplay(
            pty(
                cwd = "C:\\Users\\owner",
                context = PtyContext(cwd = "C:\\work\\other-project")
            )
        )

        assertEquals("other-project", display.title)
        assertEquals("C:\\work\\other-project", display.pathLine)
    }

    @Test
    fun `a long path is compacted from the start`() {
        val path = "C:\\Users\\owner\\AndroidStudioProjects\\IDUPI\\app\\src\\main\\java"

        val compact = compactPath(path)

        assertTrue(compact.startsWith("…"))
        assertTrue(compact.endsWith("main\\java"))
        assertTrue(compact.length <= 42)
    }

    @Test
    fun `a short path is shown untouched`() {
        assertEquals("C:\\work\\IDUPI", compactPath("C:\\work\\IDUPI"))
    }

    @Test
    fun `detached head shows the sha with the detached suffix`() {
        val display = ptyDisplay(
            pty(
                context = PtyContext(
                    cwd = "C:\\work\\IDUPI",
                    project = "IDUPI",
                    branch = "abc1234",
                    detached = true
                )
            )
        )

        assertEquals("abc1234 · $DETACHED_BRANCH_SUFFIX", display.branchLabel)
        assertTrue(display.detached)
    }

    @Test
    fun `an agent without a name falls back to its id`() {
        val display = ptyDisplay(
            pty(context = PtyContext(project = "IDUPI", agent = PtyAgent(id = "codex")))
        )

        assertEquals("codex", display.agentLabel)
        assertEquals("codex", display.agentId)
    }

    @Test
    fun `header shows project and branch with agent`() {
        val header = ptyHeaderDisplay(
            pty(
                context = PtyContext(
                    cwd = "C:\\work\\IDUPI",
                    project = "IDUPI",
                    branch = "main",
                    agent = PtyAgent(id = "opencode", name = "OpenCode", pid = 9)
                )
            ),
            fallbackId = "pty-1"
        )

        assertEquals("IDUPI", header.title)
        assertEquals("main · OpenCode", header.subtitle)
    }

    @Test
    fun `header falls back to the shell then the terminal id`() {
        assertEquals(
            TERMINAL_HEADER_FALLBACK_TITLE,
            ptyHeaderDisplay(pty(shell = "pwsh.exe"), fallbackId = "pty-1").title
        )
        assertEquals("pwsh.exe", ptyHeaderDisplay(pty(shell = "pwsh.exe"), "pty-1").subtitle)
        assertEquals("pty-1", ptyHeaderDisplay(pty(), "pty-1").subtitle)
        assertEquals("Nueva terminal", ptyHeaderDisplay(null, null).subtitle)
    }

    @Test
    fun `header with a branch but no agent shows only the branch`() {
        val header = ptyHeaderDisplay(
            pty(context = PtyContext(project = "IDUPI", branch = "main")),
            fallbackId = "pty-1"
        )

        assertEquals("main", header.subtitle)
    }

    @Test
    fun `the shell name strips the directory and arguments`() {
        assertEquals(
            "powershell.exe",
            shellNameOf(pty(shell = "C:\\Windows\\powershell.exe -NoLogo"))
        )
    }
}

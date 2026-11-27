package com.idupi.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/**
 * The owner is Chilean: every user-facing string must be neutral Latin
 * American Spanish with "tú", never voseo or regionalisms. This guards the
 * Kotlin string literals under `src/main/java` and the XML resources under
 * `src/main/res`, where Gradle's test working directory is the module dir.
 *
 * The word list is explicit on purpose: a generic accented-suffix heuristic
 * would flag legitimate words ("más", "está", "después").
 */
class NeutralSpanishCopyTest {

    private val voseoForms = listOf(
        "tocá",
        "respondé",
        "revisá",
        "podés",
        "tenés",
        "querés",
        "sabés",
        "elegí",
        "abrí",
        "activá",
        "desactivá",
        "volvé",
        "instalá",
        "andá",
        "mirá",
        "escribí",
        "usá",
        "probá",
        "configurá",
        "conectá",
        "copiá",
        "pegá",
        "ingresá",
        "esperá",
        "seleccioná",
        "agregá",
        "verificá",
        "iniciá",
        "ejecutá",
        "dejá",
        "permití",
        "contame",
        "decime",
        "fijate",
        "dale",
        // Voseo imperatives without the accent cannot be caught by the
        // accented-suffix list, so the common ones are named explicitly.
        "asegurate",
        "acordate",
        "quedate",
        "hacé",
        "poné",
        "decí",
        "vení"
    )

    private val voseoPattern: Pattern = Pattern.compile(
        "(?<![\\p{L}])(" + voseoForms.joinToString("|") { Pattern.quote(it) } + ")(?![\\p{L}])",
        Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
    )

    /** Kotlin string literals: raw ("""...""") first, then regular with escapes. */
    private val kotlinStringPattern = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\\\n])*\"")

    @Test
    fun `user-facing copy uses neutral Spanish without voseo`() {
        val javaRoot = File("src/main/java")
        val resRoot = File("src/main/res")

        assertTrue(
            "Kotlin sources not found at ${javaRoot.absolutePath}; " +
                "the test must run from the app module dir",
            javaRoot.isDirectory
        )
        assertTrue(
            "Android resources not found at ${resRoot.absolutePath}; " +
                "the test must run from the app module dir",
            resRoot.isDirectory
        )

        val violations = mutableListOf<String>()
        javaRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file -> violations += violationsInKotlinStrings(file) }
        resRoot.walkTopDown()
            .filter { it.isFile && it.extension == "xml" }
            .forEach { file -> violations += violationsInXml(file) }

        assertTrue(
            "Voseo found in user-facing copy. Use neutral Latin American Spanish (tú):\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /**
     * Words a file may contain because they are recognized INPUT, never shown
     * or spoken: the bubble's voice confirmation (phone-bubble T21) accepts
     * what the owner actually says, including "dale".
     */
    private val inputVocabularyExemptions = mapOf(
        "BubbleVoiceConfirm.kt" to setOf("dale"),
    )

    private fun violationsInKotlinStrings(file: File): List<String> {
        val text = file.readText()
        val violations = mutableListOf<String>()
        val exempt = inputVocabularyExemptions[file.name].orEmpty()
        kotlinStringPattern.findAll(text).forEach { literal ->
            val matcher = voseoPattern.matcher(literal.value)
            while (matcher.find()) {
                if (matcher.group().lowercase() in exempt) continue
                violations += "${file.path}:${lineOf(text, literal.range.first)}: ${matcher.group()}"
            }
        }
        return violations
    }

    private fun violationsInXml(file: File): List<String> {
        val violations = mutableListOf<String>()
        var insideComment = false
        file.readLines().forEachIndexed { index, line ->
            var content = line
            if (insideComment) {
                val commentEnd = content.indexOf("-->")
                if (commentEnd < 0) return@forEachIndexed
                content = content.substring(commentEnd + 3)
                insideComment = false
            }
            val commentStart = content.indexOf("<!--")
            if (commentStart >= 0) {
                content = content.substring(0, commentStart)
                if (!line.substring(commentStart).contains("-->")) insideComment = true
            }
            val matcher = voseoPattern.matcher(content)
            while (matcher.find()) {
                violations += "${file.path}:${index + 1}: ${matcher.group()}"
            }
        }
        return violations
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset).count { it == '\n' } + 1
}

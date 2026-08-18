package com.example.idupi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

object SyntaxColors {
    val Keyword = Color(0xFFC586C0)       // Purple (control flow, declarations)
    val Declaration = Color(0xFF569CD6)   // Blue (val, var, fun, class, def)
    val StringLiteral = Color(0xFFCE9178) // Orange / Amber ("...", '...')
    val Comment = Color(0xFF6A9955)       // Green / Gray (//..., #..., /*...*/)
    val Number = Color(0xFFB5CEA8)        // Light Green (123, 3.14)
    val BooleanNull = Color(0xFF569CD6)   // Blue (true, false, null)
    val TypeClass = Color(0xFF4EC9B0)     // Teal (String, Int, User, @Composable)
    val Function = Color(0xFFDCDCAA)      // Yellow / Gold (funName())
    val PlainText = Color(0xFFD4D4D4)     // Light Gray / White
    val LineNumber = Color(0xFF858585)    // Muted Gray
    val GutterBg = Color(0xFF1E222B)      // Slightly darker gutter background
}

private val DECLARATION_KEYWORDS = setOf(
    "val", "var", "fun", "function", "class", "interface", "object", "enum",
    "type", "struct", "const", "let", "def", "import", "package", "export",
    "from", "public", "private", "protected", "internal", "override", "open",
    "abstract", "suspend", "data", "sealed", "inline", "infix", "operator",
    "companion", "constructor", "init", "trait", "impl", "fn", "async", "await",
    "namespace", "using", "module", "extends", "implements", "native"
)

private val CONTROL_KEYWORDS = setOf(
    "if", "else", "when", "for", "while", "do", "try", "catch", "finally",
    "throw", "return", "break", "continue", "yield", "switch", "case", "default",
    "in", "is", "as", "by", "where", "with", "select", "from", "delete", "update", "insert"
)

private val BOOLEAN_NULL_KEYWORDS = setOf(
    "true", "false", "null", "nil", "undefined", "None", "True", "False", "NaN"
)

/**
 * Highlights a single line of source code into an [AnnotatedString].
 */
fun highlightCodeLine(line: String, fileExtension: String = ""): AnnotatedString {
    return buildAnnotatedString {
        var idx = 0
        val len = line.length

        // Check for whole-line or trailing comment
        fun isCommentStart(i: Int): Boolean {
            if (i < len - 1 && line[i] == '/' && line[i + 1] == '/') return true
            if (i < len - 1 && line[i] == '/' && line[i + 1] == '*') return true
            if (line[i] == '#' && (fileExtension in listOf("py", "sh", "bash", "yml", "yaml", "toml", "env", "rb", "r"))) return true
            if (i < len - 3 && line.substring(i, (i + 4).coerceAtMost(len)) == "<!--") return true
            return false
        }

        while (idx < len) {
            val ch = line[idx]

            // 1. Comments
            if (isCommentStart(idx)) {
                pushStyle(SpanStyle(color = SyntaxColors.Comment, fontStyle = FontStyle.Italic))
                append(line.substring(idx))
                pop()
                break
            }

            // 2. String Literals
            if (ch == '"' || ch == '\'' || ch == '`') {
                val quote = ch
                val start = idx
                idx++
                while (idx < len) {
                    if (line[idx] == '\\' && idx + 1 < len) {
                        idx += 2
                    } else if (line[idx] == quote) {
                        idx++
                        break
                    } else {
                        idx++
                    }
                }
                pushStyle(SpanStyle(color = SyntaxColors.StringLiteral))
                append(line.substring(start, idx))
                pop()
                continue
            }

            // 3. Annotations (@Annotation)
            if (ch == '@' && idx + 1 < len && (line[idx + 1].isLetter() || line[idx + 1] == '_')) {
                val start = idx
                idx++
                while (idx < len && (line[idx].isLetterOrDigit() || line[idx] == '_')) {
                    idx++
                }
                pushStyle(SpanStyle(color = SyntaxColors.TypeClass, fontWeight = FontWeight.SemiBold))
                append(line.substring(start, idx))
                pop()
                continue
            }

            // 4. Words (Keywords, Types, Functions, Identifiers)
            if (ch.isLetter() || ch == '_') {
                val start = idx
                while (idx < len && (line[idx].isLetterOrDigit() || line[idx] == '_')) {
                    idx++
                }
                val word = line.substring(start, idx)

                // Peek next non-whitespace char for function call
                var peek = idx
                while (peek < len && line[peek].isWhitespace()) peek++
                val isFunctionCall = peek < len && line[peek] == '('

                when {
                    word in DECLARATION_KEYWORDS -> {
                        pushStyle(SpanStyle(color = SyntaxColors.Declaration, fontWeight = FontWeight.Bold))
                        append(word)
                        pop()
                    }
                    word in CONTROL_KEYWORDS -> {
                        pushStyle(SpanStyle(color = SyntaxColors.Keyword, fontWeight = FontWeight.Bold))
                        append(word)
                        pop()
                    }
                    word in BOOLEAN_NULL_KEYWORDS -> {
                        pushStyle(SpanStyle(color = SyntaxColors.BooleanNull, fontWeight = FontWeight.Bold))
                        append(word)
                        pop()
                    }
                    word.first().isUpperCase() -> {
                        pushStyle(SpanStyle(color = SyntaxColors.TypeClass))
                        append(word)
                        pop()
                    }
                    isFunctionCall -> {
                        pushStyle(SpanStyle(color = SyntaxColors.Function))
                        append(word)
                        pop()
                    }
                    else -> {
                        pushStyle(SpanStyle(color = SyntaxColors.PlainText))
                        append(word)
                        pop()
                    }
                }
                continue
            }

            // 5. Numbers
            if (ch.isDigit()) {
                val start = idx
                while (idx < len && (line[idx].isLetterOrDigit() || line[idx] == '.' || line[idx] == 'x' || line[idx] == 'X')) {
                    idx++
                }
                pushStyle(SpanStyle(color = SyntaxColors.Number))
                append(line.substring(start, idx))
                pop()
                continue
            }

            // 6. Operators & Punctuation
            pushStyle(SpanStyle(color = SyntaxColors.PlainText))
            append(ch.toString())
            pop()
            idx++
        }
    }
}

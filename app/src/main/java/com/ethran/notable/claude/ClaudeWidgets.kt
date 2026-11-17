package com.ethran.notable.claude

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.ethran.notable.claude.bridge.Termux
import com.ethran.notable.ui.noRippleClickable

internal val CLAUDE_TEXT_SIZE = 15.sp

/** A flat, bordered e-ink button. */
@Composable
internal fun PanelButton(
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(2.dp)
            .background(if (selected) Color.Black else Color.White)
            .border(1.dp, if (enabled) Color.Black else Color.Gray)
            .then(if (enabled) Modifier.noRippleClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = when {
                selected -> Color.White
                enabled -> Color.Black
                else -> Color.Gray
            },
        )
    }
}

@Composable
internal fun InkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .background(Color.White)
            .border(1.dp, Color.Black)
            .padding(8.dp)
    ) {
        if (value.isEmpty()) {
            Text(placeholder, color = Color.Gray, fontSize = CLAUDE_TEXT_SIZE)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = TextStyle(fontSize = CLAUDE_TEXT_SIZE, color = Color.Black),
            cursorBrush = SolidColor(Color.Black),
            modifier = Modifier.fillMaxWidth().then(if (singleLine) Modifier else Modifier.fillMaxHeight()),
        )
    }
}

/**
 * A [ClaudeProblem] with its fixes as buttons. [onRetry] runs after "Try again" and after the
 * Termux permission is granted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProblemCard(problem: ClaudeProblem, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember(problem) { mutableStateOf<String?>(null) }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onRetry() }

    Column(modifier) {
        Text(problem.title, fontWeight = FontWeight.Bold, fontSize = CLAUDE_TEXT_SIZE)
        Text(problem.message, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
        FlowRow(Modifier.padding(top = 4.dp)) {
            problem.actions.forEach { action ->
                PanelButton(action.label) {
                    when (action) {
                        is ProblemAction.CopyCommand -> {
                            copyText(context, action.command)
                            copied = action.command
                        }
                        ProblemAction.RequestPermission -> requestPermission.launch(Termux.PERMISSION)
                        ProblemAction.OpenTermux -> openTermux(context)
                        is ProblemAction.OpenUrl -> runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, action.url.toUri())
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                        ProblemAction.Retry -> onRetry()
                    }
                }
            }
        }
        copied?.let {
            Text(
                "Copied — paste it in Termux:\n$it",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun openTermux(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(Termux.PACKAGE) ?: return false
    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return true
}

internal fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Notable", text))
}

/**
 * Just enough Markdown for chat replies on e-ink: **bold**, *italic*, `code`, headings and
 * bullet lists. Everything else is shown as written.
 */
internal fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.trimEnd().lines()
    lines.forEachIndexed { index, rawLine ->
        var line = rawLine
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(line.substring(heading.range.last + 1)) }
        } else {
            val bullet = Regex("^(\\s*)[-*+]\\s+").find(line)
            if (bullet != null) {
                append(bullet.groupValues[1])
                append("• ")
                line = line.substring(bullet.range.last + 1)
            }
            appendInline(line)
        }
        if (index < lines.lastIndex) append('\n')
    }
}

private val INLINE = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")

private fun AnnotatedString.Builder.appendInline(line: String) {
    var last = 0
    for (match in INLINE.findAll(line)) {
        append(line.substring(last, match.range.first))
        val (bold, boldUnderscore, code, italic) = match.destructured
        when {
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            boldUnderscore.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(boldUnderscore) }
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            italic.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(italic) }
        }
        last = match.range.last + 1
    }
    append(line.substring(last))
}

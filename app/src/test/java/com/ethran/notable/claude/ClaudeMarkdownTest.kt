package com.ethran.notable.claude

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMarkdownTest {

    @Test
    fun `markers are removed and styled`() {
        val out = renderMarkdown("## Step 2\n- **wrong** sign in `x²`\n* check *line 3*")
        assertEquals("Step 2\n• wrong sign in x²\n• check line 3", out.text)
        val bold = out.spanStyles.filter { it.item.fontWeight == FontWeight.Bold }
            .map { out.text.substring(it.start, it.end) }
        assertTrue(bold.containsAll(listOf("Step 2", "wrong")))
    }

    @Test
    fun `math and lone asterisks are left alone`() {
        assertEquals("2 * 3 = 6, a*b", renderMarkdown("2 * 3 = 6, a*b").text)
    }

    @Test
    fun `unclosed markers stay as written`() {
        assertEquals("**almost", renderMarkdown("**almost").text)
    }
}

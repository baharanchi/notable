package com.ethran.notable.claude

/** Defaults and ready-made instruction presets for the Claude feedback panel. */
object ClaudeDefaults {
    const val BRIDGE_URL = "http://127.0.0.1:8765"

    /** Run by Termux through `bash -lc`, so `~` and the user's PATH work. */
    const val TERMUX_COMMAND = "python ~/notable_claude_bridge.py"

    const val CHECK_PROMPT =
        "Here is my current page. Check my work so far, following your instructions."

    const val HINT_PROMPT =
        "I'm stuck. Give me one small hint for the step I'm on — the smallest nudge that " +
                "gets me moving again."

    const val TUTOR = """You are my tutor while I solve exercises by hand.
- Never reveal answers, final results or full solutions, even partially.
- When I send my page, tell me only whether my work so far is correct. If something is wrong, point to where the first mistake is (which line/step), not what the fix is.
- Give a hint only when I explicitly ask for one, and then the smallest useful hint.
- Keep replies to 1–3 short sentences."""

    const val CHECKER = """Check my work for mistakes.
- Say whether each step is right or wrong; for a wrong step, explain briefly what is wrong and how to fix it.
- Do not solve parts I have not attempted yet.
- Be concise."""

    const val EXPLAINER = """Be a patient teacher.
- Review my work, explain any mistake and the idea behind the correct approach.
- If I ask, you may show full worked solutions.
- Keep explanations short and step by step."""

    /** Used when a notebook has no instructions of its own. */
    const val INSTRUCTIONS = TUTOR

    val PRESETS: List<Pair<String, String>> = listOf(
        "Tutor (no answers)" to TUTOR,
        "Checker" to CHECKER,
        "Explainer" to EXPLAINER,
    )
}

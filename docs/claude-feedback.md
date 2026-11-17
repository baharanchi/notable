# Claude feedback

Ask Claude about the page you're writing on. Tap the **Claude** toolbar button (speech
bubble). Notable takes a snapshot of the page and sends it to Claude Code running in Termux on
the same tablet, and Claude's reply appears in a box at the bottom of the editor.

Typical use: work through an exercise sheet by hand, for example a PDF imported as the
notebook's background or uploaded as a file. Tap the button whenever you want a check. Your
instructions decide how Claude answers ("never reveal the answer, only tell me if I'm wrong,
hints only when I ask").

## Setup (once)

1. Install **Termux from F-Droid** (the Play Store version is outdated) and open it once.
2. In Termux, run the command from **Settings → Claude → Copy command**:

   ```bash
   pkg install -y python nodejs && npm install -g @anthropic-ai/claude-code && mkdir -p ~/.termux && (grep -q '^allow-external-apps *= *true' ~/.termux/termux.properties 2>/dev/null || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings && claude
   ```

   It installs Python, Node and Claude Code, allows Notable to start commands in Termux, and
   opens Claude Code so you can log in.
3. In Notable, **Settings → Claude → Check setup**. Allow "Run commands in Termux" when asked.
   The check goes through each requirement and says what to do about anything missing.
4. Recommended: exempt Termux from battery optimisation (on BOOX: disable freezing for Termux).

You never start anything by hand afterwards.

## Using it

- **Toolbar button**: snapshots the page and sends it with the "check" message. If your saved
  toolbar layout predates the button, add **Ask Claude** from Settings → Toolbar → Hidden. The
  toolbar menu also has **Claude panel**.
- **Docked panel**: shows the latest reply and has **Check page**, **Hint**, **Chat** and
  **Cancel**. The pen doesn't draw on it; the rest of the page works as usual.
- **Chat** opens a sheet with three tabs:
  - **Chat**: the whole conversation. Type messages, with or without a page snapshot
    (**☑ page**). **New conversation** makes Claude forget the chat but keeps the files. Tap
    a reply to copy it.
  - **Files**: upload files Claude can read (exercise PDFs, images, notes), and delete them.
  - **Instructions**: how Claude gives feedback in *this* notebook, from presets or your own
    words. Left empty, the default from Settings applies. Instructions are sent with every
    message.
- **Settings → Claude**: setup check, snapshot mode (visible screen or whole page), model,
  default instructions, the check and hint messages, and the `claude` command.

Each notebook is one Claude conversation (a loose page gets its own), so Claude remembers
earlier checks.

## How it works

```
Notable ──RUN_COMMAND──▶ Termux: bash ─exec─▶ python ~/.notable-claude/bridge.py
   │                                              │  (127.0.0.1:8765, secret token)
   └──────────── HTTP: snapshots, chat ──────────▶│──stdin/stdout──▶ claude -p --resume …
```

- **One helper, started on demand.** The app ships `assets/claude/bridge.py`. When it needs
  Claude and nothing answers on port 8765, it asks Termux (RUN_COMMAND intent) to start it. The
  launch command writes the shipped script to `~/.notable-claude/bridge.py`, stops any older
  helper, and runs it, so Termux always runs the version that matches the app. The helper exits
  after 15 minutes without requests or work.
- **Private.** The helper listens on 127.0.0.1 only and accepts requests carrying a random token
  that the app generated and keeps in its private storage.
- **Images over HTTP**, so there is no size limit and Termux needs no storage permission. They
  go to Claude as image content in the message itself, so Claude doesn't have to read a file
  first.
- **Conversations** live in `~/.notable-claude/conversations/<notebook>/`: `history.json`,
  `meta.json` (with the Claude session id), `files/` and `snapshots/`. Each message runs
  `claude -p --resume <session>` in that folder. The app keeps a copy of each conversation so
  the panel shows it without starting the helper.
- **Progress** is step by step: the status line shows what Claude is doing ("Read
  files/sheet.pdf…"), and text Claude writes before a step appears before the next one.

### When things go wrong

| What happens | What Notable does |
| --- | --- |
| The helper isn't running (never started, exited when idle, or Android stopped Termux) | Starts it and repeats the request. Requests carry ids, so the helper never runs a message twice. |
| An older helper is running (app updated) or one with another token | Stops it and starts the right one. |
| The start command fails (no Python, Termux refuses external commands, bootstrap missing) | Termux reports the exit code and output back; the panel says exactly what to fix, with a command to copy. |
| `claude` is killed or vanishes before answering | The helper retries, up to 3 attempts, resuming the session and telling Claude the message is a repeat. |
| The helper itself dies while Claude is answering | The running job is saved on disk; the next helper start runs it again. |
| `claude` reports an error (not logged in, API error) | Shown in the chat, with "run `claude` in Termux to log in" where that's the fix. |
| Claude takes over 15 minutes | Stopped and reported. |

`claude` runs in its own session (no terminal), so a Termux terminal can't hang it up. On
Android, `claude` is a `#!/usr/bin/env node` script and Android has no `/usr/bin/env`, so the
helper reads that line and starts `node` itself.

### Continuing a conversation in Termux

The Files tab shows the conversation folder and session id. In Termux, `cd` into that folder
and run `claude --resume <session id>`.

### Tests

- `app/src/test/python/test_bridge.py`: the helper, against a fake `claude`
  (`python3 -m unittest discover -s app/src/test/python`).
- `app/src/test/java/com/ethran/notable/claude/`: starting and replacing the helper, mapping
  Termux's reports to problems, and the launch shell script run as Termux would run it.

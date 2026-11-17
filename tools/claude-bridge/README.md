# Claude feedback for Notable (Termux bridge)

Get feedback from Claude Code on what you are writing, without leaving the notebook. Tap the
**Claude** toolbar button (speech bubble). Notable takes a snapshot of the page and sends it to
Claude Code running in Termux on the same tablet. Claude's reply appears in a box at the bottom
of the editor.

Typical use: import an exercise sheet as the notebook's PDF background, or upload it as a file,
and work through it by hand. Tap the button whenever you want a check. Your instructions decide
how Claude answers, for example "never reveal the answer, only tell me if I'm wrong, hints only
when I ask".

```
Notable ──HTTP (127.0.0.1:8765)──▶ notable_claude_bridge.py ──stdin/stdout──▶ claude -p
 (page snapshot, chat, files)       (in Termux; one session per notebook)
```

## One-time setup in Termux

```bash
pkg install python nodejs
```

```bash
npm install -g @anthropic-ai/claude-code
```

Run `claude` once and log in, then copy the bridge script into your Termux home:

```bash
cd ~ && curl -LO https://github.com/baharanchi/notable/releases/download/claude-build-1/notable_claude_bridge.py
```

(Each release on the Releases page has the matching `notable_claude_bridge.py`.)

Start it:

```bash
python ~/notable_claude_bridge.py
```

Leave it running. Run `termux-wake-lock` (or use the Termux notification's "Acquire wakelock")
so Android doesn't stop it when the screen sleeps.

### Optional: start the bridge from Notable

To start the bridge with the **Start bridge in Termux** button (in the Claude panel and in
Settings → Claude) instead of by hand:

1. In Termux, allow other apps to run commands:

   ```bash
   mkdir -p ~/.termux && echo "allow-external-apps = true" >> ~/.termux/termux.properties && termux-reload-settings
   ```

2. Tap the button once. Notable asks for the "Run commands in Termux environment" permission.

The bridge then runs as a Termux background task and logs to `~/.notable-claude/bridge.log`.

## Using it

- **Toolbar button**: snapshot the page and send it with the "check" message (editable in
  Settings → Claude). If your saved toolbar layout predates the button, add **Ask Claude** from
  Settings → Toolbar → Hidden. You can also open the panel from the toolbar menu → **Claude
  panel**.
- **Docked panel** (bottom of the editor): the latest reply, plus **Check page**, **Hint**,
  **Chat** and **Cancel**. The pen does not draw on the panel; the rest of the page works as
  usual.
- **Chat** opens a sheet with three tabs:
  - **Chat**: the whole conversation. Type a message (for example "give me a hint for 2b" or
    "is step 3 right?"). With **☑ page** on, a snapshot of the page goes with it. **New
    conversation** makes Claude forget the chat but keeps the files.
  - **Files**: upload files Claude can read (exercise PDFs, images, notes) and delete them.
  - **Instructions**: how Claude should give feedback in *this* notebook. There are presets,
    or write your own. Empty means the default from Settings → Claude. Instructions are sent
    with every message, so changes apply immediately.
- **Settings → Claude**: bridge URL and token, default instructions, the check and hint
  messages, and what the snapshot shows (the visible screen, or the whole page cut into
  screen-sized parts).

Each notebook is one Claude conversation (a quick page has its own). Claude therefore remembers
earlier checks, so it can say what has improved since the last one.

## Bridge options

```text
python notable_claude_bridge.py [--port 8765] [--token SECRET] [--model sonnet]
                                [--allowed-tools Read,Glob,Grep] [--timeout 600]
                                [--data-dir ~/.notable-claude] [--claude PATH]
                                [--extra-arg ARG ...] [--verbose]
```

- `--token`: require a shared secret. Enter the same token in Settings → Claude. Recommended:
  without it, any app on the tablet can talk to the bridge.
- `--allowed-tools`: what Claude may do without asking. The default is read-only (read the
  uploaded files and earlier snapshots). Every other tool is refused, because nobody is there
  to approve it.
- `--model`: any model alias or id `claude --model` accepts.

Every option also has an environment variable (`NOTABLE_BRIDGE_TOKEN`, `NOTABLE_BRIDGE_MODEL`,
and so on).

## Where things live

`~/.notable-claude/conversations/<notebook id>/` holds `history.json`, `meta.json` (including
the Claude session id), `files/` and `snapshots/`. To continue a conversation in the terminal,
`cd` there and run `claude --resume <session id>`. The Files tab in Notable shows both.

## API (for the curious)

| Method and path | What it does |
| --- | --- |
| `GET /health` | Bridge version and the `claude` it found |
| `GET /conversations/<key>` | History, files, `busy`, `status` and `partial` (the reply so far) |
| `POST /conversations/<key>/messages` | `{text, instructions, title, context, images:[{mime,data,label}]}`; answers 202 and runs in the background |
| `POST /conversations/<key>/files` | `{name, data}` (base64) |
| `DELETE /conversations/<key>/files/<name>` | Deletes an uploaded file |
| `POST /conversations/<key>/cancel` | Stops the running reply |
| `POST /conversations/<key>/reset` | `{keep_files}`; new session, empty chat |

Tests: `python -m unittest discover -s tools/claude-bridge/tests` (uses a fake `claude`).

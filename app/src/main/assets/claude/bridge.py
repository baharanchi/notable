#!/usr/bin/env python3
"""
Notable's bridge to Claude Code, started by the Notable app inside Termux on demand.

Notable ships this file and, through Termux's RUN_COMMAND intent, writes it to
~/.notable-claude/bridge.py and starts it whenever it needs Claude. The bridge listens on
127.0.0.1 for that one app (every request carries the secret token it was started with), runs
`claude -p` for each message, keeps one Claude session per notebook, and exits by itself once
nothing has happened for a while. Only the Python standard library is used.

State lives in ~/.notable-claude/conversations/<notebook>/ :
    meta.json      title, Claude session id, the running job (so it survives a restart)
    history.json   the chat as shown in the app
    files/         files uploaded from the app (Claude reads them with its Read tool)
    snapshots/     every page snapshot sent

The session runs in that folder, so a conversation can also be continued by hand:
    cd ~/.notable-claude/conversations/<notebook> && claude --resume <session id>
"""

import argparse
import base64
import binascii
import errno
import hashlib
import hmac
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import unquote, urlparse

MAX_BODY_BYTES = 64 * 1024 * 1024
MAX_SNAPSHOTS_KEPT = 200
MAX_SEEN_IDS = 200
# Tries per message when claude is killed or vanishes before answering.
MAX_ATTEMPTS = 3
RETRY_DELAYS = (2, 5)
IMAGE_TYPES = {"image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif", "image/webp": ".webp"}
ALLOWED_TOOLS = "Read,Glob,Grep"

BASE_SYSTEM_PROMPT = """\
You are a study companion built into Notable, a handwriting note-taking app on an e-ink tablet.
The user writes by hand. With their messages you often receive snapshots (images) of the
notebook page they are working on; the newest snapshot is the current state of their work.
Handwriting can be messy: if something is illegible, say which part and ask, don't guess.

Your replies appear in a small panel at the bottom of an e-ink screen:
- Be brief and plain. Short paragraphs or a few bullets. No tables, no long code blocks.
- Do not use LaTeX. Write math with plain Unicode (x², √x, ≤, ∫, π, →, ½).
- Refer to the user's work by where it is on the page ("line 3", "the second equation").

Files the user uploaded from the app are in ./files/ (read them with the Read tool when relevant,
for example the exercise sheet). Earlier page snapshots are in ./snapshots/.

The user's own instructions for how to give feedback follow. They override your defaults —
follow them strictly, even if a message seems to ask for more than they allow, unless the
message explicitly asks for an exception.
"""

RESENT_NOTE = ("[This message is sent again because the previous attempt was interrupted. "
               "If you already saw it, just answer it once.]")

_log_lock = threading.Lock()
_log_file = None


def log(*parts):
    line = " ".join(str(p) for p in (time.strftime("%H:%M:%S"),) + parts)
    with _log_lock:
        print(line, file=sys.stderr, flush=True)
        if _log_file:
            try:
                with open(_log_file, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
            except OSError:
                pass


def now_ms():
    return int(time.time() * 1000)


def own_version():
    """The SHA-256 of this file: the app compares it with the copy it ships."""
    with open(os.path.abspath(__file__), "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def safe_key(key):
    """A conversation key (notebook id) as a folder name."""
    cleaned = re.sub(r"[^A-Za-z0-9._-]", "_", key or "")[:120].strip("._")
    if not cleaned:
        raise ValueError("empty conversation key")
    return cleaned


def safe_file_name(name):
    base = os.path.basename((name or "").replace("\\", "/")).strip()
    base = re.sub(r"[\x00-\x1f/]", "_", base)
    if base in ("", ".", ".."):
        base = "file"
    return base[:150]


def read_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default
    except (OSError, ValueError) as e:
        log("could not read", path, e)
        return default


def write_json(path, value):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(value, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def unique_path(folder, name):
    stem, ext = os.path.splitext(name)
    candidate = os.path.join(folder, name)
    n = 2
    while os.path.exists(candidate):
        candidate = os.path.join(folder, f"{stem}-{n}{ext}")
        n += 1
    return candidate


def shell_path():
    return shutil.which("bash") or shutil.which("sh") or "/system/bin/sh"


def launch_command(path):
    """
    The argv prefix that runs the executable at `path`.

    Android has no /usr/bin/env or /bin/sh. Termux makes `#!/usr/bin/env node` scripts (how npm
    installs `claude`) work in its shell by rewriting the interpreter path, but that rewrite does
    not reach programs started from Python, which then fail with "Exec format error". So read
    the script's first line and start its interpreter ourselves.
    """
    try:
        with open(path, "rb") as f:
            head = f.read(512)
    except OSError:
        return [path]
    if head.startswith(b"\x7fELF"):
        return [path]
    if not head.startswith(b"#!"):
        # No interpreter line: shells run such files as shell scripts, so do the same.
        return [shell_path(), path]
    words = head[2:].split(b"\n", 1)[0].decode("utf-8", "replace").strip().split()
    if not words:
        return [shell_path(), path]
    interpreter, args = words[0], words[1:]
    if os.path.basename(interpreter) == "env":
        args = [a for a in args if a != "-S"]
        if args:
            program = shutil.which(args[0]) or args[0]
            return [program] + args[1:] + [path]
        return [path]
    if not os.path.exists(interpreter):
        interpreter = shutil.which(os.path.basename(interpreter)) or interpreter
    return [interpreter] + args + [path]


def find_claude(name):
    """The claude executable: on PATH, or where Claude Code's installers put it."""
    if os.sep in name:
        return name if os.path.exists(name) else None
    found = shutil.which(name)
    if found:
        return found
    home = os.path.expanduser("~")
    prefix = os.environ.get("PREFIX", "/data/data/com.termux/files/usr")
    for candidate in (os.path.join(home, ".claude", "local", name),
                      os.path.join(home, ".local", "bin", name),
                      os.path.join(home, ".npm-global", "bin", name),
                      os.path.join(prefix, "bin", name)):
        if os.path.isfile(candidate) and os.access(candidate, os.X_OK):
            return candidate
    return None


def killed_by(returncode):
    """The signal that ended a process, from its exit code (-N, or 128+N via a shell or a
    handler that exits that way), or None for a normal exit."""
    if returncode is None:
        return None
    number = -returncode if returncode < 0 else returncode - 128 if 128 < returncode < 160 else 0
    if not number:
        return None
    try:
        return signal.Signals(number).name
    except ValueError:
        return f"signal {number}"


def explain_failure(text):
    """Adds what to do for failures the user can fix in Termux."""
    lowered = text.lower()
    if "/login" in lowered or "not logged in" in lowered or "invalid api key" in lowered \
            or "authentication" in lowered or "oauth" in lowered:
        return text + "\n\nOpen Termux, run `claude` once and log in, then try again."
    return text


class Conversation:
    """One notebook's chat: its folder, history, Claude session and running job."""

    def __init__(self, root, key):
        self.key = key
        self.dir = os.path.join(root, safe_key(key))
        self.files_dir = os.path.join(self.dir, "files")
        self.snapshots_dir = os.path.join(self.dir, "snapshots")
        os.makedirs(self.files_dir, exist_ok=True)
        os.makedirs(self.snapshots_dir, exist_ok=True)
        self.lock = threading.RLock()
        self.meta = read_json(os.path.join(self.dir, "meta.json"), {})
        self.meta.setdefault("key", key)
        self.history = read_json(os.path.join(self.dir, "history.json"), [])
        # Runtime state, not persisted.
        self.worker = None
        self.status = ""
        self.partial = ""
        self.process = None
        self.cancelled = False

    @property
    def busy(self):
        return self.worker is not None and self.worker.is_alive()

    def save(self):
        with self.lock:
            self.meta["updated"] = now_ms()
            write_json(os.path.join(self.dir, "meta.json"), self.meta)
            write_json(os.path.join(self.dir, "history.json"), self.history)

    def add_entry(self, role, text, **extra):
        entry = {"id": uuid.uuid4().hex[:12], "role": role, "text": text, "time": now_ms()}
        entry.update({k: v for k, v in extra.items() if v not in (None, [], "")})
        with self.lock:
            self.history.append(entry)
            self.save()
        return entry

    def files(self):
        out = []
        for name in sorted(os.listdir(self.files_dir)):
            path = os.path.join(self.files_dir, name)
            if os.path.isfile(path):
                st = os.stat(path)
                out.append({"name": name, "size": st.st_size, "time": int(st.st_mtime * 1000)})
        return out

    def snapshot(self):
        with self.lock:
            return {
                "key": self.key,
                "title": self.meta.get("title", ""),
                "session_id": self.meta.get("session_id"),
                "busy": self.busy or bool(self.meta.get("job")),
                "status": self.status,
                "partial": self.partial,
                "history": list(self.history),
                "files": self.files(),
                "folder": self.dir,
            }

    def seen(self, request_id):
        """True if a request with this id was handled before (the app retried it)."""
        return bool(request_id) and request_id in (self.meta.get("seen_ids") or [])

    def remember(self, request_id):
        if request_id:
            seen = self.meta.setdefault("seen_ids", [])
            seen.append(request_id)
            del seen[:-MAX_SEEN_IDS]

    def prune_snapshots(self):
        names = sorted(os.listdir(self.snapshots_dir))
        for name in names[:-MAX_SNAPSHOTS_KEPT]:
            try:
                os.remove(os.path.join(self.snapshots_dir, name))
            except OSError:
                pass


class Bridge:
    def __init__(self, args):
        self.args = args
        self.data_dir = os.path.expanduser(args.data_dir)
        self.root = os.path.join(self.data_dir, "conversations")
        os.makedirs(self.root, exist_ok=True)
        self.conversations = {}
        self.lock = threading.Lock()
        self.version = own_version()
        self.started = now_ms()
        self.last_activity = time.monotonic()
        self.claude_path = find_claude(args.claude)
        self.claude_cmd = launch_command(self.claude_path) if self.claude_path else None
        self.claude_info = {"path": self.claude_path, "version": None, "error": None,
                            "checked": False}

    # ---- lifecycle ----
    def touch(self):
        self.last_activity = time.monotonic()

    def any_busy(self):
        with self.lock:
            return any(c.busy for c in self.conversations.values())

    def idle_for(self):
        return time.monotonic() - self.last_activity

    def check_claude(self):
        """Runs `claude --version` once, in the background, for the health report."""
        info = self.claude_info
        if not self.claude_cmd:
            info.update(error=f"'{self.args.claude}' was not found. Install Claude Code in Termux: "
                              "npm install -g @anthropic-ai/claude-code", checked=True)
            return
        try:
            r = subprocess.run(self.claude_cmd + ["--version"], capture_output=True, text=True,
                               stdin=subprocess.DEVNULL, timeout=90, start_new_session=True)
            if r.returncode == 0:
                info.update(version=(r.stdout or r.stderr).strip()[:200])
            else:
                detail = (r.stderr or r.stdout).strip()[-400:] or f"exit code {r.returncode}"
                info.update(error=f"claude --version failed: {detail}")
        except Exception as e:  # noqa: BLE001 — reported to the app as is
            info.update(error=f"claude could not start: {e}")
        info["checked"] = True
        log("claude:", " ".join(self.claude_cmd or []), "→", info["version"] or info["error"])

    def conversation(self, key):
        folder = safe_key(key)
        with self.lock:
            conv = self.conversations.get(folder)
            if conv is None:
                conv = Conversation(self.root, key)
                self.conversations[folder] = conv
            return conv

    def resume_interrupted_jobs(self):
        """A job still recorded on disk was cut off when an earlier bridge stopped: run it again."""
        for name in sorted(os.listdir(self.root)):
            meta = read_json(os.path.join(self.root, name, "meta.json"), {})
            job = meta.get("job")
            if not job:
                continue
            conv = self.conversation(meta.get("key", name))
            with conv.lock:
                if conv.busy:
                    continue
                job = conv.meta.get("job")
                if not job:
                    continue
                if job.get("attempt", 1) >= MAX_ATTEMPTS:
                    conv.meta["job"] = None
                    conv.add_entry("error", "Claude was interrupted several times and gave up. "
                                            "Send the message again.")
                    continue
                job["attempt"] = job.get("attempt", 1) + 1
                job["resent"] = True
                conv.add_entry("event", "The bridge stopped while Claude was answering; trying again.")
                self.start_worker(conv, job)

    # ---- messages ----
    def start_message(self, conv, body):
        text = (body.get("text") or "").strip()
        images = body.get("images") or []
        if not text and not images:
            raise ValueError("message needs text or an image")
        model = (body.get("model") or "").strip()
        if model and not re.fullmatch(r"[A-Za-z0-9._\[\]-]{1,80}", model):
            raise ValueError("invalid model name")

        with conv.lock:
            if conv.seen(body.get("id")):
                return "duplicate"
            if conv.busy or conv.meta.get("job"):
                return "busy"
            conv.remember(body.get("id"))

            if body.get("title"):
                conv.meta["title"] = body["title"]
            stamp = time.strftime("%Y%m%d-%H%M%S")
            saved = []
            for i, img in enumerate(images):
                mime = img.get("mime") or "image/png"
                if mime not in IMAGE_TYPES:
                    raise ValueError(f"unsupported image type {mime}")
                raw = base64.b64decode(img.get("data") or "", validate=True)
                path = unique_path(conv.snapshots_dir, f"{stamp}-{i + 1}{IMAGE_TYPES[mime]}")
                with open(path, "wb") as f:
                    f.write(raw)
                saved.append({"path": os.path.relpath(path, conv.dir), "mime": mime,
                              "label": img.get("label") or "Page snapshot"})
            conv.prune_snapshots()

            prompt = text or "(no message — please look at the attached page)"
            if body.get("context"):
                prompt += f"\n\n[{body['context']}]"
            pending = conv.meta.get("pending_files") or []
            if pending:
                listed = ", ".join(f"files/{p}" for p in pending)
                prompt += f"\n\n[New files uploaded: {listed}. Read them if they are relevant.]"
            conv.meta["pending_files"] = []

            conv.add_entry("user", text, images=[s["path"] for s in saved], kind=body.get("kind"))
            job = {"id": body.get("id") or uuid.uuid4().hex, "prompt": prompt, "images": saved,
                   "instructions": (body.get("instructions") or "").strip(), "model": model,
                   "attempt": 1, "started": now_ms()}
            self.start_worker(conv, job)
        return "started"

    def start_worker(self, conv, job):
        with conv.lock:
            conv.meta["job"] = job
            conv.cancelled = False
            conv.status = "Starting Claude…"
            conv.partial = ""
            conv.save()
            conv.worker = threading.Thread(target=self.run_job, args=(conv, job), daemon=True)
            conv.worker.start()

    def blocks_for(self, conv, job):
        prompt = job["prompt"]
        if job.get("resent"):
            prompt = f"{RESENT_NOTE}\n\n{prompt}"
        blocks = [{"type": "text", "text": prompt}]
        for img in job.get("images") or []:
            path = os.path.join(conv.dir, img["path"])
            try:
                with open(path, "rb") as f:
                    data = base64.b64encode(f.read()).decode("ascii")
            except OSError:
                blocks.append({"type": "text", "text": f"[{img['label']} — missing: {img['path']}]"})
                continue
            blocks.append({"type": "text", "text": f"[{img['label']} — saved as {img['path']}]"})
            blocks.append({"type": "image",
                           "source": {"type": "base64", "media_type": img["mime"], "data": data}})
        return blocks

    def run_job(self, conv, job):
        try:
            if not self.claude_cmd:
                raise RuntimeError(f"'{self.args.claude}' was not found. Install Claude Code in "
                                   "Termux: npm install -g @anthropic-ai/claude-code")
            system = BASE_SYSTEM_PROMPT + "\n" + (job["instructions"] or
                                                  "(none — give helpful, concise feedback)")
            while True:
                message = json.dumps({"type": "user", "message": {
                    "role": "user", "content": self.blocks_for(conv, job)}})
                result = self.invoke(conv, message, system, job)
                if result.get("resume_failed") and not conv.cancelled:
                    log(conv.key, "session could not be resumed, starting a new one")
                    conv.meta["session_id"] = None
                    conv.add_entry("event", "The previous Claude session was not found; "
                                            "started a new one.")
                    continue
                if result.get("transient") and not conv.cancelled and job["attempt"] < MAX_ATTEMPTS:
                    delay = RETRY_DELAYS[min(job["attempt"] - 1, len(RETRY_DELAYS) - 1)]
                    log(conv.key, f"attempt {job['attempt']} failed ({result['error']}); "
                                  f"retrying in {delay}s")
                    job["attempt"] += 1
                    job["resent"] = True
                    with conv.lock:
                        conv.save()
                    conv.add_entry("event", "Claude was interrupted; trying again.")
                    conv.status = "Trying again…"
                    time.sleep(delay)
                    continue
                break

            if conv.cancelled:
                conv.add_entry("event", "Cancelled.")
            elif result.get("error"):
                conv.add_entry("error", explain_failure(result["error"]))
            else:
                if result.get("session_id"):
                    conv.meta["session_id"] = result["session_id"]
                conv.add_entry("assistant", result.get("text", "").strip() or "(empty reply)",
                               cost_usd=result.get("cost"), duration_ms=result.get("duration_ms"))
        except Exception as e:  # noqa: BLE001 — every failure ends up in the chat
            log(conv.key, "error:", repr(e))
            conv.add_entry("error", str(e))
        finally:
            with conv.lock:
                conv.meta["job"] = None
                conv.status = ""
                conv.partial = ""
                conv.process = None
                conv.save()
            self.touch()

    def claude_command(self, conv, system, job):
        cmd = self.claude_cmd + ["-p",
                                 "--input-format", "stream-json",
                                 "--output-format", "stream-json",
                                 "--verbose",
                                 "--append-system-prompt", system,
                                 "--allowedTools", ALLOWED_TOOLS]
        if job.get("model"):
            cmd += ["--model", job["model"]]
        if conv.meta.get("session_id"):
            cmd += ["--resume", conv.meta["session_id"]]
        return cmd + list(self.args.extra_arg)

    def invoke(self, conv, message, system, job):
        cmd = self.claude_command(conv, system, job)
        resuming = "--resume" in cmd
        log(conv.key, f"attempt {job['attempt']}:", "resume" if resuming else "new session")
        # Own session: no controlling terminal, so nothing in Termux can send claude SIGHUP or
        # stop it; it can only end by itself, by cancel, or by the timeout.
        proc = subprocess.Popen(cmd, cwd=conv.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1,
                                start_new_session=True)
        with conv.lock:
            conv.process = proc
            conv.status = "Claude is thinking…"
        if conv.cancelled:
            proc.kill()

        stderr_lines = []
        stderr_thread = threading.Thread(target=lambda: stderr_lines.extend(proc.stderr), daemon=True)
        stderr_thread.start()
        timed_out = threading.Event()

        def on_timeout():
            timed_out.set()
            proc.kill()

        timer = threading.Timer(self.args.timeout, on_timeout)
        timer.start()
        result = {}
        texts = []
        try:
            try:
                proc.stdin.write(message + "\n")
                proc.stdin.close()
            except (BrokenPipeError, OSError):
                pass  # claude already exited; its exit code tells why
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                self.on_event(conv, event, texts, result)
                self.touch()
            proc.wait()
        finally:
            timer.cancel()
            stderr_thread.join(timeout=2)
            proc.stdout.close()
            proc.stderr.close()
        stderr = "".join(stderr_lines).strip()

        if conv.cancelled:
            return {"error": "Cancelled."}
        if timed_out.is_set():
            return {"error": f"Claude took longer than {self.args.timeout // 60} minutes and was stopped."}
        if "text" in result and not result.get("is_error"):
            return result
        detail = result.get("text") or stderr or f"claude exited with code {proc.returncode}"
        if resuming and "No conversation found" in (detail + stderr):
            return {"resume_failed": True}
        signal_name = killed_by(proc.returncode)
        if "text" not in result and (signal_name or (not stderr and not texts)):
            reason = f"stopped by {signal_name}" if signal_name else \
                f"exited (code {proc.returncode}) without answering"
            return {"error": f"Claude was {reason}. {stderr[-300:]}".strip(), "transient": True}
        return {"error": detail[-2000:]}

    def on_event(self, conv, event, texts, result):
        kind = event.get("type")
        if kind == "assistant":
            for block in (event.get("message") or {}).get("content") or []:
                if block.get("type") == "text" and block.get("text"):
                    texts.append(block["text"])
                    with conv.lock:
                        conv.partial = "\n\n".join(texts)
                        conv.status = "Claude is writing…"
                elif block.get("type") == "tool_use":
                    name = block.get("name", "tool")
                    inp = block.get("input") or {}
                    target = inp.get("file_path") or inp.get("pattern") or inp.get("path") or ""
                    if target and os.path.isabs(target):
                        target = os.path.relpath(target, conv.dir)
                    with conv.lock:
                        conv.status = f"{name} {target}".strip() + "…"
        elif kind == "result":
            result["is_error"] = bool(event.get("is_error"))
            result["text"] = event.get("result") or "\n\n".join(texts)
            result["session_id"] = event.get("session_id")
            result["cost"] = event.get("total_cost_usd")
            result["duration_ms"] = event.get("duration_ms")
        elif kind == "system" and event.get("subtype") == "init" and event.get("session_id"):
            # Remember the session as soon as it exists, so a retry resumes it.
            with conv.lock:
                conv.meta["session_id"] = event["session_id"]
                conv.save()

    # ---- other operations ----
    def cancel(self, conv):
        with conv.lock:
            if not conv.busy:
                return False
            conv.cancelled = True
            conv.status = "Cancelling…"
            proc = conv.process
        if proc and proc.poll() is None:
            proc.kill()
        return True

    def reset(self, conv, keep_files=True):
        with conv.lock:
            if conv.busy:
                return False
            conv.meta.update(session_id=None, pending_files=[], job=None)
            conv.history = []
            if not keep_files:
                for name in os.listdir(conv.files_dir):
                    try:
                        os.remove(os.path.join(conv.files_dir, name))
                    except OSError:
                        pass
            conv.save()
            return True

    def upload(self, conv, body):
        name = safe_file_name(body.get("name"))
        raw = base64.b64decode(body.get("data") or "", validate=True)
        with conv.lock:
            if conv.seen(body.get("id")):
                return None
            conv.remember(body.get("id"))
            path = unique_path(conv.files_dir, name)
            with open(path, "wb") as f:
                f.write(raw)
            stored = os.path.basename(path)
            conv.meta.setdefault("pending_files", []).append(stored)
            conv.add_entry("event", f"Uploaded {stored} ({max(1, len(raw) // 1024)} KB)")
        return stored

    def delete_file(self, conv, name):
        path = os.path.join(conv.files_dir, safe_file_name(name))
        with conv.lock:
            if not os.path.isfile(path):
                return False
            os.remove(path)
            gone = os.path.basename(path)
            conv.meta["pending_files"] = [p for p in conv.meta.get("pending_files") or [] if p != gone]
            conv.add_entry("event", f"Deleted {gone}")
        return True

    def stop_all(self):
        with self.lock:
            convs = list(self.conversations.values())
        for conv in convs:
            proc = conv.process
            if proc and proc.poll() is None:
                proc.kill()


class Handler(BaseHTTPRequestHandler):
    bridge: Bridge = None  # set by serve()
    server_version = "NotableClaudeBridge"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if self.bridge.args.verbose:
            log(self.address_string(), fmt % args)

    def send_json(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def fail(self, status, message):
        self.send_json(status, {"ok": False, "error": message})

    def authorized(self):
        given = self.headers.get("Authorization", "")
        given = given[len("Bearer "):] if given.startswith("Bearer ") else ""
        return hmac.compare_digest(given.strip().encode(), self.bridge.args.token.encode())

    def body(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_BODY_BYTES:
            raise ValueError(f"request too large ({length // (1024 * 1024)} MB)")
        raw = self.rfile.read(length) if length else b"{}"
        value = json.loads(raw.decode("utf-8") or "{}")
        if not isinstance(value, dict):
            raise ValueError("expected a JSON object")
        return value

    def route(self, method):
        b = self.bridge
        url = urlparse(self.path)
        parts = [unquote(p) for p in url.path.strip("/").split("/") if p]
        # Read the body before answering, so the connection stays usable.
        try:
            body = self.body() if method in ("POST", "DELETE") else {}
        except (ValueError, UnicodeDecodeError) as e:
            return self.fail(HTTPStatus.BAD_REQUEST, str(e))
        if not self.authorized():
            return self.fail(HTTPStatus.UNAUTHORIZED, "wrong or missing token")
        b.touch()
        try:
            if method == "GET" and parts == ["health"]:
                return self.send_json(200, {"ok": True, "version": b.version, "pid": os.getpid(),
                                            "started": b.started, "claude": b.claude_info})
            if method == "POST" and parts == ["shutdown"]:
                self.send_json(200, {"ok": True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return None
            if len(parts) >= 2 and parts[0] == "conversations":
                conv = b.conversation(parts[1])
                rest = parts[2:]
                if method == "GET" and rest == []:
                    return self.send_json(200, {"ok": True, **conv.snapshot()})
                if method == "POST" and rest == ["messages"]:
                    outcome = b.start_message(conv, body)
                    if outcome == "busy":
                        return self.fail(HTTPStatus.CONFLICT,
                                         "Claude is still answering the previous message")
                    return self.send_json(202, {"ok": True, "duplicate": outcome == "duplicate"})
                if method == "POST" and rest == ["files"]:
                    stored = b.upload(conv, body)
                    return self.send_json(200, {"ok": True, "name": stored, "duplicate": stored is None})
                if method == "DELETE" and len(rest) == 2 and rest[0] == "files":
                    if b.delete_file(conv, rest[1]):
                        return self.send_json(200, {"ok": True})
                    return self.fail(HTTPStatus.NOT_FOUND, "no such file")
                if method == "POST" and rest == ["cancel"]:
                    return self.send_json(200, {"ok": b.cancel(conv)})
                if method == "POST" and rest == ["reset"]:
                    if b.reset(conv, keep_files=bool(body.get("keep_files", True))):
                        return self.send_json(200, {"ok": True})
                    return self.fail(HTTPStatus.CONFLICT, "Claude is busy; cancel first")
            return self.fail(HTTPStatus.NOT_FOUND, f"unknown endpoint {method} {url.path}")
        except (ValueError, binascii.Error) as e:
            return self.fail(HTTPStatus.BAD_REQUEST, str(e))
        except Exception as e:  # noqa: BLE001 — never drop the connection without an answer
            log("internal error:", repr(e))
            return self.fail(HTTPStatus.INTERNAL_SERVER_ERROR, str(e))

    def do_GET(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def do_DELETE(self):
        self.route("DELETE")


def bind(args):
    """The server, retrying while an older bridge still holds the port."""
    deadline = time.monotonic() + args.bind_wait
    while True:
        try:
            return ThreadingHTTPServer((args.host, args.port), Handler)
        except OSError as e:
            if e.errno != errno.EADDRINUSE or time.monotonic() > deadline:
                raise
            time.sleep(0.25)


def watch_idle(bridge, server):
    """Stops the server once nothing has happened for --idle-timeout seconds."""
    while True:
        time.sleep(min(5.0, max(0.2, bridge.args.idle_timeout / 4)))
        if not bridge.any_busy() and bridge.idle_for() > bridge.args.idle_timeout:
            log(f"idle for {bridge.args.idle_timeout}s, exiting")
            server.shutdown()
            return


def parse_args(argv):
    p = argparse.ArgumentParser(description="Notable's bridge to Claude Code (started by the app).")
    p.add_argument("--token", default=os.environ.get("NOTABLE_BRIDGE_TOKEN", ""),
                   help="secret the app sends with every request (required)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--data-dir", default="~/.notable-claude")
    p.add_argument("--claude", default="claude", help="the Claude Code command")
    p.add_argument("--timeout", type=int, default=900, help="seconds before a reply is given up on")
    p.add_argument("--idle-timeout", type=float, default=900,
                   help="exit after this many seconds without requests or work")
    p.add_argument("--bind-wait", type=float, default=8, help="seconds to wait for a busy port")
    p.add_argument("--extra-arg", action="append", default=[], help="extra argument for claude")
    p.add_argument("--verbose", action="store_true", help="log every request")
    args = p.parse_args(argv)
    if len(args.token) < 16:
        p.error("--token (at least 16 characters) is required")
    return args


def serve(args, ready=None):
    """Runs the bridge until it is idle or shut down. [ready] is called with the server."""
    global _log_file
    data_dir = os.path.expanduser(args.data_dir)
    os.makedirs(data_dir, exist_ok=True)
    _log_file = os.path.join(data_dir, "bridge.log")
    try:
        if os.path.getsize(_log_file) > 512 * 1024:
            os.replace(_log_file, _log_file + ".1")
    except OSError:
        pass

    bridge = Bridge(args)
    Handler.bridge = bridge
    server = bind(args)
    server.daemon_threads = True
    pid_file = os.path.join(data_dir, "bridge.pid")
    with open(pid_file, "w") as f:
        f.write(str(os.getpid()))
    log(f"bridge {bridge.version[:12]} pid {os.getpid()} on http://{args.host}:{args.port}")

    threading.Thread(target=bridge.check_claude, daemon=True).start()
    threading.Thread(target=bridge.resume_interrupted_jobs, daemon=True).start()
    threading.Thread(target=watch_idle, args=(bridge, server), daemon=True).start()
    if ready:
        ready(server)
    try:
        server.serve_forever(poll_interval=0.2)
    finally:
        server.server_close()
        bridge.stop_all()
        try:
            with open(pid_file) as f:
                if f.read().strip() == str(os.getpid()):
                    os.remove(pid_file)
        except OSError:
            pass
        log("bridge stopped")


def main(argv=None):
    args = parse_args(argv)
    # Keep running if the terminal goes away; stop cleanly on TERM (a newer bridge replacing us).
    for name, handler in (("SIGHUP", signal.SIG_IGN), ("SIGTERM", lambda *_: sys.exit(0))):
        if hasattr(signal, name):
            try:
                signal.signal(getattr(signal, name), handler)
            except ValueError:  # not the main thread
                pass
    try:
        serve(args)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Notable <-> Claude Code bridge.

A tiny HTTP server, meant to run inside Termux on the same tablet as Notable. Notable sends it
snapshots of the page you are writing on, files and chat messages; the bridge hands them to the
`claude` CLI (Claude Code, in headless `-p` mode) and keeps one Claude session per notebook, so
Claude remembers the whole exercise across checks.

Only the Python standard library is used. Run it with:

    python notable_claude_bridge.py            # listens on 127.0.0.1:8765

and keep Termux running (`termux-wake-lock` helps). See tools/claude-bridge/README.md.

State lives in ~/.notable-claude/conversations/<notebook>/ :
    meta.json      title and Claude session id
    history.json   the chat as shown in the app
    files/         files you uploaded from the app (Claude reads them with its Read tool)
    snapshots/     every page snapshot sent, for reference

Since the session runs in that folder, you can also continue the same conversation by hand:
    cd ~/.notable-claude/conversations/<notebook> && claude --resume <session id>
"""

import argparse
import base64
import binascii
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

BRIDGE_VERSION = 1
MAX_BODY_BYTES = 64 * 1024 * 1024
MAX_SNAPSHOTS_KEPT = 200
IMAGE_TYPES = {"image/png", "image/jpeg", "image/gif", "image/webp"}

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


def log(*parts):
    print(time.strftime("%H:%M:%S"), *parts, file=sys.stderr, flush=True)


def now_ms():
    return int(time.time() * 1000)


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
    not always reach programs started from Python, which then fail with "Exec format error". So
    read the script's first line and start its interpreter ourselves.
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


class Conversation:
    """One notebook's chat: its folder, history and Claude session. Guarded by self.lock."""

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
        self.busy = False
        self.status = ""
        self.partial = ""
        self.process = None
        self.cancelled = False

    # ---- persistence ----
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

    def snapshot(self, since=0):
        with self.lock:
            return {
                "key": self.key,
                "title": self.meta.get("title", ""),
                "session_id": self.meta.get("session_id"),
                "busy": self.busy,
                "status": self.status,
                "partial": self.partial,
                "history": [e for e in self.history if e.get("time", 0) > since] if since else list(self.history),
                "files": self.files(),
                "folder": self.dir,
            }

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
        self.root = os.path.join(os.path.expanduser(args.data_dir), "conversations")
        os.makedirs(self.root, exist_ok=True)
        self.conversations = {}
        self.lock = threading.Lock()
        self.claude_path = shutil.which(args.claude) or (args.claude if os.path.exists(args.claude) else None)
        self.claude_cmd = launch_command(self.claude_path) if self.claude_path else None

    def conversation(self, key):
        folder = safe_key(key)
        with self.lock:
            conv = self.conversations.get(folder)
            if conv is None:
                conv = Conversation(self.root, key)
                self.conversations[folder] = conv
            return conv

    def list_conversations(self):
        out = []
        for name in sorted(os.listdir(self.root)):
            meta = read_json(os.path.join(self.root, name, "meta.json"), None)
            if meta:
                out.append({"key": meta.get("key", name), "title": meta.get("title", ""),
                            "updated": meta.get("updated", 0)})
        return sorted(out, key=lambda m: -m["updated"])

    def claude_version(self):
        if not self.claude_path:
            return None
        try:
            r = subprocess.run(self.claude_cmd + ["--version"], capture_output=True, text=True,
                               stdin=subprocess.DEVNULL, timeout=60)
            if r.returncode != 0:
                return f"error: {(r.stderr or r.stdout).strip()[-300:] or f'exit code {r.returncode}'}"
            return r.stdout.strip() or r.stderr.strip()
        except Exception as e:  # noqa: BLE001 — report anything as "not working"
            return f"error: {e}"

    # ---- sending a message ----
    def start_message(self, conv, body):
        text = (body.get("text") or "").strip()
        images = body.get("images") or []
        if not text and not images:
            raise ValueError("message needs text or an image")

        with conv.lock:
            if conv.busy:
                return False
            conv.busy = True
            conv.status = "Starting Claude…"
            conv.partial = ""
            conv.cancelled = False

        try:
            if body.get("title"):
                conv.meta["title"] = body["title"]

            saved_images = []
            blocks = []
            stamp = time.strftime("%Y%m%d-%H%M%S")
            for i, img in enumerate(images):
                mime = img.get("mime") or "image/png"
                if mime not in IMAGE_TYPES:
                    raise ValueError(f"unsupported image type {mime}")
                raw = base64.b64decode(img.get("data") or "", validate=True)
                ext = {"image/png": ".png", "image/jpeg": ".jpg", "image/gif": ".gif", "image/webp": ".webp"}[mime]
                path = unique_path(conv.snapshots_dir, f"{stamp}-{i + 1}{ext}")
                with open(path, "wb") as f:
                    f.write(raw)
                rel = os.path.relpath(path, conv.dir)
                saved_images.append(rel)
                label = img.get("label") or "Page snapshot"
                blocks.append({"type": "text", "text": f"[{label} — saved as {rel}]"})
                blocks.append({"type": "image",
                               "source": {"type": "base64", "media_type": mime, "data": img["data"]}})
            conv.prune_snapshots()

            # Files uploaded since the last message are announced once.
            pending = conv.meta.get("pending_files") or []
            prompt = text or "(no message — please look at the attached page)"
            if body.get("context"):
                prompt = f"{prompt}\n\n[{body['context']}]"
            if pending:
                listed = ", ".join(f"files/{p}" for p in pending)
                prompt += f"\n\n[New files uploaded: {listed}. Read them if they are relevant.]"
            blocks.insert(0, {"type": "text", "text": prompt})

            conv.add_entry("user", text, images=saved_images, kind=body.get("kind"))
            conv.meta["pending_files"] = []
            conv.save()
        except Exception:
            with conv.lock:
                conv.busy = False
                conv.status = ""
            raise

        instructions = (body.get("instructions") or "").strip()
        system = BASE_SYSTEM_PROMPT + "\n" + (instructions or "(none — give helpful, concise feedback)")
        thread = threading.Thread(target=self.run_claude, args=(conv, blocks, system), daemon=True)
        thread.start()
        return True

    def claude_command(self, conv, system, resume):
        cmd = self.claude_cmd + ["-p",
               "--input-format", "stream-json",
               "--output-format", "stream-json",
               "--verbose",
               "--append-system-prompt", system]
        if self.args.allowed_tools:
            cmd += ["--allowedTools", self.args.allowed_tools]
        if self.args.model:
            cmd += ["--model", self.args.model]
        if resume and conv.meta.get("session_id"):
            cmd += ["--resume", conv.meta["session_id"]]
        cmd += self.args.extra_arg
        return cmd

    def run_claude(self, conv, blocks, system):
        try:
            if not self.claude_path:
                raise RuntimeError(f"'{self.args.claude}' not found. Install Claude Code in Termux "
                                   "(npm install -g @anthropic-ai/claude-code) or pass --claude PATH.")
            message = json.dumps({"type": "user", "message": {"role": "user", "content": blocks}})
            result = self.invoke(conv, message, system, resume=True)
            if (result.get("resume_failed") and conv.meta.get("session_id") and not conv.cancelled):
                log(conv.key, "session could not be resumed, starting a new one")
                conv.meta["session_id"] = None
                conv.add_entry("event", "Previous Claude session was not found; started a new one.")
                result = self.invoke(conv, message, system, resume=False)
            if result.get("killed_by_signal") and not conv.cancelled:
                # Android or Termux sometimes stops background processes; one retry usually works.
                log(conv.key, "claude was stopped by a signal, retrying once:", result["error"])
                conv.add_entry("event", "Claude was interrupted; trying again.")
                result = self.invoke(conv, message, system, resume=True)

            if conv.cancelled:
                conv.add_entry("event", "Cancelled.")
            elif result.get("error"):
                conv.add_entry("error", result["error"])
            else:
                if result.get("session_id"):
                    conv.meta["session_id"] = result["session_id"]
                conv.add_entry("assistant", result.get("text", "").strip() or "(empty reply)",
                               cost_usd=result.get("cost"), duration_ms=result.get("duration_ms"))
        except Exception as e:  # noqa: BLE001 — every failure ends up in the chat
            log(conv.key, "error:", e)
            conv.add_entry("error", str(e))
        finally:
            with conv.lock:
                conv.busy = False
                conv.status = ""
                conv.partial = ""
                conv.process = None
                conv.save()

    def invoke(self, conv, message, system, resume):
        cmd = self.claude_command(conv, system, resume)
        log(conv.key, "running claude", "(resume)" if "--resume" in cmd else "(new session)")
        # Own session: no controlling terminal, so a Termux terminal that hangs up or is paused
        # in the background can't send claude SIGHUP or stop it.
        proc = subprocess.Popen(cmd, cwd=conv.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True, encoding="utf-8", bufsize=1,
                                start_new_session=True)
        with conv.lock:
            conv.process = proc
            conv.status = "Claude is thinking…"

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
            proc.stdin.write(message + "\n")
            proc.stdin.close()
            for line in proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                self.on_event(conv, event, texts, result)
            proc.wait()
        finally:
            timer.cancel()
            stderr_thread.join(timeout=2)
            proc.stdout.close()
            proc.stderr.close()
        stderr = "".join(stderr_lines).strip()

        if conv.cancelled:
            return {"error": "Cancelled."}
        if "text" in result and not result.get("is_error"):
            return result
        detail = result.get("text") or stderr or f"claude exited with code {proc.returncode}"
        if timed_out.is_set():
            return {"error": f"Claude took longer than {self.args.timeout}s and was stopped."}
        if resume and ("No conversation found" in detail or "No conversation found" in stderr):
            return {"resume_failed": True}
        signal_name = killed_by(proc.returncode)
        if signal_name and "text" not in result:
            return {"error": f"Claude was stopped by {signal_name} before it finished "
                             f"(exit code {proc.returncode}). {stderr[-500:]}".strip(),
                    "killed_by_signal": True}
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
                    if target:
                        target = os.path.relpath(target, conv.dir) if os.path.isabs(target) else target
                    with conv.lock:
                        conv.status = f"{name} {target}".strip() + "…"
        elif kind == "result":
            result["is_error"] = bool(event.get("is_error"))
            result["text"] = event.get("result") or "\n\n".join(texts)
            result["session_id"] = event.get("session_id")
            result["cost"] = event.get("total_cost_usd")
            result["duration_ms"] = event.get("duration_ms")
        elif kind == "system" and event.get("subtype") == "init" and event.get("session_id"):
            # Remember the session as soon as it exists, so a crash mid-reply still resumes it.
            with conv.lock:
                conv.meta["session_id"] = event["session_id"]

    # ---- other operations ----
    def cancel(self, conv):
        with conv.lock:
            proc = conv.process
            if not conv.busy:
                return False
            conv.cancelled = True
            conv.status = "Cancelling…"
        if proc:
            proc.kill()
        return True

    def reset(self, conv, keep_files=True):
        with conv.lock:
            if conv.busy:
                return False
            conv.meta["session_id"] = None
            conv.meta["pending_files"] = []
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
            path = unique_path(conv.files_dir, name)
            with open(path, "wb") as f:
                f.write(raw)
            stored = os.path.basename(path)
            conv.meta.setdefault("pending_files", []).append(stored)
            conv.add_entry("event", f"Uploaded {stored} ({len(raw) // 1024} KB)")
        return stored

    def delete_file(self, conv, name):
        path = os.path.join(conv.files_dir, safe_file_name(name))
        with conv.lock:
            if not os.path.isfile(path):
                return False
            os.remove(path)
            pending = conv.meta.get("pending_files") or []
            conv.meta["pending_files"] = [p for p in pending if p != os.path.basename(path)]
            conv.add_entry("event", f"Deleted {os.path.basename(path)}")
        return True


class Handler(BaseHTTPRequestHandler):
    bridge: Bridge = None  # set in main()
    server_version = f"NotableClaudeBridge/{BRIDGE_VERSION}"

    def log_message(self, fmt, *args):
        if self.bridge.args.verbose:
            log(self.address_string(), fmt % args)

    # ---- helpers ----
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
        token = self.bridge.args.token
        if not token:
            return True
        given = self.headers.get("Authorization", "")
        if given.startswith("Bearer "):
            given = given[len("Bearer "):]
        else:
            given = self.headers.get("X-Notable-Token", "")
        return hmac.compare_digest(given.strip(), token)

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
        if not self.authorized():
            return self.fail(HTTPStatus.UNAUTHORIZED, "wrong or missing bridge token")
        url = urlparse(self.path)
        parts = [unquote(p) for p in url.path.strip("/").split("/") if p]
        query = dict(p.split("=", 1) for p in url.query.split("&") if "=" in p)
        b = self.bridge
        try:
            if method == "GET" and parts == ["health"]:
                return self.send_json(200, {"ok": True, "version": BRIDGE_VERSION,
                                            "claude": b.claude_path, "claude_version": b.claude_version()})
            if method == "GET" and parts == ["conversations"]:
                return self.send_json(200, {"ok": True, "conversations": b.list_conversations()})
            if len(parts) >= 2 and parts[0] == "conversations":
                conv = b.conversation(parts[1])
                rest = parts[2:]
                if method == "GET" and rest == []:
                    return self.send_json(200, {"ok": True, **conv.snapshot(int(query.get("since", 0) or 0))})
                if method == "POST" and rest == ["messages"]:
                    if b.start_message(conv, self.body()):
                        return self.send_json(202, {"ok": True})
                    return self.fail(HTTPStatus.CONFLICT, "Claude is still answering the previous message")
                if method == "POST" and rest == ["files"]:
                    return self.send_json(200, {"ok": True, "name": b.upload(conv, self.body())})
                if method == "DELETE" and len(rest) == 2 and rest[0] == "files":
                    if b.delete_file(conv, rest[1]):
                        return self.send_json(200, {"ok": True})
                    return self.fail(HTTPStatus.NOT_FOUND, "no such file")
                if method == "POST" and rest == ["cancel"]:
                    return self.send_json(200, {"ok": b.cancel(conv)})
                if method == "POST" and rest == ["reset"]:
                    keep = bool(self.body().get("keep_files", True))
                    if b.reset(conv, keep_files=keep):
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


def main(argv=None):
    p = argparse.ArgumentParser(description="Bridge between the Notable app and Claude Code.")
    p.add_argument("--host", default=os.environ.get("NOTABLE_BRIDGE_HOST", "127.0.0.1"),
                   help="address to listen on (default 127.0.0.1: only apps on this device)")
    p.add_argument("--port", type=int, default=int(os.environ.get("NOTABLE_BRIDGE_PORT", "8765")))
    p.add_argument("--token", default=os.environ.get("NOTABLE_BRIDGE_TOKEN", ""),
                   help="require this token from the app (set the same token in Notable's settings)")
    p.add_argument("--data-dir", default=os.environ.get("NOTABLE_BRIDGE_DIR", "~/.notable-claude"))
    p.add_argument("--claude", default=os.environ.get("NOTABLE_BRIDGE_CLAUDE", "claude"),
                   help="the Claude Code executable")
    p.add_argument("--model", default=os.environ.get("NOTABLE_BRIDGE_MODEL", ""),
                   help="model alias or id passed to claude --model (default: Claude Code's default)")
    p.add_argument("--allowed-tools", default=os.environ.get("NOTABLE_BRIDGE_TOOLS", "Read,Glob,Grep"),
                   help="tools Claude may use without asking (default: Read,Glob,Grep)")
    p.add_argument("--timeout", type=int, default=int(os.environ.get("NOTABLE_BRIDGE_TIMEOUT", "600")),
                   help="seconds before a reply is given up on")
    p.add_argument("--extra-arg", action="append", default=[],
                   help="extra argument passed to claude (repeatable)")
    p.add_argument("--verbose", action="store_true", help="log every request")
    args = p.parse_args(argv)

    # Keep running when the Termux terminal hangs up (session closed, app paused).
    if hasattr(signal, "SIGHUP"):
        try:
            signal.signal(signal.SIGHUP, signal.SIG_IGN)
        except ValueError:  # not the main thread (tests)
            pass

    bridge = Bridge(args)
    Handler.bridge = bridge
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    log(f"Notable Claude bridge v{BRIDGE_VERSION} on http://{args.host}:{args.port}")
    log("claude:", " ".join(bridge.claude_cmd) if bridge.claude_cmd else f"NOT FOUND ('{args.claude}')")
    if bridge.claude_cmd:
        log("claude --version:", bridge.claude_version())
    log("data:", bridge.root)
    if args.token:
        log("token required")
    elif args.host not in ("127.0.0.1", "localhost", "::1"):
        log("WARNING: listening beyond this device without --token; anyone on the network can use it")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        # claude runs in its own session, so Ctrl+C doesn't reach it: stop it here.
        for conv in list(bridge.conversations.values()):
            if conv.process and conv.process.poll() is None:
                conv.process.kill()


if __name__ == "__main__":
    main()

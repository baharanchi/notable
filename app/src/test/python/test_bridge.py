"""Tests for the Termux bridge (app/src/main/assets/claude/bridge.py), against a fake `claude`.

    python3 -m unittest discover -s app/src/test/python
"""
import base64
import hashlib
import json
import os
import socket
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
BRIDGE_PATH = os.path.join(HERE, "..", "..", "main", "assets", "claude", "bridge.py")
sys.path.insert(0, os.path.dirname(BRIDGE_PATH))
import bridge as bridge_mod  # noqa: E402

TOKEN = "t" * 32

# Behaves like `claude -p --input-format stream-json --output-format stream-json`. Records each
# call in calls.jsonl (in its working directory) and honours a few magic words in the message.
FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, os, sys, time
args = sys.argv[1:]
if args == ["--version"]:
    print("9.9.9 (Claude Code)")
    sys.exit(0)
msg = json.loads(sys.stdin.readline())
blocks = msg["message"]["content"]
text = blocks[0]["text"]
calls = os.path.join(os.getcwd(), "calls.jsonl")
with open(calls, "a") as f:
    f.write(json.dumps({"args": args, "blocks": [b["type"] for b in blocks], "text": text,
                        "own_session": os.getsid(0) == os.getpid()}) + "\n")
attempt = sum(1 for _ in open(calls))
if "--resume" in args and args[args.index("--resume") + 1] == "gone":
    print("No conversation found with session ID: gone", file=sys.stderr)
    sys.exit(1)
sid = args[args.index("--resume") + 1] if "--resume" in args else "sess-%d" % attempt
print(json.dumps({"type": "system", "subtype": "init", "session_id": sid}), flush=True)
if "SLOW" in text:
    time.sleep(30)
if "CRASH" in text:
    print("boom", file=sys.stderr)
    sys.exit(3)
if "HUP_ONCE" in text and attempt == 1:
    sys.exit(129)
if "HUP_ALWAYS" in text:
    sys.exit(129)
if "SILENT_ONCE" in text and attempt == 1:
    os._exit(1)
if "LOGIN" in text:
    print(json.dumps({"type": "result", "is_error": True, "session_id": sid,
                      "result": "Invalid API key · Please run /login"}), flush=True)
    sys.exit(1)
print(json.dumps({"type": "assistant", "message": {"content": [
    {"type": "tool_use", "name": "Read", "input": {"file_path": os.path.join(os.getcwd(), "files", "x.pdf")}}]}}), flush=True)
reply = "images=%d text=%s" % (sum(b["type"] == "image" for b in blocks), text)
print(json.dumps({"type": "assistant", "message": {"content": [{"type": "text", "text": reply}]}}), flush=True)
print(json.dumps({"type": "result", "subtype": "success", "is_error": False, "result": reply,
                  "session_id": sid, "total_cost_usd": 0.01, "duration_ms": 5}), flush=True)
'''

PNG_1PX = base64.b64encode(bytes.fromhex(
    "89504e470d0a1a0a0000000d4948445200000001000000010806000000"
    "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082")).decode()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class BridgeHarness(unittest.TestCase):
    """Starts a bridge on a free port with a fake claude; one per test."""

    idle_timeout = 60

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = os.path.join(self.tmp.name, "data")
        self.fake = os.path.join(self.tmp.name, "claude")
        with open(self.fake, "w") as f:
            f.write(FAKE_CLAUDE)
        os.chmod(self.fake, os.stat(self.fake).st_mode | stat.S_IEXEC)
        bridge_mod.RETRY_DELAYS = (0.1, 0.1)
        self.port = None
        self.server = None
        self.thread = None

    def start(self, **overrides):
        self.port = overrides.pop("port", None) or free_port()
        argv = ["--port", str(self.port), "--token", TOKEN, "--data-dir", self.data,
                "--claude", self.fake, "--timeout", "3", "--idle-timeout", str(self.idle_timeout)]
        for k, v in overrides.items():
            argv += ["--" + k.replace("_", "-"), str(v)]
        args = bridge_mod.parse_args(argv)
        ready = threading.Event()

        def on_ready(server):
            self.server = server
            ready.set()

        self.thread = threading.Thread(target=bridge_mod.serve, args=(args, on_ready), daemon=True)
        self.thread.start()
        self.assertTrue(ready.wait(10), "bridge did not start")

    def tearDown(self):
        if self.thread and self.thread.is_alive():
            self.server.shutdown()
            self.thread.join(5)
        self.tmp.cleanup()

    def req(self, method, path, body=None, token=TOKEN):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data,
                                   headers=headers, method=method)
        try:
            with urllib.request.urlopen(r, timeout=10) as f:
                return f.status, json.load(f)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def send(self, text, key="nb1", **extra):
        return self.req("POST", f"/conversations/{key}/messages", {"text": text, **extra})

    def wait_idle(self, key="nb1", timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            _, conv = self.req("GET", f"/conversations/{key}")
            if not conv["busy"]:
                return conv
            time.sleep(0.05)
        self.fail("bridge stayed busy")

    def calls(self, key="nb1"):
        path = os.path.join(self.data, "conversations", key, "calls.jsonl")
        if not os.path.exists(path):
            return []
        with open(path) as f:
            return [json.loads(line) for line in f]

    def roles(self, conv):
        return [e["role"] for e in conv["history"]]


class ApiTest(BridgeHarness):
    def setUp(self):
        super().setUp()
        self.start()

    def test_every_request_needs_the_token(self):
        self.assertEqual(self.req("GET", "/health", token=None)[0], 401)
        self.assertEqual(self.req("GET", "/health", token="x" * 32)[0], 401)
        self.assertEqual(self.req("POST", "/conversations/nb1/messages", {"text": "hi"}, token=None)[0], 401)

    def test_health_reports_version_and_claude(self):
        with open(BRIDGE_PATH, "rb") as f:
            expected = hashlib.sha256(f.read()).hexdigest()
        deadline = time.time() + 10
        while True:
            status, health = self.req("GET", "/health")
            if health["claude"]["checked"] or time.time() > deadline:
                break
            time.sleep(0.05)
        self.assertEqual(status, 200)
        self.assertEqual(health["version"], expected)
        self.assertEqual(health["pid"], os.getpid())
        self.assertEqual(health["claude"]["version"], "9.9.9 (Claude Code)")

    def test_message_with_snapshot_then_resume(self):
        status, _ = self.send("check", instructions="Never reveal answers.", title="Algebra",
                              context="page 2/5", model="sonnet",
                              images=[{"mime": "image/png", "data": PNG_1PX, "label": "Snap"}])
        self.assertEqual(status, 202)
        conv = self.wait_idle()
        self.assertEqual(conv["title"], "Algebra")
        self.assertEqual(conv["session_id"], "sess-1")
        self.assertEqual(self.roles(conv), ["user", "assistant"])
        self.assertIn("images=1", conv["history"][1]["text"])
        self.assertTrue(os.path.isfile(os.path.join(conv["folder"], conv["history"][0]["images"][0])))

        first = self.calls()[0]
        args = first["args"]
        self.assertIn("Never reveal answers.", args[args.index("--append-system-prompt") + 1])
        self.assertEqual(args[args.index("--model") + 1], "sonnet")
        self.assertEqual(args[args.index("--allowedTools") + 1], "Read,Glob,Grep")
        self.assertNotIn("--resume", args)
        self.assertIn("page 2/5", first["text"])
        self.assertTrue(first["own_session"], "claude must not share the terminal's session")

        self.send("hint please")
        self.wait_idle()
        second = self.calls()[1]["args"]
        self.assertEqual(second[second.index("--resume") + 1], "sess-1")

    def test_a_retried_request_runs_once(self):
        self.assertEqual(self.send("hello", id="m-1")[1]["duplicate"], False)
        self.assertEqual(self.send("hello", id="m-1")[1]["duplicate"], True)
        conv = self.wait_idle()
        self.assertEqual(self.send("hello", id="m-1")[1]["duplicate"], True)
        self.wait_idle()
        self.assertEqual(len(self.calls()), 1)
        self.assertEqual(self.roles(conv), ["user", "assistant"])

    def test_second_message_while_busy_is_refused_and_cancel_works(self):
        self.send("SLOW", id="a")
        status, _ = self.send("again", id="b")
        self.assertEqual(status, 409)
        time.sleep(0.3)
        self.assertTrue(self.req("POST", "/conversations/nb1/cancel", {})[1]["ok"])
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["text"], "Cancelled.")
        # The refused message was not remembered, so sending it again now works.
        self.assertEqual(self.send("again", id="b")[1]["duplicate"], False)

    def test_timeout_is_an_error_without_retry(self):
        self.send("SLOW")
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("longer than", conv["history"][-1]["text"])
        self.assertEqual(len(self.calls()), 1)

    def test_crash_with_an_error_message_is_not_retried(self):
        self.send("CRASH")
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("boom", conv["history"][-1]["text"])
        self.assertEqual(len(self.calls()), 1)

    def test_hangup_is_retried_and_the_retry_says_so(self):
        self.send("HUP_ONCE")
        conv = self.wait_idle()
        self.assertEqual(self.roles(conv), ["user", "event", "assistant"])
        retry = self.calls()[1]
        self.assertIn("sent again", retry["text"])
        self.assertEqual(retry["args"][retry["args"].index("--resume") + 1], "sess-1")

    def test_exit_without_any_output_is_retried(self):
        self.send("SILENT_ONCE")
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "assistant")
        self.assertEqual(len(self.calls()), 2)

    def test_repeated_hangups_give_up_naming_the_signal(self):
        self.send("HUP_ALWAYS")
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("SIGHUP", conv["history"][-1]["text"])
        self.assertEqual(len(self.calls()), bridge_mod.MAX_ATTEMPTS)

    def test_login_problem_says_what_to_do(self):
        self.send("LOGIN")
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("log in", conv["history"][-1]["text"])
        self.assertEqual(len(self.calls()), 1)

    def test_lost_session_starts_a_new_one(self):
        conv_dir = os.path.join(self.data, "conversations", "nb2")
        os.makedirs(conv_dir)
        with open(os.path.join(conv_dir, "meta.json"), "w") as f:
            json.dump({"key": "nb2", "session_id": "gone"}, f)
        self.send("hello", key="nb2")
        conv = self.wait_idle("nb2")
        self.assertEqual(self.roles(conv), ["user", "event", "assistant"])
        self.assertEqual(conv["session_id"], "sess-2")

    def test_uploads_are_deduplicated_sanitised_and_announced_once(self):
        data = base64.b64encode(b"%PDF-1.4 fake").decode()
        status, body = self.req("POST", "/conversations/nb1/files", {"id": "u1", "name": "../../evil.pdf", "data": data})
        self.assertEqual((status, body["name"]), (200, "evil.pdf"))
        self.assertTrue(self.req("POST", "/conversations/nb1/files", {"id": "u1", "name": "evil.pdf", "data": data})[1]["duplicate"])
        self.assertEqual(self.req("POST", "/conversations/nb1/files", {"id": "u2", "name": "evil.pdf", "data": data})[1]["name"], "evil-2.pdf")

        self.send("first")
        self.wait_idle()
        self.send("second")
        conv = self.wait_idle()
        first, second = self.calls()
        self.assertIn("files/evil.pdf", first["text"])
        self.assertNotIn("New files uploaded", second["text"])
        self.assertEqual({f["name"] for f in conv["files"]}, {"evil.pdf", "evil-2.pdf"})
        self.assertEqual(self.req("DELETE", "/conversations/nb1/files/evil.pdf")[0], 200)
        self.assertEqual(self.req("DELETE", "/conversations/nb1/files/evil.pdf")[0], 404)

    def test_reset_clears_history_and_session(self):
        self.send("hi")
        self.wait_idle()
        self.assertEqual(self.req("POST", "/conversations/nb1/reset", {"keep_files": True})[0], 200)
        conv = self.wait_idle()
        self.assertEqual(conv["history"], [])
        self.assertIsNone(conv["session_id"])

    def test_bad_requests(self):
        self.assertEqual(self.send("")[0], 400)
        self.assertEqual(self.send("x", images=[{"mime": "image/png", "data": "!!"}])[0], 400)
        self.assertEqual(self.send("x", images=[{"mime": "text/html", "data": PNG_1PX}])[0], 400)
        self.assertEqual(self.send("x", model="sonnet; rm -rf ~")[0], 400)
        self.assertEqual(self.req("GET", "/nope")[0], 404)
        self.assertFalse(self.wait_idle()["busy"])

    def test_shutdown_endpoint_stops_the_bridge(self):
        self.assertEqual(self.req("POST", "/shutdown", {})[0], 200)
        self.thread.join(5)
        self.assertFalse(self.thread.is_alive())
        self.assertFalse(os.path.exists(os.path.join(self.data, "bridge.pid")))


class LifecycleTest(BridgeHarness):
    idle_timeout = 0.6

    def test_exits_when_idle_but_not_while_working(self):
        self.start(timeout=30)
        self.send("SLOW")  # runs past the idle timeout
        time.sleep(1.5)
        self.assertTrue(self.thread.is_alive(), "must not exit while Claude is answering")
        self.req("POST", "/conversations/nb1/cancel", {})
        self.thread.join(8)
        self.assertFalse(self.thread.is_alive(), "should exit once idle")

    def test_waits_for_a_busy_port(self):
        port = free_port()
        holder = socket.socket()
        holder.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        holder.bind(("127.0.0.1", port))
        holder.listen()
        threading.Timer(1.0, holder.close).start()
        started = time.time()
        self.start(port=port)
        self.assertGreater(time.time() - started, 0.8)
        self.assertEqual(self.req("GET", "/health")[0], 200)

    def test_interrupted_job_is_resumed_after_a_restart(self):
        conv_dir = os.path.join(self.data, "conversations", "nb1")
        os.makedirs(os.path.join(conv_dir, "snapshots"))
        with open(os.path.join(conv_dir, "snapshots", "s.png"), "wb") as f:
            f.write(base64.b64decode(PNG_1PX))
        job = {"id": "j", "prompt": "check my page", "instructions": "", "model": "", "attempt": 1,
               "images": [{"path": "snapshots/s.png", "mime": "image/png", "label": "Snap"}]}
        with open(os.path.join(conv_dir, "meta.json"), "w") as f:
            json.dump({"key": "nb1", "job": job}, f)
        with open(os.path.join(conv_dir, "history.json"), "w") as f:
            json.dump([{"id": "u", "role": "user", "text": "check my page", "time": 1}], f)
        self.start()
        conv = self.wait_idle()
        self.assertEqual(self.roles(conv), ["user", "event", "assistant"])
        call = self.calls()[0]
        self.assertIn("sent again", call["text"])
        self.assertIn("image", call["blocks"])


class HelpersTest(unittest.TestCase):
    def test_killed_by(self):
        self.assertEqual(bridge_mod.killed_by(129), "SIGHUP")
        self.assertEqual(bridge_mod.killed_by(-9), "SIGKILL")
        self.assertEqual(bridge_mod.killed_by(137), "SIGKILL")
        self.assertIsNone(bridge_mod.killed_by(0))
        self.assertIsNone(bridge_mod.killed_by(1))
        self.assertIsNone(bridge_mod.killed_by(None))

    def script(self, content):
        tmp = tempfile.NamedTemporaryFile("w", delete=False, suffix=".sh")
        tmp.write(content)
        tmp.close()
        os.chmod(tmp.name, 0o755)
        self.addCleanup(os.remove, tmp.name)
        return tmp.name

    def run_cmd(self, path):
        cmd = bridge_mod.launch_command(path) + ["--version"]
        return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()

    def test_env_shebang_starts_the_interpreter_directly(self):
        path = self.script("#!/usr/bin/env python3\nimport sys; print('v', sys.argv[1])\n")
        cmd = bridge_mod.launch_command(path)
        self.assertTrue(os.path.basename(cmd[0]).startswith("python3"))
        self.assertEqual(self.run_cmd(path), "v --version")

    def test_missing_interpreter_path_is_found_on_path(self):
        path = self.script("#!/nonexistent/bin/sh\necho v \"$1\"\n")
        self.assertNotEqual(bridge_mod.launch_command(path)[0], "/nonexistent/bin/sh")
        self.assertEqual(self.run_cmd(path), "v --version")

    def test_script_without_shebang_runs_in_a_shell(self):
        self.assertEqual(self.run_cmd(self.script("echo v \"$1\"\n")), "v --version")

    def test_binaries_run_as_they_are(self):
        self.assertEqual(bridge_mod.launch_command(sys.executable), [sys.executable])

    def test_claude_is_found_where_installers_put_it(self):
        with tempfile.TemporaryDirectory() as home:
            local = os.path.join(home, ".local", "bin")
            os.makedirs(local)
            exe = os.path.join(local, "claude-test-only")
            with open(exe, "w") as f:
                f.write("#!/bin/sh\n")
            os.chmod(exe, 0o755)
            old = os.environ.get("HOME")
            os.environ["HOME"] = home
            try:
                self.assertEqual(bridge_mod.find_claude("claude-test-only"), exe)
                self.assertIsNone(bridge_mod.find_claude("claude-nowhere"))
            finally:
                os.environ["HOME"] = old

    def test_token_is_required(self):
        with self.assertRaises(SystemExit):
            bridge_mod.parse_args(["--token", "short"])


if __name__ == "__main__":
    unittest.main()

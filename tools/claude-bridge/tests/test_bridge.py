"""Tests for notable_claude_bridge.py, run against a fake `claude` executable.

    python -m unittest discover -s tools/claude-bridge/tests
"""
import base64
import json
import os
import socket
import stat
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import notable_claude_bridge as bridge_mod  # noqa: E402

# Behaves like `claude -p --input-format stream-json --output-format stream-json`: echoes what it
# received, records its arguments, and honours a few magic words in the message.
FAKE_CLAUDE = r'''#!/usr/bin/env python3
import json, os, sys, time
args = sys.argv[1:]
if args == ["--version"]:
    print("9.9.9 (Claude Code)")
    sys.exit(0)
msg = json.loads(sys.stdin.readline())
blocks = msg["message"]["content"]
text = blocks[0]["text"]
with open(os.path.join(os.getcwd(), "calls.jsonl"), "a") as f:
    f.write(json.dumps({"args": args, "blocks": [b["type"] for b in blocks], "text": text}) + "\n")
if "--resume" in args and args[args.index("--resume") + 1] == "gone":
    print("No conversation found with session ID: gone", file=sys.stderr)
    sys.exit(1)
if "SLOW" in text:
    time.sleep(30)
if "CRASH" in text:
    print("boom", file=sys.stderr)
    sys.exit(3)
sid = args[args.index("--resume") + 1] if "--resume" in args else "sess-1"
print(json.dumps({"type": "system", "subtype": "init", "session_id": sid}), flush=True)
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


class BridgeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        fake = os.path.join(self.tmp.name, "claude")
        with open(fake, "w") as f:
            f.write(FAKE_CLAUDE)
        os.chmod(fake, os.stat(fake).st_mode | stat.S_IEXEC)
        self.port = free_port()
        args = ["--port", str(self.port), "--data-dir", os.path.join(self.tmp.name, "data"),
                "--claude", fake, "--token", "t0k", "--timeout", "3"]
        parsed = bridge_mod.argparse.ArgumentParser()
        # Reuse main()'s parser by running it up to server creation.
        self.server = None
        orig = bridge_mod.ThreadingHTTPServer

        def capture(addr, handler):
            self.server = orig(addr, handler)
            return self.server

        bridge_mod.ThreadingHTTPServer = capture
        self.thread = threading.Thread(target=bridge_mod.main, args=(args,), daemon=True)
        self.thread.start()
        for _ in range(100):
            if self.server is not None:
                break
            time.sleep(0.02)
        bridge_mod.ThreadingHTTPServer = orig
        del parsed

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def req(self, method, path, body=None, token="t0k"):
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = "Bearer " + token
        data = json.dumps(body).encode() if body is not None else None
        r = urllib.request.Request(f"http://127.0.0.1:{self.port}{path}", data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(r) as f:
                return f.status, json.load(f)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def wait_idle(self, key="nb1"):
        for _ in range(200):
            _, conv = self.req("GET", f"/conversations/{key}")
            if not conv["busy"]:
                return conv
            time.sleep(0.05)
        self.fail("bridge stayed busy")

    def calls(self, key="nb1"):
        path = os.path.join(self.tmp.name, "data", "conversations", key, "calls.jsonl")
        with open(path) as f:
            return [json.loads(line) for line in f]

    def test_token_required(self):
        self.assertEqual(self.req("GET", "/health", token=None)[0], 401)
        self.assertEqual(self.req("GET", "/health", token="wrong")[0], 401)
        status, health = self.req("GET", "/health")
        self.assertEqual(status, 200)
        self.assertTrue(health["claude"])

    def test_message_with_snapshot_then_resume(self):
        status, _ = self.req("POST", "/conversations/nb1/messages", {
            "text": "check", "instructions": "Never reveal answers.", "title": "Algebra",
            "context": "page 2/5", "images": [{"mime": "image/png", "data": PNG_1PX, "label": "Snap"}]})
        self.assertEqual(status, 202)
        conv = self.wait_idle()
        self.assertEqual(conv["title"], "Algebra")
        self.assertEqual(conv["session_id"], "sess-1")
        roles = [e["role"] for e in conv["history"]]
        self.assertEqual(roles, ["user", "assistant"])
        self.assertEqual(len(conv["history"][0]["images"]), 1)
        self.assertIn("images=1", conv["history"][1]["text"])
        snap = os.path.join(conv["folder"], conv["history"][0]["images"][0])
        self.assertTrue(os.path.isfile(snap))

        call = self.calls()[0]
        self.assertIn("--append-system-prompt", call["args"])
        system = call["args"][call["args"].index("--append-system-prompt") + 1]
        self.assertIn("Never reveal answers.", system)
        self.assertNotIn("--resume", call["args"])
        self.assertIn("page 2/5", call["text"])

        self.req("POST", "/conversations/nb1/messages", {"text": "hint please", "instructions": ""})
        self.wait_idle()
        second = self.calls()[1]
        self.assertEqual(second["args"][second["args"].index("--resume") + 1], "sess-1")

    def test_busy_conversation_rejects_second_message_and_cancels(self):
        self.req("POST", "/conversations/nb1/messages", {"text": "SLOW"})
        status, body = self.req("POST", "/conversations/nb1/messages", {"text": "again"})
        self.assertEqual(status, 409)
        time.sleep(0.3)
        self.assertTrue(self.req("POST", "/conversations/nb1/cancel", {})[1]["ok"])
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["text"], "Cancelled.")

    def test_timeout_and_crash_become_errors(self):
        self.req("POST", "/conversations/nb1/messages", {"text": "SLOW"})
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("longer than 3s", conv["history"][-1]["text"])

        self.req("POST", "/conversations/nb1/messages", {"text": "CRASH"})
        conv = self.wait_idle()
        self.assertEqual(conv["history"][-1]["role"], "error")
        self.assertIn("boom", conv["history"][-1]["text"])

    def test_lost_session_starts_a_new_one(self):
        conv_dir = os.path.join(self.tmp.name, "data", "conversations", "nb1")
        os.makedirs(conv_dir, exist_ok=True)
        with open(os.path.join(conv_dir, "meta.json"), "w") as f:
            json.dump({"key": "nb1", "session_id": "gone"}, f)
        self.req("POST", "/conversations/nb1/messages", {"text": "hello"})
        conv = self.wait_idle()
        self.assertEqual(conv["session_id"], "sess-1")
        self.assertEqual([e["role"] for e in conv["history"]], ["user", "event", "assistant"])

    def test_upload_is_announced_once_and_names_are_sanitised(self):
        data = base64.b64encode(b"%PDF-1.4 fake").decode()
        status, body = self.req("POST", "/conversations/nb1/files", {"name": "../../evil.pdf", "data": data})
        self.assertEqual((status, body["name"]), (200, "evil.pdf"))
        _, again = self.req("POST", "/conversations/nb1/files", {"name": "evil.pdf", "data": data})
        self.assertEqual(again["name"], "evil-2.pdf")

        self.req("POST", "/conversations/nb1/messages", {"text": "first"})
        self.wait_idle()
        self.req("POST", "/conversations/nb1/messages", {"text": "second"})
        conv = self.wait_idle()
        first, second = self.calls()
        self.assertIn("files/evil.pdf", first["text"])
        self.assertNotIn("New files uploaded", second["text"])
        self.assertEqual({f["name"] for f in conv["files"]}, {"evil.pdf", "evil-2.pdf"})

        self.assertEqual(self.req("DELETE", "/conversations/nb1/files/evil.pdf")[0], 200)
        self.assertEqual(self.req("DELETE", "/conversations/nb1/files/evil.pdf")[0], 404)

    def test_reset_clears_history_and_session(self):
        self.req("POST", "/conversations/nb1/messages", {"text": "hi"})
        self.wait_idle()
        self.assertEqual(self.req("POST", "/conversations/nb1/reset", {"keep_files": True})[0], 200)
        conv = self.wait_idle()
        self.assertEqual(conv["history"], [])
        self.assertIsNone(conv["session_id"])

    def test_bad_requests(self):
        self.assertEqual(self.req("POST", "/conversations/nb1/messages", {"text": ""})[0], 400)
        self.assertEqual(self.req("POST", "/conversations/nb1/messages",
                                  {"text": "x", "images": [{"mime": "image/png", "data": "!!"}]})[0], 400)
        self.assertEqual(self.req("POST", "/conversations/nb1/messages",
                                  {"text": "x", "images": [{"mime": "text/html", "data": PNG_1PX}]})[0], 400)
        self.assertEqual(self.req("GET", "/nope")[0], 404)
        # A failed request must not leave the conversation stuck as busy.
        self.assertFalse(self.wait_idle()["busy"])


if __name__ == "__main__":
    unittest.main()


class LaunchCommandTest(unittest.TestCase):
    """How the bridge starts `claude` on Android, where /usr/bin/env does not exist."""

    def script(self, content):
        tmp = tempfile.NamedTemporaryFile("w", delete=False, suffix=".sh")
        tmp.write(content)
        tmp.close()
        os.chmod(tmp.name, 0o755)
        self.addCleanup(os.remove, tmp.name)
        return tmp.name

    def run_cmd(self, path):
        import subprocess
        cmd = bridge_mod.launch_command(path) + ["--version"]
        return subprocess.run(cmd, capture_output=True, text=True).stdout.strip()

    def test_env_shebang_starts_the_interpreter_directly(self):
        path = self.script("#!/usr/bin/env python3\nimport sys; print('v', sys.argv[1])\n")
        cmd = bridge_mod.launch_command(path)
        self.assertEqual(os.path.basename(cmd[0]).startswith("python3"), True)
        self.assertEqual(cmd[-1], path)
        self.assertEqual(self.run_cmd(path), "v --version")

    def test_missing_interpreter_path_is_found_on_path(self):
        path = self.script("#!/nonexistent/bin/sh\necho v \"$1\"\n")
        self.assertNotEqual(bridge_mod.launch_command(path)[0], "/nonexistent/bin/sh")
        self.assertEqual(self.run_cmd(path), "v --version")

    def test_script_without_shebang_runs_in_a_shell(self):
        path = self.script("echo v \"$1\"\n")
        self.assertEqual(self.run_cmd(path), "v --version")

    def test_binaries_run_as_they_are(self):
        self.assertEqual(bridge_mod.launch_command(sys.executable), [sys.executable])

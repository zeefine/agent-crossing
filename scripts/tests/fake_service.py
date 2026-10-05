"""Isolated service fixture: the launcher has a TERM-resistant HTTP child."""
import http.server
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

root = Path(os.environ["FIXTURE_ROOT"])
name = sys.argv[1]
(root / "run" / f"{name}.{os.getpid()}.fixture").touch()
if os.environ.get("FAIL_SERVICE") == name:
    sys.exit(7)
port = int(os.environ[{
    "platform-api": "PLATFORM_API_PORT", "agent-runtime": "AGENT_RUNTIME_PORT", "platform-web": "PLATFORM_WEB_PORT"
}[name]])
if len(sys.argv) == 2:
    child = subprocess.Popen([sys.executable, __file__, name, "child"])
    if os.environ.get("EXIT_PARENT") == name:
        time.sleep(0.5)
        sys.exit(3)
    child.wait()
else:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if os.environ.get("HANG_SERVICE") == name:
                time.sleep(30)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"status":"ok"}')

        def log_message(self, *args):
            pass

    http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()

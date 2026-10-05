"""No real Java, Node, CLI agents, Docker or database is used by these tests."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


class DevScriptsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="agent-crossing-script-test-")
        self.root = Path(self.temp.name)
        shutil.copytree(SCRIPTS, self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        for directory in ("run", "logs", "bin", "services/platform-api", "services/platform-web", "services/agent-runtime/.venv/bin"):
            (self.root / directory).mkdir(parents=True, exist_ok=True)
        for filename in ("services/platform-api/pom.xml", "services/platform-web/package.json", "services/agent-runtime/pyproject.toml"):
            (self.root / filename).touch()
        self.env = {**os.environ, "PATH": f"{self.root / 'bin'}:{os.environ['PATH']}", "FIXTURE_ROOT": str(self.root),
                    "STORAGE_MODE": "memory", "SPRING_PROFILES_ACTIVE": "", "DEV_READY_TIMEOUT": "2",
                    "DEV_MYSQL_TIMEOUT": "1", "DEV_HTTP_TIMEOUT": "0.3", "DEV_STOP_TIMEOUT": "0.5"}
        ports = set()
        for key in ("PLATFORM_API_PORT", "AGENT_RUNTIME_PORT", "PLATFORM_WEB_PORT", "MYSQL_PORT"):
            port = free_port()
            while port in ports:
                port = free_port()
            ports.add(port)
            self.env[key] = str(port)
        self.env.update(PLATFORM_API_HOST="127.0.0.1", AGENT_RUNTIME_HOST="127.0.0.1", PLATFORM_WEB_HOST="127.0.0.1", MYSQL_SERVER="127.0.0.1")
        for key in ("DEV_MYSQL_CONTAINER", "SPRING_DATASOURCE_URL", "AGENT_CROSSING_STORAGE_MODE", "SPRING_PROFILES_INCLUDE"):
            self.env.pop(key, None)
        fixture = self.root / "scripts/tests/fake_service.py"
        for command, name in (("bin/mvn", "platform-api"), ("bin/npm", "platform-web"), ("services/agent-runtime/.venv/bin/uvicorn", "agent-runtime")):
            path = self.root / command
            path.write_text(f"#!/bin/sh\nexec {shlex.quote(sys.executable)} {shlex.quote(str(fixture))} {name}\n")
            path.chmod(0o755)
        (self.root / "bin/uv").write_text("#!/bin/sh\nexit 0\n")
        (self.root / "bin/uv").chmod(0o755)

    def run_script(self, operation, timeout=15):
        return subprocess.run(["bash", str(self.root / f"scripts/dev-{operation}.sh")], env=self.env,
                              capture_output=True, text=True, timeout=timeout)

    def fixture_alive(self):
        alive = []
        for marker in (self.root / "run").glob("*.fixture"):
            pid = int(marker.name.split(".")[1])
            info = subprocess.run(["ps", "-p", str(pid), "-o", "stat=,command="], capture_output=True, text=True).stdout
            if str(self.root) in info and not info.lstrip().startswith("Z"):
                alive.append(pid)
        return alive

    def tearDown(self):
        # Only signal fixtures whose command still contains this test's unique directory.
        for pid in self.fixture_alive():
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        try:
            self.run_script("down")
        finally:
            self.temp.cleanup()

    def test_database_failure_prevents_any_service_launch(self):
        self.env["SPRING_PROFILES_ACTIVE"] = "mysql"
        result = self.run_script("up")
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(list((self.root / "run").glob("*.fixture")), result.stdout)

    def test_legacy_pid_does_not_kill_unrelated_process(self):
        unrelated = subprocess.Popen(["sleep", "30"])
        try:
            (self.root / "run/platform-api.pid").write_text(str(unrelated.pid))
            result = self.run_script("down")
            self.assertIsNone(unrelated.poll(), result.stdout)
            self.assertNotEqual(result.returncode, 0)
        finally:
            unrelated.kill() if unrelated.poll() is None else None
            unrelated.wait()

    def test_shutdown_includes_term_resistant_children(self):
        result = self.run_script("up")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        result = self.run_script("down")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.fixture_alive(), [])

    def test_partial_start_failure_rolls_back_new_services(self):
        self.env["FAIL_SERVICE"] = "platform-web"
        result = self.run_script("up")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.fixture_alive(), [], result.stdout + result.stderr)

    def test_hanging_health_request_is_bounded_and_rolled_back(self):
        self.env["HANG_SERVICE"] = "platform-api"
        started = time.monotonic()
        result = self.run_script("up", timeout=10)
        self.assertNotEqual(result.returncode, 0)
        self.assertLess(time.monotonic() - started, 8)
        self.assertIn("timed out", result.stderr)
        self.assertEqual(self.fixture_alive(), [])

    def test_repeated_start_reuses_healthy_services(self):
        first = self.run_script("up")
        self.assertEqual(first.returncode, 0, first.stderr)
        identities = {p.name: p.read_text() for p in (self.root / "run").glob("*.json")}
        second = self.run_script("up")
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(identities, {p.name: p.read_text() for p in (self.root / "run").glob("*.json")})

    def test_rollback_preserves_preexisting_services(self):
        result = self.run_script("up")
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.root / "run/platform-web.json"
        web = json.loads(path.read_text())
        # Ask this test-owned supervisor to stop, leaving API/runtime running.
        os.kill(web["pid"], signal.SIGTERM)
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", int(self.env["PLATFORM_WEB_PORT"]))) != 0:
                    break
            time.sleep(0.05)
        api = (self.root / "run/platform-api.json").read_text()
        runtime = (self.root / "run/agent-runtime.json").read_text()
        self.env["FAIL_SERVICE"] = "platform-web"
        result = self.run_script("up")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "run/platform-api.json").read_text(), api)
        self.assertEqual((self.root / "run/agent-runtime.json").read_text(), runtime)
        for port in ("PLATFORM_API_PORT", "AGENT_RUNTIME_PORT"):
            with socket.create_connection(("127.0.0.1", int(self.env[port])), timeout=1):
                pass

    def test_wrong_process_identity_is_not_signaled(self):
        result = self.run_script("up")
        self.assertEqual(result.returncode, 0, result.stderr)
        path = self.root / "run/platform-api.json"
        original = path.read_text()
        state = json.loads(original)
        state["started"] = "wrong start time"
        path.write_text(json.dumps(state))
        try:
            result = self.run_script("down")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("identity mismatch", result.stderr)
            with socket.create_connection(("127.0.0.1", int(self.env["PLATFORM_API_PORT"])), timeout=1):
                pass
        finally:
            path.write_text(original)

    def test_dead_launcher_also_cleans_its_children(self):
        self.env["EXIT_PARENT"] = "platform-api"
        self.run_script("up")
        deadline = time.monotonic() + 4
        while self.fixture_alive() and time.monotonic() < deadline:
            time.sleep(0.1)
        # The API may initially pass health before the fixture parent exits.
        api_pids = {int(p.name.split(".")[1]) for p in (self.root / "run").glob("platform-api.*.fixture")}
        self.assertFalse(set(self.fixture_alive()) & api_pids)

    def test_mysql_greeting_allows_start_without_database_authentication(self):
        self.env["SPRING_PROFILES_ACTIVE"] = "mysql"
        with socket.socket() as server:
            server.bind(("127.0.0.1", int(self.env["MYSQL_PORT"])))
            server.listen()
            server.settimeout(5)

            def greet():
                connection, _ = server.accept()
                with connection:
                    connection.sendall(b"\x02\x00\x00\x00\x0a\x00")

            worker = threading.Thread(target=greet, daemon=True)
            worker.start()
            result = self.run_script("up")
            worker.join(timeout=5)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_interrupt_rolls_back_and_concurrent_start_is_rejected(self):
        self.env["HANG_SERVICE"] = "platform-api"
        self.env["DEV_READY_TIMEOUT"] = "10"
        running = subprocess.Popen(["bash", str(self.root / "scripts/dev-up.sh")], env=self.env,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not self.fixture_alive() and time.monotonic() < deadline:
                time.sleep(0.05)
            self.assertTrue(self.fixture_alive())
            concurrent = self.run_script("up")
            self.assertNotEqual(concurrent.returncode, 0)
            self.assertIn("in progress", concurrent.stderr)
            running.send_signal(signal.SIGTERM)
            output, error = running.communicate(timeout=6)
            self.assertNotEqual(running.returncode, 0, output + error)
            self.assertEqual(self.fixture_alive(), [])
        finally:
            if running.poll() is None:
                running.kill()
                running.wait()


class DatabaseConfigurationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location("dev_services", SCRIPTS / "dev_services.py")
        cls.manager = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.manager)

    def test_memory_mode_skips_database(self):
        with patch.dict(os.environ, {"STORAGE_MODE": "memory"}, clear=True):
            self.assertIsNone(self.manager.mysql_target())

    def test_mysql_profile_and_datasource_url(self):
        with patch.dict(os.environ, {"SPRING_PROFILES_ACTIVE": "dev,mysql", "SPRING_DATASOURCE_URL": "jdbc:mysql://127.0.0.1:4567/example"}, clear=True):
            self.assertEqual(self.manager.mysql_target(), ("127.0.0.1", 4567))

    def test_no_docker_mutation_unless_explicitly_configured(self):
        with patch.dict(os.environ, {"STORAGE_MODE": "memory"}, clear=True), patch.object(self.manager, "command") as commands:
            self.manager.wait_mysql()
            commands.assert_not_called()

    def test_explicit_container_must_match_local_mysql_port(self):
        with patch.dict(os.environ, {"STORAGE_MODE": "mysql", "DEV_MYSQL_CONTAINER": "test-mysql"}, clear=True), patch.object(self.manager, "command", return_value='{"3306/tcp":[{"HostPort":"9999"}]}') as commands:
            with self.assertRaisesRegex(ValueError, "publish"):
                self.manager.wait_mysql()
            self.assertEqual(commands.call_count, 1)

    def test_explicit_container_start_and_health_are_checked(self):
        responses = ['{"3306/tcp":[{"HostPort":"13306"}]}', 'false', 'test-mysql', 'healthy']
        with patch.dict(os.environ, {"STORAGE_MODE": "mysql", "DEV_MYSQL_CONTAINER": "test-mysql"}, clear=True), patch.object(self.manager, "command", side_effect=responses) as commands, patch.object(self.manager.socket, "create_connection") as connect:
            connect.return_value.__enter__.return_value.recv.return_value = b"\x02\x00\x00\x00\x0a"
            self.manager.wait_mysql()
            self.assertIn(["docker", "start", "test-mysql"], [call.args[0] for call in commands.call_args_list])

    def test_mysql_without_container_does_not_use_docker(self):
        with patch.dict(os.environ, {"STORAGE_MODE": "mysql"}, clear=True), patch.object(self.manager, "command") as commands, patch.object(self.manager.socket, "create_connection") as connect:
            connect.return_value.__enter__.return_value.recv.return_value = b"\x02\x00\x00\x00\x0a"
            self.manager.wait_mysql()
            commands.assert_not_called()

    def test_non_mysql_listener_is_not_ready(self):
        with patch.dict(os.environ, {"STORAGE_MODE": "mysql", "DEV_MYSQL_TIMEOUT": "0.1"}, clear=True), patch.object(self.manager.socket, "create_connection") as connect:
            connect.return_value.__enter__.return_value.recv.return_value = b"HTTP/"
            with self.assertRaisesRegex(RuntimeError, "MySQL not ready"):
                self.manager.wait_mysql()


if __name__ == "__main__":
    unittest.main()

"""Local POSIX service lifecycle; Python standard library only (macOS/Linux).

Each service has a persistent session/group leader. Never signal an unverified
PID or an orphan group: PID reuse and legacy pid files must fail closed.
"""
import fcntl
import json
import math
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
from urllib.parse import urlsplit
import uuid

ROOT = Path(__file__).resolve().parent.parent
RUN = ROOT / "run"
LOG = ROOT / "logs"
SERVICES = ("platform-api", "agent-runtime", "platform-web")


def seconds(name, default):
    value = float(os.environ.get(name, default))
    if not math.isfinite(value) or not 0 < value <= 3600:
        raise ValueError(f"{name} must be between 0 and 3600 seconds")
    return value


def command(args, timeout=5, **kwargs):
    return subprocess.run(args, timeout=timeout, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def process_info(pid):
    result = subprocess.run(["ps", "-p", str(pid), "-o", "pid=,pgid=,lstart=,command="],
                            capture_output=True, text=True, timeout=3, env={**os.environ, "LC_ALL": "C"})
    if result.returncode == 1 and not result.stdout.strip():
        return None
    if result.returncode:
        raise RuntimeError("Cannot inspect process identity; refusing to signal processes")
    fields = result.stdout.strip().split(None, 7)
    if len(fields) != 8:
        raise RuntimeError("Unexpected ps identity output")
    return {"pid": int(fields[0]), "pgid": int(fields[1]), "started": " ".join(fields[2:7]), "command": fields[7]}


def group_members(pgid):
    output = command(["ps", "-ax", "-o", "pid=,pgid=,stat="])
    return [int(pid) for pid, group, status in (row.split() for row in output.splitlines())
            if int(group) == pgid and not status.startswith("Z")]


def state_path(name):
    return RUN / f"{name}.json"


def verified(state):
    pid = state.get("pid")
    if type(pid) is not int or pid <= 1 or state.get("pgid") != pid:
        return False
    info = process_info(pid)
    # macOS Python may re-exec its framework binary after Popen returns. Match
    # the exact supervisor suffix, not the transient interpreter prefix.
    return bool(info and all(info[key] == state.get(key) for key in ("pid", "pgid", "started"))
                and info["command"].endswith(f"{Path(__file__).resolve()} _supervise {state['service']} {state['token']}"))


def remove_state(name):
    state_path(name).unlink(missing_ok=True)
    (RUN / f"{name}.pid").unlink(missing_ok=True)


def read_state(name):
    path = state_path(name)
    legacy = RUN / f"{name}.pid"
    if not path.exists():
        if legacy.exists():
            value = legacy.read_text().strip()
            if not value.isdecimal() or int(value) <= 1 or process_info(int(value)):
                raise RuntimeError(f"{name}: unverified legacy PID file {legacy}; inspect the old process manually. No signal sent.")
            legacy.unlink()
        return None
    state = json.loads(path.read_text())
    if (state.get("root") != str(ROOT) or state.get("service") != name
            or type(state.get("pid")) is not int or state["pid"] <= 1
            or state.get("pgid") != state["pid"] or not state.get("token")
            or type(state.get("stop_timeout")) not in (int, float)
            or not 0 < state["stop_timeout"] <= 3600):
        raise RuntimeError(f"{name}: invalid process metadata; no signal sent")
    if verified(state):
        return state
    if process_info(state["pid"]) is None and not group_members(state["pgid"]):
        remove_state(name)
        return None
    raise RuntimeError(f"{name}: process identity mismatch or orphan group; inspect {path}. No signal sent.")


def stop_service(name, state):
    if not verified(state):
        if not group_members(state["pgid"]):
            remove_state(name)
            return
        raise RuntimeError(f"{name}: cannot verify group leader; refusing to kill an orphan/reused group")
    print(f"Stopping {name} (managed group {state['pgid']})", flush=True)
    try:
        os.kill(state["pid"], signal.SIGTERM)
    except ProcessLookupError:
        pass
    deadline = time.monotonic() + state["stop_timeout"] + 3
    while group_members(state["pgid"]):
        if time.monotonic() >= deadline:
            if not verified(state):
                raise RuntimeError(f"{name}: group leader disappeared; metadata retained for manual recovery")
            os.killpg(state["pgid"], signal.SIGKILL)
            break
        time.sleep(0.1)
    deadline = time.monotonic() + 3
    while group_members(state["pgid"]):
        if time.monotonic() >= deadline:
            raise RuntimeError(f"{name}: group still exists; metadata retained")
        time.sleep(0.1)
    remove_state(name)


def service_spec(name):
    prefix, default = {"platform-api": ("PLATFORM_API", 8080), "agent-runtime": ("AGENT_RUNTIME", 8090),
                       "platform-web": ("PLATFORM_WEB", 3000)}[name]
    host = os.environ.get(f"{prefix}_HOST", "127.0.0.1")
    port = int(os.environ.get(f"{prefix}_PORT", default))
    if not 0 < port < 65536:
        raise ValueError(f"Invalid {prefix}_PORT")
    return host, port


def base_url(name, protocol="http"):
    host, port = service_spec(name)
    host = {"0.0.0.0": "127.0.0.1", "::": "::1"}.get(host, host)
    return f"{protocol}://{'[' + host + ']' if ':' in host else host}:{port}"


def service_command(name):
    host, port = service_spec(name)
    if name == "platform-api":
        return ["mvn", "spring-boot:run"]
    if name == "agent-runtime":
        return [str(ROOT / "services/agent-runtime/.venv/bin/uvicorn"), "agent_runtime.main:app", "--host", host, "--port", str(port)]
    return ["npm", "run", "dev", "--", "-H", host, "-p", str(port)]


def supervise(name, token):
    """Keep the leader alive until all ordinary descendants in its group are gone."""
    if os.getpgrp() != os.getpid() or os.getsid(0) != os.getpid():
        raise RuntimeError("Supervisor requires a dedicated session; use dev-up.sh")
    stopping = False

    def request_stop(*_):
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGHUP, signal.SIG_IGN)
    signal.signal(signal.SIGTERM, request_stop)
    signal.signal(signal.SIGINT, request_stop)
    # Do not launch anything until the manager has durably recorded ownership.
    deadline = time.monotonic() + 5
    while not stopping:
        if state_path(name).exists() and json.loads(state_path(name).read_text()).get("token") == token:
            break
        if time.monotonic() >= deadline:
            return 1
        time.sleep(0.05)
    if stopping:
        return 1
    child = None
    try:
        child = subprocess.Popen(service_command(name), cwd=ROOT / "services" / name, stdin=subprocess.DEVNULL)
        while not stopping and child.poll() is None:
            time.sleep(0.1)
        return child.returncode or 0
    finally:
        # This group is ours by construction, not inferred from a stored PID.
        os.killpg(os.getpgrp(), signal.SIGTERM)
        deadline = time.monotonic() + seconds("DEV_STOP_TIMEOUT", 10)
        while True:
            if child:
                child.poll()
            if not [pid for pid in group_members(os.getpgrp()) if pid != os.getpid()]:
                break
            if time.monotonic() >= deadline:
                os.killpg(os.getpgrp(), signal.SIGKILL)
            time.sleep(0.1)


def start_service(name, started):
    token = uuid.uuid4().hex
    with (LOG / f"{name}.log").open("a") as log:
        log.write(f"\n--- managed start {time.strftime('%Y-%m-%d %H:%M:%S %z')} ---\n")
        log.flush()
        child = subprocess.Popen([sys.executable, "-u", str(Path(__file__).resolve()), "_supervise", name, token],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
    info = process_info(child.pid)
    if not info or info["pgid"] != child.pid or token not in info["command"]:
        raise RuntimeError(f"{name}: supervisor failed to establish its identity")
    state = {**info, "root": str(ROOT), "service": name, "token": token, "stop_timeout": seconds("DEV_STOP_TIMEOUT", 10)}
    started.append((name, state, child))
    temp = RUN / f"{name}.{token}.tmp"
    with temp.open("x") as output:
        os.chmod(temp, 0o600)
        json.dump(state, output)
    temp.replace(state_path(name))
    (RUN / f"{name}.pid").write_text(str(child.pid))
    print(f"Starting {name}; log: {LOG / (name + '.log')}", flush=True)
    return state


def wait_http(name, state):
    deadline = time.monotonic() + seconds("DEV_READY_TIMEOUT", 60)
    url = base_url(name) + ("" if name == "platform-web" else "/api/health")
    while time.monotonic() < deadline:
        if not verified(state):
            raise RuntimeError(f"{name} exited before ready; see {LOG / (name + '.log')}")
        budget = min(seconds("DEV_HTTP_TIMEOUT", 2), deadline - time.monotonic())
        if budget <= 0:
            break
        try:
            command(["curl", "--noproxy", "*", "-fsS", "--connect-timeout", str(budget), "--max-time", str(budget), url], timeout=budget + 0.2)
            if verified(state):
                print(f"{name} ready: {url}", flush=True)
                return
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(min(0.2, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"{name} readiness timed out; see {LOG / (name + '.log')}")


def mysql_target():
    profiles = (os.environ.get("SPRING_PROFILES_ACTIVE", "") + "," + os.environ.get("SPRING_PROFILES_INCLUDE", "")).replace(" ", "").split(",")
    mode = os.environ.get("AGENT_CROSSING_STORAGE_MODE", "mysql" if "mysql" in profiles else os.environ.get("STORAGE_MODE", "memory"))
    if mode != "mysql":
        return None
    url = os.environ.get("SPRING_DATASOURCE_URL")
    if url:
        parsed = urlsplit(url.removeprefix("jdbc:"))
        if parsed.scheme != "mysql" or not parsed.hostname or "," in parsed.netloc:
            raise ValueError("Database precheck supports a single-host jdbc:mysql:// URL")
        return parsed.hostname, parsed.port or 3306
    return os.environ.get("MYSQL_SERVER", "localhost"), int(os.environ.get("MYSQL_PORT", "13306"))


def wait_mysql():
    target = mysql_target()
    if target is None:
        return
    host, port = target
    if not 0 < port < 65536:
        raise ValueError("Invalid MySQL port")
    deadline = time.monotonic() + seconds("DEV_MYSQL_TIMEOUT", 30)

    def docker(*args):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError("MySQL container precheck timed out")
        return command(["docker", *args], timeout=remaining)

    container = os.environ.get("DEV_MYSQL_CONTAINER", "")
    if container:
        if host not in ("localhost", "127.0.0.1", "::1"):
            raise ValueError("DEV_MYSQL_CONTAINER is only supported for a local database")
        bindings = json.loads(docker("inspect", "--format", "{{json .HostConfig.PortBindings}}", container))
        if not any(int(item["HostPort"]) == port for item in (bindings or {}).get("3306/tcp", []) or []):
            raise ValueError("Configured Docker container does not publish the configured MySQL port")
        running = docker("inspect", "--format", "{{.State.Running}}", container)
        if running != "true":
            print(f"Starting explicitly configured MySQL container: {container}", flush=True)
            docker("start", container)
    print(f"Waiting for MySQL at {host}:{port}", flush=True)
    while time.monotonic() < deadline:
        if container:
            health = docker("inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", container)
            if health not in ("healthy", "none"):
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
                continue
        try:
            with socket.create_connection(target, timeout=min(1, max(0.01, deadline - time.monotonic()))) as connection:
                # Read the packet header + protocol marker; TCP accept alone isn't readiness.
                greeting = b""
                while len(greeting) < 5 and time.monotonic() < deadline:
                    connection.settimeout(max(0.01, min(1, deadline - time.monotonic())))
                    part = connection.recv(5 - len(greeting))
                    if not part:
                        break
                    greeting += part
                if len(greeting) == 5 and greeting[3] == 0 and greeting[4] == 10:
                    print("MySQL handshake ready (credentials/schema are validated by platform-api)", flush=True)
                    return
        except OSError:
            pass
        time.sleep(min(0.2, max(0, deadline - time.monotonic())))
    raise RuntimeError(f"MySQL not ready at {host}:{port}; start MySQL/check .env before retrying. No application services started.")


def up():
    started = []
    try:
        existing = {name: read_state(name) for name in SERVICES}
        for setting, default in (("DEV_READY_TIMEOUT", 60), ("DEV_HTTP_TIMEOUT", 2), ("DEV_STOP_TIMEOUT", 10), ("DEV_MYSQL_TIMEOUT", 30)):
            seconds(setting, default)
        for tool in ("ps", "curl", "java", "mvn", "node", "npm"):
            if not shutil.which(tool):
                raise RuntimeError(f"Required executable not found: {tool}")
        for name in SERVICES:
            host, port = service_spec(name)
            if not existing[name]:
                with socket.socket(socket.AF_INET6 if ":" in host else socket.AF_INET) as probe:
                    probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    try:
                        probe.bind((host, port))
                    except OSError as error:
                        raise RuntimeError(f"{name}: cannot bind {host}:{port}: {error}") from None
        wait_mysql()
        runtime = ROOT / "services/agent-runtime"
        if not existing["agent-runtime"] and not (runtime / ".venv/bin/uvicorn").is_file():
            command(["uv", "--directory", str(runtime), "sync"], timeout=seconds("DEV_INSTALL_TIMEOUT", 180))
        os.environ.setdefault("AGENT_RUNTIME_BASE_URL", base_url("agent-runtime"))
        os.environ.setdefault("PLATFORM_API_BASE_URL", base_url("platform-api"))
        os.environ.setdefault("NEXT_PUBLIC_PLATFORM_WS_URL", base_url("platform-api", "ws") + "/ws/chat")
        for name in SERVICES:
            state = existing[name] or start_service(name, started)
            wait_http(name, state)
        print(f"All services ready. Logs: {LOG}", flush=True)
    except BaseException:
        # Repeated Ctrl+C must not interrupt rollback; never stop pre-existing services.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        for name, state, child in reversed(started):
            try:
                stop_service(name, state)
                child.wait(timeout=1)
            except Exception as error:
                print(f"Rollback warning: {error}", file=sys.stderr)
        raise


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "_supervise" and sys.argv[2] in SERVICES:
        return supervise(sys.argv[2], sys.argv[3])
    if sys.argv[1:] not in (["up"], ["down"]):
        raise ValueError("Usage: dev_services.py up|down")
    RUN.mkdir(exist_ok=True)
    LOG.mkdir(exist_ok=True)
    os.umask(0o077)
    with (RUN / "dev-services.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise RuntimeError("Another startup/shutdown is in progress; retry after it finishes") from None
        if sys.argv[1] == "up":
            def interrupted(*_):
                raise InterruptedError("Startup interrupted")
            signal.signal(signal.SIGTERM, interrupted)
            signal.signal(signal.SIGINT, interrupted)
            up()
        else:
            errors = []
            for name in reversed(SERVICES):
                try:
                    state = read_state(name)
                    if state:
                        stop_service(name, state)
                    else:
                        print(f"{name} is not running")
                except Exception as error:
                    errors.append(str(error))
            if errors:
                raise RuntimeError("\n".join(errors))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (Exception, KeyboardInterrupt) as failure:
        # Do not dump command output/environment (may contain credentials).
        print(f"dev-services: {failure}", file=sys.stderr)
        sys.exit(1)

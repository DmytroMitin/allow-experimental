#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import pathlib
import re
import subprocess
import threading
import time


def encode_message(message):
    body = json.dumps(message, separators=(",", ":")).encode("utf-8")
    payload = body + b"\r\n"
    return f"Content-Length: {len(payload)}\r\n\r\n".encode("ascii") + payload


def read_message(stream):
    headers = {}
    while True:
        line = stream.readline()
        if not line:
            raise EOFError("JSON-RPC stream ended before a complete header")
        if line in (b"\r\n", b"\n"):
            break
        name, value = line.decode("ascii").split(":", 1)
        headers[name.lower()] = value.strip()
    length = int(headers["content-length"])
    payload = stream.read(length)
    if len(payload) != length:
        raise EOFError("JSON-RPC stream ended before the complete body")
    return json.loads(payload.rstrip(b"\r\n").decode("utf-8"))


def select_compile_target(result, project):
    compile_fragment = f"#{project}/Compile"
    matches = [
        item["id"]
        for item in result["targets"]
        if item.get("displayName") == project
        and item.get("id", {}).get("uri", "").endswith(compile_fragment)
    ]
    if len(matches) != 1:
        raise ValueError(f"expected exactly one Compile target for {project}, observed {len(matches)}")
    return matches[0]


class ProcessLog:
    def __init__(self, stream):
        self.stream = stream
        self.data = bytearray()
        self.condition = threading.Condition()
        self.thread = threading.Thread(target=self._read, daemon=True)
        self.thread.start()

    def _read(self):
        while True:
            chunk = os.read(self.stream.fileno(), 4096)
            with self.condition:
                if not chunk:
                    self.condition.notify_all()
                    return
                self.data.extend(chunk)
                self.condition.notify_all()

    def wait_for(self, marker, start=0, timeout=180):
        marker = marker.encode("utf-8")
        deadline = time.monotonic() + timeout
        with self.condition:
            while True:
                position = self.data.find(marker, start)
                if position >= 0:
                    end = position + len(marker)
                    return end, bytes(self.data[start:end]).decode("utf-8", errors="replace")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError(f"timed out waiting for {marker!r}")
                self.condition.wait(remaining)

    def snapshot(self):
        with self.condition:
            return bytes(self.data)


class SbtSession:
    prompt = "M5C> "

    def __init__(self, fixture, environment):
        self.process = subprocess.Popen(
            ["sbt"],
            cwd=fixture,
            env=environment,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        self.log = ProcessLog(self.process.stdout)
        self.cursor, _ = self.log.wait_for(self.prompt, timeout=300)

    def command(self, command, timeout=300):
        if self.process.poll() is not None:
            raise RuntimeError(f"sbt process {self.process.pid} exited before {command!r}")
        start = self.cursor
        self.process.stdin.write((command + "\n").encode("utf-8"))
        self.process.stdin.flush()
        self.cursor, output = self.log.wait_for(self.prompt, start=start, timeout=timeout)
        return output

    def stop(self):
        if self.process.poll() is None:
            self.process.stdin.write(b"exit\n")
            self.process.stdin.flush()
        return self.process.wait(timeout=120)


class JsonRpcClient:
    def __init__(self, process, traffic_path):
        self.process = process
        self.buffer = bytearray()
        self.traffic_path = traffic_path
        self.next_id = 1
        self.initialize_count = 0

    def _record(self, direction, message):
        with self.traffic_path.open("a", encoding="utf-8") as out:
            out.write(json.dumps({"direction": direction, "message": message}, sort_keys=True) + "\n")

    def send_request(self, method, params):
        request_id = str(self.next_id)
        self.next_id += 1
        if method == "build/initialize":
            self.initialize_count += 1
        message = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": params}
        self.process.stdin.write(encode_message(message))
        self.process.stdin.flush()
        self._record("client-request", message)
        return request_id

    def send_notification(self, method, params):
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        self.process.stdin.write(encode_message(message))
        self.process.stdin.flush()
        self._record("client-notification", message)

    def _extract(self):
        header_end = self.buffer.find(b"\r\n\r\n")
        if header_end < 0:
            return None
        header = bytes(self.buffer[:header_end]).decode("ascii")
        lengths = [line.split(":", 1)[1].strip() for line in header.split("\r\n")
                   if line.lower().startswith("content-length:")]
        if len(lengths) != 1:
            raise ValueError(f"invalid JSON-RPC header: {header}")
        length = int(lengths[0])
        body_start = header_end + 4
        if len(self.buffer) < body_start + length:
            return None
        payload = bytes(self.buffer[body_start:body_start + length])
        del self.buffer[:body_start + length]
        return json.loads(payload.rstrip(b"\r\n").decode("utf-8"))

    def receive(self, timeout=300):
        deadline = time.monotonic() + timeout
        while True:
            message = self._extract()
            if message is not None:
                self._record("server", message)
                return message
            if self.process.poll() is not None:
                raise EOFError(f"BSP proxy exited with {self.process.returncode}")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("timed out waiting for BSP response")
            import select
            readable, _, _ = select.select([self.process.stdout], [], [], remaining)
            if not readable:
                raise TimeoutError("timed out waiting for BSP bytes")
            chunk = os.read(self.process.stdout.fileno(), 65536)
            if not chunk:
                raise EOFError("BSP proxy stdout closed")
            self.buffer.extend(chunk)

    def response(self, request_id, timeout=300):
        notifications = []
        deadline = time.monotonic() + timeout
        while True:
            message = self.receive(max(0.1, deadline - time.monotonic()))
            if message.get("id") == request_id:
                return message, notifications
            notifications.append(message)


def sha256_bytes(value):
    return hashlib.sha256(value).hexdigest()


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_source(path, text):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(path)
    time.sleep(0.05)


def provider_source(mode):
    annotation = "\nimport scala.annotation.experimental\n" if mode == "experimental" else ""
    marker = "@experimental " if mode == "experimental" else ""
    return f"""package m5cfixture
{annotation}
object Provider:
  {marker}def provider(): Int = 1
"""


def allowed_source(mode):
    if mode == "unmarked":
        imports = ""
        marker = ""
        sibling = ""
    else:
        imports = "\nimport io.github.dmytromitin.allowexperimental.allowExperimental\n"
        marker = "@allowExperimental "
        sibling = "\n  def forbiddenSibling(): Int = Provider.provider()" if mode == "sibling" else ""
    return f"""package m5cfixture
{imports}
object Allowed:
  {marker}def allowed(): Int = Provider.provider(){sibling}
"""


def source_paths(fixture):
    base = fixture
    return {
        "provider": base / "provider/src/main/scala/m5cfixture/Provider.scala",
        "allowed": base / "allowed/src/main/scala/m5cfixture/Allowed.scala",
        "consumer": base / "consumer/src/main/scala/m5cfixture/Consumer.scala",
        "downstream": base / "fresh-downstream/src/main/scala/m5cfixture/FreshDownstream.scala",
    }


def set_state(fixture, provider, allowed):
    paths = source_paths(fixture)
    write_source(paths["provider"], provider_source(provider))
    write_source(paths["allowed"], allowed_source(allowed))


def tree_hashes(fixture, lane):
    files = []
    for path in fixture.rglob("*"):
        if path.is_file() and (
            "/src/main/scala/" in path.as_posix()
            or f"/target/scala-{lane}/classes/" in path.as_posix()
            or path.name == "inc_compile_3.zip"
        ):
            files.append(path)
    return {str(path.relative_to(fixture)): sha256_file(path) for path in sorted(files)}


def process_identity(process):
    stat = pathlib.Path(f"/proc/{process.pid}/stat").read_text(encoding="utf-8").split()
    cmdline = pathlib.Path(f"/proc/{process.pid}/cmdline").read_bytes().replace(b"\0", b" ").decode("utf-8")
    return {"pid": process.pid, "startTicks": stat[21], "cmdline": cmdline}


def expected_success(output, label):
    if "[error]" in output or "Compilation failed" in output:
        raise AssertionError(f"{label} expected success:\n{output}")


def expected_failure(output, label):
    if "marked @experimental" not in output:
        raise AssertionError(f"{label} lacked stock experimental rejection:\n{output}")


def record_step(evidence, label, output, fixture, lane, extra=None):
    (evidence / f"{label}.log").write_text(output, encoding="utf-8")
    record = {
        "label": label,
        "sourcesAndOutputs": tree_hashes(fixture, lane),
    }
    if extra:
        record.update(extra)
    with (evidence / "steps.jsonl").open("a", encoding="utf-8") as out:
        out.write(json.dumps(record, sort_keys=True) + "\n")


def sbt_environment(args, mode):
    environment = os.environ.copy()
    environment["SBT_OPTS"] = " ".join([
        f"-Dsbt.boot.directory={args.work / '.sbt-boot'}",
        f"-Dsbt.global.base={args.work / '.sbt-global'}",
        "-Dsbt.supershell=false",
        "-Dsbt.log.noformat=true",
        "-Dsbt.server.autostart=true",
    ])
    environment["SBT_GLOBAL_SERVER_DIR"] = str(args.runtime / "server")
    environment["XDG_RUNTIME_DIR"] = str(args.runtime)
    return environment


def run_persistent_sbt(args):
    set_state(args.fixture, "ordinary", "unmarked")
    session = SbtSession(args.fixture, sbt_environment(args, "persistent-sbt"))
    start_identity = process_identity(session.process)
    (args.evidence / "process-start.json").write_text(json.dumps(start_identity, sort_keys=True) + "\n")
    try:
        output = session.command("recordM5CIdentity; clean; compile; recordM5CInputs", timeout=600)
        expected_success(output, "S0-baseline")
        record_step(args.evidence, "S0-baseline", output, args.fixture, args.lane)

        sequence = [
            ("S1-provider-experimental-reject", "experimental", "unmarked", "allowed/compile", False),
            ("S2-permission-repair", "experimental", "marked", "consumer/compile; recordM5CInputs", True),
            ("S3-permission-remove-reject", "experimental", "unmarked", "allowed/compile", False),
            ("S4-permission-restore", "experimental", "marked", "consumer/compile", True),
            ("S5-sibling-reject", "experimental", "sibling", "allowed/compile", False),
            ("S6-sibling-repair", "experimental", "marked", "consumer/compile", True),
            ("S7-provider-ordinary", "ordinary", "marked", "consumer/compile", True),
            ("S8-provider-experimental", "experimental", "marked", "consumer/compile", True),
        ]
        for label, provider, allowed, command, success in sequence:
            set_state(args.fixture, provider, allowed)
            output = session.command(command, timeout=600)
            (expected_success if success else expected_failure)(output, label)
            if session.process.pid != start_identity["pid"] or session.process.poll() is not None:
                raise AssertionError(f"sbt process changed or exited at {label}")
            record_step(args.evidence, label, output, args.fixture, args.lane,
                        {"process": process_identity(session.process)})

        before = tree_hashes(args.fixture, args.lane)
        output = session.command("compile", timeout=600)
        expected_success(output, "S9-noop")
        after = tree_hashes(args.fixture, args.lane)
        if before != after or re.search(r"compiling [0-9]+ Scala source", output):
            raise AssertionError(f"S9-noop was not a stable no-op:\n{output}")
        record_step(args.evidence, "S9-noop", output, args.fixture, args.lane)

        output = session.command("freshDownstream/compile; recordM5CInputs", timeout=600)
        expected_success(output, "S10-fresh-downstream")
        if not re.search(rf"compiling 1 Scala source to .*/fresh-downstream/target/scala-{re.escape(args.lane)}/classes", output):
            raise AssertionError(f"fresh downstream was not first compiled at S10:\n{output}")
        record_step(args.evidence, "S10-fresh-downstream", output, args.fixture, args.lane)

        output = session.command("recordM5CIdentity", timeout=120)
        expected_success(output, "identity-end")
        end_identity = process_identity(session.process)
        if start_identity != end_identity:
            raise AssertionError(f"sbt process identity changed: {start_identity} != {end_identity}")
        (args.evidence / "process-end.json").write_text(json.dumps(end_identity, sort_keys=True) + "\n")
    finally:
        exit_code = session.stop()
        (args.evidence / "session.log").write_bytes(session.log.snapshot())
    if exit_code != 0:
        raise AssertionError(f"persistent sbt process exited with {exit_code}")
    identities = (args.fixture / "m5c-identities.txt").read_text(encoding="utf-8").splitlines()
    if len(identities) != 2 or identities[0] != identities[1]:
        raise AssertionError(f"JVM identity changed inside persistent sbt session: {identities}")
    lane_key = args.lane.replace(".", "_")
    summary = [
        f"PERSISTENT_SBT_SESSION_{lane_key}=PASS",
        f"PERSISTENT_SBT_SINGLE_PROCESS_{lane_key}=YES",
        f"PERSISTENT_SBT_FAILURE_RECOVERY_{lane_key}=PASS",
        f"PERSISTENT_SBT_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_{lane_key}=PASS",
        f"PERSISTENT_SBT_PERMISSION_REPAIR_{lane_key}=PASS",
        f"PERSISTENT_SBT_SIBLING_ISOLATION_{lane_key}=PASS",
        f"PERSISTENT_SBT_NOOP_{lane_key}=YES",
        f"PERSISTENT_SBT_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS_{lane_key}=PASS",
    ]
    (args.work / "persistent-sbt-summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")


def bsp_initialize(client, fixture):
    request_id = client.send_request("build/initialize", {
        "displayName": "allow-experimental-m5c",
        "version": "1.0",
        "bspVersion": "2.1.0-M1",
        "rootUri": fixture.as_uri(),
        "capabilities": {"languageIds": ["scala"]},
        "data": None,
    })
    response, notifications = client.response(request_id)
    if "error" in response or response.get("result", {}).get("displayName") != "sbt":
        raise AssertionError(f"BSP initialization failed: {response}")
    client.send_notification("build/initialized", {})
    return notifications


def validate_bsp_compile(response, notifications, expect_success, label):
    error = response.get("error")
    status = response.get("result", {}).get("statusCode")
    if expect_success:
        if error is not None:
            raise AssertionError(f"{label} returned JSON-RPC error: {response}")
        if status != 1:
            raise AssertionError(f"{label} status {status}, expected 1: {response}")
        return

    compile_failed = error is not None and "Compilation failed" in error.get("message", "")
    if status != 2 and not compile_failed:
        raise AssertionError(f"{label} did not report a compile failure: {response}")
    diagnostics = [message for message in notifications
                   if message.get("method") == "build/publishDiagnostics"]
    diagnostic_text = json.dumps(diagnostics)
    if "marked @experimental" not in diagnostic_text:
        raise AssertionError(f"{label} lacked stock experimental BSP diagnostic: {diagnostics}")


def bsp_compile(client, target, label, expect_success):
    request_id = client.send_request("buildTarget/compile", {
        "targets": [target], "originId": label, "arguments": []
    })
    response, notifications = client.response(request_id, timeout=600)
    validate_bsp_compile(response, notifications, expect_success, label)
    return response, notifications


def bsp_scalac_options(client, targets):
    request_id = client.send_request("buildTarget/scalacOptions", {"targets": targets})
    response, notifications = client.response(request_id, timeout=300)
    if "error" in response:
        raise AssertionError(f"buildTarget/scalacOptions failed: {response}; notifications={notifications}")
    items = response.get("result", {}).get("items", [])
    if len(items) != len(targets):
        raise AssertionError(f"buildTarget/scalacOptions returned {len(items)} items for {len(targets)} targets")
    return items


def run_bsp(args):
    set_state(args.fixture, "ordinary", "unmarked")
    environment = sbt_environment(args, "bsp")
    server = SbtSession(args.fixture, environment)
    server_start = process_identity(server.process)
    (args.evidence / "server-process-start.json").write_text(json.dumps(server_start, sort_keys=True) + "\n")
    proxy = None
    proxy_errors = None
    try:
        output = server.command("recordM5CIdentity", timeout=120)
        expected_success(output, "BSP server identity start")
        active = args.fixture / "project/target/active.json"
        if not active.is_file():
            raise AssertionError("real sbt server did not create project/target/active.json")
        (args.evidence / "active.json").write_bytes(active.read_bytes())

        proxy = subprocess.Popen(
            ["sbt", "-bsp"], cwd=args.fixture, env=environment,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        proxy_errors = ProcessLog(proxy.stderr)
        (args.evidence / "proxy-process.json").write_text(
            json.dumps(process_identity(proxy), sort_keys=True) + "\n", encoding="utf-8")
        traffic = args.evidence / "jsonrpc.jsonl"
        client = JsonRpcClient(proxy, traffic)
        bsp_initialize(client, args.fixture)
        if client.initialize_count != 1:
            raise AssertionError(f"BSP initialize count was {client.initialize_count}")

        request_id = client.send_request("workspace/buildTargets", {})
        response, _ = client.response(request_id, timeout=300)
        if "error" in response:
            raise AssertionError(f"workspace/buildTargets failed: {response}")
        targets = response["result"]
        (args.evidence / "build-targets.json").write_text(
            json.dumps(targets, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        selected = {name: select_compile_target(targets, name)
                    for name in ("allowed", "consumer", "freshDownstream")}
        option_items = bsp_scalac_options(client, [selected["consumer"], selected["freshDownstream"]])
        classpath = [entry for item in option_items for entry in item.get("classpath", [])]
        if any("allow-experimental-annotation" in entry or "allow-experimental-plugin" in entry
               for entry in classpath):
            raise AssertionError(f"BSP consumer/downstream classpath leaked Allow artifacts: {classpath}")
        (args.evidence / "scalac-options.json").write_text(
            json.dumps(option_items, indent=2, sort_keys=True) + "\n", encoding="utf-8")

        response, notifications = bsp_compile(client, selected["consumer"], "B0-baseline", True)
        record_step(args.evidence, "B0-baseline", json.dumps({"response": response, "notifications": notifications}, indent=2),
                    args.fixture, args.lane)
        sequence = [
            ("B1-provider-experimental-reject", "experimental", "unmarked", "allowed", False),
            ("B2-permission-repair", "experimental", "marked", "consumer", True),
            ("B3-permission-remove-reject", "experimental", "unmarked", "allowed", False),
            ("B4-permission-restore", "experimental", "marked", "consumer", True),
            ("B5-sibling-reject", "experimental", "sibling", "allowed", False),
            ("B6-sibling-repair", "experimental", "marked", "consumer", True),
        ]
        for label, provider, allowed, target_name, success in sequence:
            set_state(args.fixture, provider, allowed)
            response, notifications = bsp_compile(client, selected[target_name], label, success)
            if server.process.poll() is not None or process_identity(server.process) != server_start:
                raise AssertionError(f"BSP server changed or exited at {label}")
            record_step(args.evidence, label,
                        json.dumps({"response": response, "notifications": notifications}, indent=2),
                        args.fixture, args.lane, {"serverProcess": process_identity(server.process)})

        before = tree_hashes(args.fixture, args.lane)
        response, notifications = bsp_compile(client, selected["consumer"], "B7-no-change", True)
        after = tree_hashes(args.fixture, args.lane)
        if before != after:
            raise AssertionError("BSP no-change compile changed source/output/analysis hashes")
        record_step(args.evidence, "B7-no-change",
                    json.dumps({"response": response, "notifications": notifications}, indent=2),
                    args.fixture, args.lane)

        downstream_classes = args.fixture / f"fresh-downstream/target/scala-{args.lane}/classes"
        if downstream_classes.exists() and any(downstream_classes.rglob("*.class")):
            raise AssertionError("fresh downstream already had class files before its BSP compile")
        response, notifications = bsp_compile(client, selected["freshDownstream"], "B8-fresh-downstream", True)
        if not downstream_classes.exists() or not any(downstream_classes.rglob("*.class")):
            raise AssertionError("fresh downstream BSP compile produced no class files")
        record_step(args.evidence, "B8-fresh-downstream",
                    json.dumps({"response": response, "notifications": notifications}, indent=2),
                    args.fixture, args.lane)

        shutdown_id = client.send_request("build/shutdown", {})
        shutdown_response, _ = client.response(shutdown_id, timeout=120)
        if shutdown_response.get("result", "missing") is not None:
            raise AssertionError(f"unexpected BSP shutdown response: {shutdown_response}")
        client.send_notification("build/exit", {})
        proxy.stdin.close()
        proxy.wait(timeout=120)
        if proxy.returncode != 0:
            raise AssertionError(f"BSP proxy exited with {proxy.returncode}")
        output = server.command("recordM5CIdentity", timeout=120)
        expected_success(output, "BSP server identity end")
        server_end = process_identity(server.process)
        if server_start != server_end:
            raise AssertionError(f"BSP server identity changed: {server_start} != {server_end}")
        (args.evidence / "server-process-end.json").write_text(json.dumps(server_end, sort_keys=True) + "\n")
    finally:
        if proxy is not None and proxy.poll() is None:
            proxy.terminate()
            proxy.wait(timeout=30)
        if proxy_errors is not None:
            (args.evidence / "proxy-stderr.log").write_bytes(proxy_errors.snapshot())
        exit_code = server.stop()
        (args.evidence / "server-session.log").write_bytes(server.log.snapshot())
    if exit_code != 0:
        raise AssertionError(f"BSP sbt server exited with {exit_code}")
    identities = (args.fixture / "m5c-identities.txt").read_text(encoding="utf-8").splitlines()
    if len(identities) != 2 or identities[0] != identities[1]:
        raise AssertionError(f"JVM identity changed inside BSP server session: {identities}")
    lane_key = args.lane.replace(".", "_")
    summary = [
        f"BSP_SESSION_{lane_key}=PASS",
        f"BSP_REAL_SBT_SERVER_{lane_key}=YES",
        f"BSP_SINGLE_SERVER_PROCESS_{lane_key}=YES",
        f"BSP_INITIALIZE_ONCE_{lane_key}=YES",
        f"BSP_FAILURE_RECOVERY_{lane_key}=PASS",
        f"BSP_PROVIDER_BECOMES_EXPERIMENTAL_INVALIDATES_ALLOWED_{lane_key}=PASS",
        f"BSP_PERMISSION_REPAIR_{lane_key}=PASS",
        f"BSP_SIBLING_ISOLATION_{lane_key}=PASS",
        f"BSP_STABLE_NO_CHANGE_{lane_key}=PASS",
        f"BSP_DOWNSTREAM_WITHOUT_ALLOW_ARTIFACTS_{lane_key}=PASS",
        f"BSP_BUILD_SEMANTICS_{lane_key}=PASS",
    ]
    (args.work / "bsp-summary.txt").write_text("\n".join(summary) + "\n", encoding="utf-8")


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("persistent-sbt", "bsp"))
    parser.add_argument("--lane", required=True)
    parser.add_argument("--fixture", required=True, type=pathlib.Path)
    parser.add_argument("--evidence", required=True, type=pathlib.Path)
    parser.add_argument("--work", required=True, type=pathlib.Path)
    parser.add_argument("--runtime", required=True, type=pathlib.Path)
    return parser.parse_args()


def main():
    args = parse_args()
    args.fixture = args.fixture.resolve()
    args.evidence = args.evidence.resolve()
    args.work = args.work.resolve()
    args.runtime = args.runtime.resolve()
    args.evidence.mkdir(parents=True, exist_ok=True)
    args.runtime.mkdir(parents=True, exist_ok=True)
    if args.mode == "persistent-sbt":
        run_persistent_sbt(args)
    else:
        run_bsp(args)


if __name__ == "__main__":
    main()

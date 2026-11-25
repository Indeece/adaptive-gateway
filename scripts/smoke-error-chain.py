"""Check Inventory error forwarding through the packaged Gateway and Checkout."""

import contextlib
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError, URLError
from urllib.request import urlopen
from uuid import uuid4


ROOT = Path(__file__).resolve().parents[1]


def request(url):
    try:
        response = urlopen(url, timeout=3)
    except HTTPError as error:
        response = error
    with response:
        return response.status, json.load(response)


def available_port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


@contextlib.contextmanager
def application(name, port, inventory_url, checkout_url, log_directory):
    jar = ROOT / name / "target" / f"{name}-0.0.1-SNAPSHOT.jar"
    if not jar.is_file():
        raise RuntimeError(f"Build the project with Maven verify first: {jar}")
    command = [
        "java", f"-Dserver.port={port}", "-Dserver.address=127.0.0.1",
        f"-Dupstream.inventory.base-url={inventory_url}",
        f"-Dupstream.checkout.base-url={checkout_url}", "-jar", str(jar),
    ]
    with (log_directory / f"{name}.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
        )
        try:
            deadline = time.monotonic() + 60
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"{name} exited; see {log.name}")
                try:
                    status, body = request(f"http://127.0.0.1:{port}/actuator/health/readiness")
                    if status == 200 and body.get("status") == "UP":
                        break
                except (URLError, TimeoutError):
                    pass
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"{name} did not become ready; see {log.name}")
                time.sleep(0.1)
            yield
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


class InventoryStub(BaseHTTPRequestHandler):
    status_code = 503
    code = "inventory_overload"
    paths = []

    def do_GET(self):
        type(self).paths.append(self.path)
        body = json.dumps({"code": self.code, "message": "Controlled Inventory error"}).encode()
        self.send_response(self.status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        pass


def main():
    logs = ROOT / "target" / "error-chain-smoke" / uuid4().hex
    logs.mkdir(parents=True)
    with ThreadingHTTPServer(("127.0.0.1", 0), InventoryStub) as stub:
        server_thread = threading.Thread(target=stub.serve_forever, daemon=True)
        server_thread.start()
        try:
            inventory_url = f"http://127.0.0.1:{stub.server_port}"
            checkout_port = available_port()
            checkout_url = f"http://127.0.0.1:{checkout_port}"
            with application("checkout", checkout_port, inventory_url, checkout_url, logs):
                gateway_port = available_port()
                with application("gateway", gateway_port, inventory_url, checkout_url, logs):
                    for status, code in [(503, "inventory_overload"), (504, "inventory_deadline")]:
                        InventoryStub.status_code = status
                        InventoryStub.code = code
                        for route, upstream_path in [
                            ("availability", "/availability/SKU-001"),
                            ("quotes", "/internal/batch-availability?sku=SKU-001"),
                        ]:
                            InventoryStub.paths.clear()
                            actual_status, body = request(f"http://127.0.0.1:{gateway_port}/{route}/SKU-001")
                            if (actual_status, body.get("code")) != (status, code):
                                raise AssertionError((route, actual_status, body))
                            if InventoryStub.paths != [upstream_path]:
                                raise AssertionError(InventoryStub.paths)
                            print(f"{route}: {status} {code}, exactly one Inventory call")
            print(f"Error chain smoke passed. Logs: {logs}")
        finally:
            stub.shutdown()
            server_thread.join(timeout=5)


if __name__ == "__main__":
    main()

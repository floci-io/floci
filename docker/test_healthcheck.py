"""Run with python3 -m unittest discover -s docker -p 'test_healthcheck.py'."""
import os
from pathlib import Path
import socket
import subprocess
import threading
import unittest


SCRIPT = Path(__file__).with_name("healthcheck.sh")


class HealthcheckTest(unittest.TestCase):
    def probe(
        self,
        settings: dict[str, str],
        host: str = "127.0.0.1",
        status: int = 200,
        body: str = '{"ready":true}',
    ) -> tuple[subprocess.CompletedProcess[bytes], list[bytes]]:
        with socket.socket(socket.AF_INET6 if ":" in host else socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind((host, 0))
            listener.listen(1)
            listener.settimeout(2)
            port = str(listener.getsockname()[1])
            env = {key: value for key, value in os.environ.items()
                   if not key.startswith(("QUARKUS_HTTP_", "FLOCI_PORT", "FLOCI_TLS_ENABLED"))
                   and key not in ("USE_SSL", "LOCALSTACK_PARITY")}
            env.update({key: port if value == "PORT" else value for key, value in settings.items()})
            requests: list[bytes] = []

            def serve() -> None:
                try:
                    connection, _ = listener.accept()
                except socket.timeout:
                    return
                with connection:
                    connection.settimeout(2)
                    request = b""
                    while b"\r\n\r\n" not in request:
                        chunk = connection.recv(4096)
                        if not chunk:
                            break
                        request += chunk
                    requests.append(request)
                    connection.sendall(f"HTTP/1.0 {status} Test\r\nContent-Length: {len(body)}\r\n\r\n{body}".encode())

            thread = threading.Thread(target=serve)
            thread.start()
            try:
                result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, timeout=3)
            finally:
                thread.join(timeout=3)
            self.assertFalse(thread.is_alive())
            return result, requests

    def test_configured_interface_and_http_port(self):
        result, requests = self.probe({"QUARKUS_HTTP_HOST": "127.0.0.2", "QUARKUS_HTTP_PORT": "PORT"}, host="127.0.0.2")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(b"GET /_floci/init HTTP/1.0", requests[0])

    def test_floci_port(self):
        result, _ = self.probe({"FLOCI_PORT": "PORT"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_quarkus_port_precedes_floci_port(self):
        result, _ = self.probe({"QUARKUS_HTTP_PORT": "PORT", "FLOCI_PORT": "1"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_tls_uses_public_multiplexed_port(self):
        result, _ = self.probe({"FLOCI_TLS_ENABLED": "TRUE", "FLOCI_PORT": "PORT", "QUARKUS_HTTP_PORT": "1", "QUARKUS_HTTP_SSL_PORT": "2"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_localstack_tls_uses_public_multiplexed_port(self):
        for floci_setting in (None, ""):
            with self.subTest(floci_setting=floci_setting):
                settings = {"USE_SSL": "1", "FLOCI_PORT": "PORT", "QUARKUS_HTTP_PORT": "1"}
                if floci_setting is not None:
                    settings["FLOCI_TLS_ENABLED"] = floci_setting
                result, _ = self.probe(settings)
                self.assertEqual(0, result.returncode, result.stderr)

    def test_explicit_floci_tls_setting_precedes_localstack_tls(self):
        for floci_setting in ("false", " \t"):
            with self.subTest(floci_setting=floci_setting):
                result, _ = self.probe({"FLOCI_TLS_ENABLED": floci_setting, "USE_SSL": "1",
                                        "QUARKUS_HTTP_PORT": "PORT", "FLOCI_PORT": "1"})
                self.assertEqual(0, result.returncode, result.stderr)

    def test_disabled_parity_ignores_localstack_tls(self):
        result, _ = self.probe({"LOCALSTACK_PARITY": "false", "USE_SSL": "1",
                                "QUARKUS_HTTP_PORT": "PORT", "FLOCI_PORT": "1"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_explicit_floci_tls_works_with_parity_disabled(self):
        result, _ = self.probe({"LOCALSTACK_PARITY": "false", "FLOCI_TLS_ENABLED": "true",
                                "QUARKUS_HTTP_PORT": "1", "FLOCI_PORT": "PORT"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_wildcard_uses_loopback(self):
        result, _ = self.probe({"QUARKUS_HTTP_HOST": "0.0.0.0", "FLOCI_PORT": "PORT"})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_ipv6_wildcard_uses_loopback(self):
        result, _ = self.probe({"QUARKUS_HTTP_HOST": "::", "FLOCI_PORT": "PORT"}, host="::1")
        self.assertEqual(0, result.returncode, result.stderr)

    def test_failure_status_stays_unhealthy(self):
        result, requests = self.probe({"FLOCI_PORT": "PORT"}, status=503)
        self.assertTrue(requests)
        self.assertNotEqual(0, result.returncode)

    def test_closed_port_stays_unhealthy(self):
        with socket.socket() as reserved:
            reserved.bind(("127.0.0.1", 0))
            env = dict(os.environ, QUARKUS_HTTP_HOST="127.0.0.1", QUARKUS_HTTP_PORT=str(reserved.getsockname()[1]), FLOCI_TLS_ENABLED="false")
            result = subprocess.run(["bash", str(SCRIPT)], env=env, capture_output=True, timeout=3)
        self.assertNotEqual(0, result.returncode)

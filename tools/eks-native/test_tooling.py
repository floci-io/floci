"""Regression checks for download integrity and teardown of interrupted fixtures."""

import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load("native_runner", ROOT / "tools/eks-native/run.py")
builder = load("native_builder", ROOT / "docker/ec2/ami-images/al2023-arm64/build.py")


addresses = load(
    "certificate_addresses",
    ROOT / "tools/eks-native/templates/certificate_addresses.py",
)


class ToolingTest(unittest.TestCase):
    def test_cloud_controller_dns_alias_does_not_invalidate_safe_earlier_csr(self):
        dns, ips = addresses.expected_names(
            [
                {"type": "Hostname", "address": "worker.local"},
                {"type": "InternalDNS", "address": "worker.local"},
                {"type": "ExternalDNS", "address": "10.0.0.2"},
                {"type": "InternalIP", "address": "10.0.0.2"},
            ]
        )
        self.assertEqual({"worker.local", "10.0.0.2"}, dns)
        self.assertTrue(
            addresses.matches_node("worker.local", {"worker.local"}, ips, dns, ips)
        )
        self.assertFalse(
            addresses.matches_node(
                "worker.local", {"worker.local", "other.local"}, ips, dns, ips
            )
        )
        self.assertFalse(
            addresses.matches_node(
                "worker.local", {"worker.local"}, {"10.0.0.3"}, dns, ips
            )
        )
        self.assertEqual({"10.0.0.2"}, ips)
        with self.assertRaises(ValueError):
            addresses.expected_names(
                [{"type": "InternalIP", "address": "invalid.local"}]
            )

    def test_checksum_failure_does_not_install_download(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source"
            target = Path(directory) / "download"
            source.write_bytes(b"unexpected executable")
            with self.assertRaisesRegex(ValueError, "Checksum mismatch"):
                builder.download(source.as_uri(), target, "0" * 64)
            self.assertFalse(target.exists())
            builder.download(
                source.as_uri(), target, hashlib.sha256(source.read_bytes()).hexdigest()
            )
            self.assertEqual(source.read_bytes(), target.read_bytes())

    def test_command_timeout_is_reported_as_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            run = runner.Run.__new__(runner.Run)
            run.out = Path(directory)
            run.env = dict(os.environ)
            run.records = []
            result = run.command(
                "timeout",
                [sys.executable, "-c", "import time; time.sleep(60)"],
                timeout=0.1,
                required=False,
            )
            self.assertIsNone(result)
            self.assertNotEqual(0, run.records[0]["exit"])
            self.assertIn("TIMEOUT", (run.out / "timeout.log").read_text())

    def test_cleanup_handles_async_removal_and_preserves_unrelated_containers(self):
        run = runner.Run.__new__(runner.Run)
        run.aws = lambda *args, **kwargs: None
        calls = []

        def command(name, args, *unused, **kwargs):
            calls.append(args)
            if name == "cleanup-inventory":
                return runner.PREFIX + "test\nunrelated-database\n"
            if name == "cleanup-confirm-containers":
                return "unrelated-database\n"
            if name == "cleanup-confirm-networks":
                return "unrelated-network\n"
            return None  # Includes Docker's already-removing response.

        run.command = command
        self.assertEqual([], run.cleanup())
        removed = [args[-1] for args in calls if args[:3] == ["docker", "rm", "-f"]]
        self.assertEqual([runner.PREFIX + "test"], removed)


if __name__ == "__main__":
    unittest.main()

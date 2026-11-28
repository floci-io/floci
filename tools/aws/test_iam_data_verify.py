"""Exercise IAM gate selection without contacting the live AWS reference."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class IamDataVerifyTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.calls = self.directory / "calls"
        self.make = self.directory / "make"
        self.make.write_text(
            '#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\n'
            '[ "$*" != "--no-print-directory $FAIL_TARGET" ]\n'
        )
        self.make.chmod(0o700)

    def run_gate(self, *arguments, fail_target=""):
        result = subprocess.run(
            ["make", "--no-print-directory", "iam-data-verify", f"MAKE={self.make}", *arguments],
            cwd=Path(__file__).resolve().parents[2],
            env={**os.environ, "CALLS": str(self.calls), "FAIL_TARGET": fail_target},
            text=True, capture_output=True,
        )
        calls = self.calls.read_text().splitlines() if self.calls.exists() else []
        return result, [line.split()[-1] for line in calls]

    def changed_file(self, text):
        path = self.directory / "changed"
        path.write_text(text)
        return f"CHANGED_FILE={path}"

    def test_large_diff_selects_only_affected_check(self):
        paths = "".join(f"src/test/unrelated/path_{index}.java\n" for index in range(10000))
        paths += "src/main/resources/aws/iam-service-namespaces.json\n"
        self.assertGreater(len(paths), 131072)
        result, calls = self.run_gate(self.changed_file(paths))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["iam-namespaces-verify"], calls)

    def test_unrelated_diff_runs_no_checks(self):
        result, calls = self.run_gate(self.changed_file("docs/index.md\n"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], calls)

    def test_empty_diff_keeps_full_verification(self):
        result, calls = self.run_gate(self.changed_file(""))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(13, len(calls))

    def test_weekly_run_checks_every_file(self):
        result, calls = self.run_gate()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(13, len(calls))

    def test_the_cloudtrail_s3_data_events_check_covers_its_fetcher(self):
        result, calls = self.run_gate(self.changed_file("tools/aws/fetch_cloudtrail_s3_data_events.py\n"))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["cloudtrail-s3-data-events-verify"], calls)

    def test_legacy_changed_argument_still_selects(self):
        result, calls = self.run_gate("CHANGED=  tools/aws/regen_service_namespaces.py  ")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["iam-namespaces-verify"], calls)

    def test_missing_file_fails_without_silently_skipping(self):
        result, calls = self.run_gate(f"CHANGED_FILE={self.directory / 'missing'}")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], calls)

    def test_failed_check_does_not_hide_later_checks(self):
        result, calls = self.run_gate(self.changed_file(
            "tools/aws/regen_service_namespaces.py\ntools/aws/regen_iam_action_resources.py\n"
        ), fail_target="iam-namespaces-verify")
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(["iam-namespaces-verify", "iam-action-resources-verify"], calls)
        self.assertIn("the service namespace list", result.stdout)


if __name__ == "__main__":
    unittest.main()

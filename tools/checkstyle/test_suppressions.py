"""Keep tools/checkstyle/suppressions.xml exact and independent of the Java package name.

Every entry is meant to excuse one file until that file is cleaned up. An entry that matches no
file is dead weight, and one that matches several hides violations nobody chose to excuse. The
paths start below the package root, so they keep matching when the package moves
(io.github.hectorvent.floci to io.floci.aws).
"""

from pathlib import Path
import re
import subprocess
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SUPPRESSIONS = ROOT / "tools/checkstyle/suppressions.xml"
SOURCE_ROOTS = (
    "src/main/java/",
    "src/test/java/",
    "compatibility-tests/sdk-test-java/src/main/java/",
    "compatibility-tests/sdk-test-java/src/test/java/",
)
PACKAGE_PATH = "io/github/hectorvent/floci/"
RENAMED_PATH = "io/floci/aws/"


def checked_files():
    out = subprocess.run(["git", "ls-files", "-z", "--", *SOURCE_ROOTS], cwd=ROOT, check=True,
                         capture_output=True).stdout.decode("utf-8")
    return ["/" + path for path in out.split("\0") if path.endswith(".java")]


def suppressions():
    return [(entry.get("id"), entry.get("files")) for entry in ET.parse(SUPPRESSIONS).getroot().iter("suppress")]


class SuppressionsTest(unittest.TestCase):
    def test_every_entry_matches_exactly_one_file(self):
        files = checked_files()
        self.assertGreater(len(files), 1000, "the source scan found almost no Java files")
        problems = []
        for rule, pattern in suppressions():
            matches = [path for path in files if re.search(pattern, path)]
            if len(matches) != 1:
                problems.append(f"{rule} {pattern}: {len(matches)} matches {matches[:3]}")
        self.assertEqual([], problems)

    def test_entries_survive_the_package_rename(self):
        renamed = [path.replace("/" + PACKAGE_PATH, "/" + RENAMED_PATH) for path in checked_files()]
        problems = []
        for rule, pattern in suppressions():
            matches = [path for path in renamed if re.search(pattern, path)]
            if len(matches) != 1:
                problems.append(f"{rule} {pattern}: {len(matches)} matches after the rename")
        self.assertEqual([], problems)

    def test_no_entry_names_the_package(self):
        offenders = [pattern for _rule, pattern in suppressions()
                     if pattern.startswith("/floci/") or "hectorvent" in pattern or "/io/" in pattern]
        self.assertEqual([], offenders, "start suppression paths below the package root (/services/...)")


if __name__ == "__main__":
    unittest.main()

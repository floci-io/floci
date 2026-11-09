import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / ".github/ci/neptune-image.sh"


class NeptuneImageTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.config = self.root / "src/main/resources/application.yml"
        self.config.parent.mkdir(parents=True)
        self.config.write_text(
            'floci:\n  services:\n    before:\n      default-image: "wrong:before"\n'
            '    neptune:\n      default-image: "example/gremlin:changed"\n'
            '      default-neo4j-image: "example/neo4j:changed"\n'
            '    after:\n      absent-image: "wrong:after"\n'
        )

    def read(self, *arguments):
        return subprocess.run(["bash", str(HELPER), *arguments], cwd=self.root,
                              capture_output=True, text=True)

    def test_reads_each_configured_key(self):
        for key, image in [("default-image", "example/gremlin:changed"),
                           ("default-neo4j-image", "example/neo4j:changed")]:
            with self.subTest(key=key):
                result = self.read(key)
                self.assertEqual(0, result.returncode)
                self.assertEqual(image + "\n", result.stdout)

    def test_missing_key_does_not_read_the_next_service(self):
        result = self.read("absent-image")
        self.assertEqual(0, result.returncode)
        self.assertEqual("", result.stdout)

    def test_missing_config_is_an_error(self):
        self.config.unlink()
        self.assertNotEqual(0, self.read("default-image").returncode)

    def test_requires_one_key(self):
        self.assertEqual(2, self.read().returncode)
        self.assertEqual(2, self.read("one", "two").returncode)

    def test_prefetch_uses_both_configured_images(self):
        ci = self.root / ".github/ci"
        ci.mkdir(parents=True)
        (ci / "neptune-image.sh").write_bytes(HELPER.read_bytes())
        tests = self.root / "src/test/java/testing"
        tests.mkdir(parents=True)
        (tests / "TestImages.java").write_bytes(next((ROOT / "src/test/java").rglob("TestImages.java")).read_bytes())
        shard = self.root / "shard.txt"
        shard.write_text("services/neptune/NeptuneIntegrationTest.java\n")
        tools = self.root / "bin"
        tools.mkdir()
        docker = tools / "docker"
        docker.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$DOCKER_CALLS"\n')
        docker.chmod(0o700)
        calls = self.root / "docker-calls.txt"
        env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"],
                   DOCKER_CALLS=str(calls))
        result = subprocess.run(["bash", str(ROOT / ".github/ci/prefetch-images.sh"), str(shard)],
                                cwd=self.root, env=env, capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual({"pull -q example/gremlin:changed", "pull -q example/neo4j:changed"},
                         set(calls.read_text().splitlines()))

    def test_workflow_consumers_share_the_helper(self):
        workflow = (ROOT / ".github/workflows/compatibility.yml").read_text()
        self.assertEqual(2, workflow.count("bash .github/ci/neptune-image.sh default-image"))
        self.assertNotIn("/^    neptune:/", workflow)
        self.assertNotIn("# checkout and the test image build", workflow)


if __name__ == "__main__":
    unittest.main()

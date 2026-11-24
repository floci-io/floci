"""Run with python3 -m unittest discover -s .github/ci -p 'test_select_nightly_checks.py'."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest

PATH = Path(__file__).with_name('select-nightly-checks.sh').resolve()
MAIN = 'src/main/java/io/floci/aws'
TEST = 'src/test/java/io/floci/aws'

# Class path -> source. Imports and names decide what a change selects, so each fixture carries only those.
SOURCES = {
    'services/rds/RdsMysqlDockerNightlyIntegrationTest': '',
    'services/cur/CurEmissionDockerNightlyIntegrationTest':
        'import io.floci.aws.services.floci.duck.FlociDuckClient;\nimport io.floci.aws.testing.TestImages;\n',
    'services/rds/export/RdsSnapshotExportDockerNightlyIntegrationTest':
        'import io.floci.aws.services.floci.duck.FlociDuckClient;\n',
    'services/redshift/RedshiftSpectrumDockerNightlyIntegrationTest': '',
    'services/redshift/RedshiftCopyJobDockerNightlyIntegrationTest': '',
    'services/firehose/FirehoseRedshiftDeliveryDockerNightlyIntegrationTest':
        'import io.floci.aws.services.redshift.RedshiftService;\n',
    'services/redshiftserverless/RedshiftServerlessRuntimeDockerNightlyIntegrationTest': '',
    'services/stepfunctions/StepFunctionsRedshiftDataDockerNightlyIntegrationTest': '',
    'services/emr/EmrDockerRunnerNightlyIntegrationTest': '',
    'services/eks/EksEcrMirrorNightlyIntegrationTest': '',
}
E2E = sorted('io/floci/aws/' + name for name in SOURCES if name.endswith('DockerNightlyIntegrationTest'))


class SelectNightlyChecksTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        for name, body in SOURCES.items():
            path = self.root / TEST / (name + '.java')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body + 'class Fixture {}\n')

    def select(self, files=None):
        """The selected classes per group, for a pull request changing files, or for a scheduled run when None."""
        output = self.root / 'output.txt'
        output.write_text('')
        env = dict(os.environ, GITHUB_OUTPUT=str(output))
        env.pop('PR', None)
        if files is not None:
            fixture = self.root / 'files.json'
            fixture.write_text(json.dumps([{'filename': f, 'patch': p} for f, p in files.items()]))
            env.update(PR='1', SELECT_NIGHTLY_FILES=str(fixture))
        result = subprocess.run(['bash', str(PATH)], cwd=self.root, capture_output=True, text=True, env=env)
        self.assertEqual(0, result.returncode, result.stderr)
        matrix = json.loads(output.read_text().strip().removeprefix('matrix='))
        return {entry['group']: sorted(entry['classes'].split()) for entry in matrix}

    def e2e(self, files):
        return self.select(files).get('docker-e2e', [])

    def test_scheduleRunsEveryCheck(self):
        groups = self.select()
        self.assertEqual(E2E, groups['docker-e2e'])
        self.assertEqual(['io/floci/aws/services/emr/EmrDockerRunnerNightlyIntegrationTest'], groups['docker-runner'])
        self.assertEqual(['io/floci/aws/services/eks/EksEcrMirrorNightlyIntegrationTest'], groups['standard'])

    def test_changingTheSelectorOrItsWorkflowRunsEveryCheck(self):
        for path in ('.github/workflows/nightly-checks.yml', '.github/ci/select-nightly-checks.sh',
                     '.github/ci/test_select_nightly_checks.py'):
            with self.subTest(path=path):
                self.assertEqual(3, len(self.select({path: '+x'})))

    def test_aServiceSelectsItsOwnChecks(self):
        self.assertEqual(['io/floci/aws/services/rds/RdsMysqlDockerNightlyIntegrationTest',
                          'io/floci/aws/services/rds/export/RdsSnapshotExportDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/rds/RdsService.java': '+x'}))

    def test_flociDuckSelectsTheChecksThatImportItAndItsConsumers(self):
        self.assertEqual(['io/floci/aws/services/cur/CurEmissionDockerNightlyIntegrationTest',
                          'io/floci/aws/services/rds/export/RdsSnapshotExportDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftSpectrumDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/floci/duck/FlociDuckManager.java': '+x'}))

    def test_redshiftSelectsTheServicesThatRunOnItsEngine(self):
        self.assertEqual(['io/floci/aws/services/firehose/FirehoseRedshiftDeliveryDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftCopyJobDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftSpectrumDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshiftserverless/'
                          'RedshiftServerlessRuntimeDockerNightlyIntegrationTest',
                          'io/floci/aws/services/stepfunctions/StepFunctionsRedshiftDataDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/redshift/RedshiftService.java': '+x'}))

    def test_anImagePinSelectsEveryContainerCheck(self):
        pins = (('src/main/resources/application.yml',
                 '@@ -1 +1 @@\n-      image: postgres:16\n+      image: postgres:17'),
                ('src/test/resources/application.yml', '+      default-image: mysql:8.4'),
                (MAIN + '/config/EmulatorConfig.java', '-        String DEFAULT_MYSQL_IMAGE = "mysql:8.0";'),
                # GitHub sends no patch for a large file, so its change might be a pin.
                ('src/main/resources/application.yml', None))
        for path, patch in pins:
            with self.subTest(path=path, patch=patch):
                self.assertEqual(E2E, self.e2e({path: patch}))

    def test_aConfigChangeWithoutAnImageSelectsNoContainerCheck(self):
        self.assertEqual({}, self.select({'src/main/resources/application.yml': '@@ -1 +1 @@\n+      enabled: true',
                                          MAIN + '/config/EmulatorConfig.java': '+    boolean enabled();'}))

    def test_aTestHelperSelectsTheChecksThatNameIt(self):
        self.assertEqual(['io/floci/aws/services/cur/CurEmissionDockerNightlyIntegrationTest'],
                         self.e2e({TEST + '/testing/TestImages.java': '+x'}))

    def test_aChangedNightlyCheckRunsInItsGroup(self):
        self.assertEqual({'standard': ['io/floci/aws/services/eks/EksEcrMirrorNightlyIntegrationTest']},
                         self.select({TEST + '/services/eks/EksEcrMirrorNightlyIntegrationTest.java': '+x'}))

    def test_aServiceWithoutContainerChecksSelectsNothing(self):
        self.assertEqual({}, self.select({MAIN + '/services/sqs/SqsService.java': '+x'}))


if __name__ == '__main__':
    unittest.main()

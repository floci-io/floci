"""Run with python3 -m unittest discover -s .github/ci -p 'test_select_nightly_checks.py'."""
from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest
import unittest.mock

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
    'services/redshift/RedshiftS3AutoCopyDockerNightlyIntegrationTest': '',
    'services/cur/CurDailyEmissionAccountIsolationDockerNightlyIntegrationTest': '',
    'services/cur/CurNonDefaultAccountEmissionDockerNightlyIntegrationTest': '',
    'services/cur/ParquetEmitterDockerNightlyIntegrationTest': 'import io.floci.aws.services.s3.S3Service;\n',
    'services/emrserverless/EmrServerlessHiveDockerNightlyIntegrationTest': '',
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

    def test_dnsChangesStartThePullRequestWorkflow(self):
        workflow = PATH.parent.parent / 'workflows' / 'nightly-checks.yml'
        pull_request = workflow.read_text().split('  pull_request:', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn("      - 'src/main/java/io/floci/aws/core/common/dns/**'", pull_request)

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
        self.assertEqual(['io/floci/aws/services/cur/CurDailyEmissionAccountIsolationDockerNightlyIntegrationTest',
                          'io/floci/aws/services/cur/CurEmissionDockerNightlyIntegrationTest',
                          'io/floci/aws/services/cur/CurNonDefaultAccountEmissionDockerNightlyIntegrationTest',
                          'io/floci/aws/services/rds/export/RdsSnapshotExportDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftSpectrumDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/floci/duck/FlociDuckManager.java': '+x'}))

    def test_redshiftSelectsTheServicesThatRunOnItsEngine(self):
        self.assertEqual(['io/floci/aws/services/firehose/FirehoseRedshiftDeliveryDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftCopyJobDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftS3AutoCopyDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftSpectrumDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshiftserverless/'
                          'RedshiftServerlessRuntimeDockerNightlyIntegrationTest',
                          'io/floci/aws/services/stepfunctions/StepFunctionsRedshiftDataDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/redshift/RedshiftService.java': '+x'}))

    def test_s3SelectsTheChecksThatImportItAndThoseThatReachItThroughItsApi(self):
        self.assertEqual(['io/floci/aws/services/cur/CurDailyEmissionAccountIsolationDockerNightlyIntegrationTest',
                          'io/floci/aws/services/cur/CurNonDefaultAccountEmissionDockerNightlyIntegrationTest',
                          'io/floci/aws/services/cur/ParquetEmitterDockerNightlyIntegrationTest',
                          'io/floci/aws/services/emrserverless/EmrServerlessHiveDockerNightlyIntegrationTest',
                          'io/floci/aws/services/redshift/RedshiftS3AutoCopyDockerNightlyIntegrationTest'],
                         self.e2e({MAIN + '/services/s3/S3Service.java': '+x'}))

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

    def test_theSelectionDoesNotDependOnTheCallersLocale(self):
        # The lists are sorted bytewise; comm would refuse or misjoin them under a locale that collates otherwise.
        with unittest.mock.patch.dict(os.environ, LC_ALL='en_US.UTF-8', LANG='en_US.UTF-8'):
            self.assertEqual(['io/floci/aws/services/rds/RdsMysqlDockerNightlyIntegrationTest',
                              'io/floci/aws/services/rds/export/RdsSnapshotExportDockerNightlyIntegrationTest'],
                             self.e2e({MAIN + '/services/rds/RdsService.java': '+x'}))

    def test_aServiceWithoutContainerChecksSelectsNothing(self):
        self.assertEqual({}, self.select({MAIN + '/services/sqs/SqsService.java': '+x'}))


ROOT = Path(__file__).resolve().parents[2]
TESTS = ROOT / 'src/test/java'
DNS = 'io/floci/aws/core/common/dns/'
NAMES = [kind + 'PrivateDnsGuestDockerNightlyIntegrationTest' for kind in
         ('Ec2', 'Ec2Protected', 'Ecs', 'EcsProtected', 'EcsShared')]
EXPECTED = {DNS + name for name in NAMES}


class DnsGuestNightlyTest(unittest.TestCase):
    def select(self, changed=None):
        with tempfile.TemporaryDirectory() as directory:
            env = dict(os.environ)
            for key in ('PR', 'SELECT_NIGHTLY_FILES', 'GITHUB_OUTPUT'):
                env.pop(key, None)
            if changed is not None:
                files = Path(directory) / 'files.json'
                files.write_text(json.dumps([{'filename': path, 'patch': '+changed'} for path in changed]))
                env.update(PR='1', SELECT_NIGHTLY_FILES=str(files))
            result = subprocess.run(['bash', '.github/ci/select-nightly-checks.sh'], cwd=ROOT,
                                    env=env, capture_output=True, text=True, check=True)
            return {name for group in json.loads(result.stdout) if group['group'] == 'docker-e2e'
                    for name in group['classes'].split()}

    def test_schedule_discovers_all_five_guests(self):
        self.assertTrue(EXPECTED <= self.select())
        for name in NAMES:
            self.assertTrue((TESTS / DNS / (name + '.java')).is_file())

    def test_each_guest_change_selects_itself(self):
        for name in NAMES:
            with self.subTest(name=name):
                self.assertIn(DNS + name, self.select(['src/test/java/' + DNS + name + '.java']))

    def test_services_select_inherited_guests(self):
        for service in ('ec2', 'ecs', 'route53', 'cloudmap'):
            expected = {name for name in EXPECTED if service != 'ecs' or '/Ecs' in name}
            with self.subTest(service=service):
                selected = self.select(['src/main/java/io/floci/aws/services/' + service + '/Service.java'])
                self.assertEqual(expected, selected & EXPECTED)

    def test_base_guest_change_selects_inherited_guests(self):
        for kind in ('Ec2', 'Ecs'):
            with self.subTest(kind=kind):
                expected = {name for name in EXPECTED if '/' + kind in name}
                base = 'src/test/java/' + DNS + kind + 'PrivateDnsGuestDockerNightlyIntegrationTest.java'
                self.assertEqual(expected, self.select([base]) & EXPECTED)

    def test_dns_implementation_selects_all_guests(self):
        selected = self.select(['src/main/java/io/floci/aws/core/common/dns/EmbeddedDnsServer.java'])
        self.assertEqual(EXPECTED, selected & EXPECTED)

    def test_each_guest_prefetches_python_and_helper_base(self):
        images = (TESTS / 'io/floci/aws/testing/TestImages.java').read_text()
        python_image = images.split('PYTHON_ALPINE = "', 1)[1].split('"', 1)[0]
        for name in NAMES:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                temp = Path(directory)
                docker = temp / 'docker'
                docker.write_text('#!/bin/sh\nprintf "%s\\n" "$3" >> "$PULLS"\n')
                docker.chmod(0o755)
                shard = temp / 'shard.txt'
                shard.write_text(DNS + name + '\n')
                pulls = temp / 'pulls.txt'
                pulls.write_text('')
                log = temp / 'prefetch.log'
                env = dict(os.environ, PATH=str(temp) + ':' + os.environ['PATH'],
                           PULLS=str(pulls), PREFETCH_LOG=str(log))
                subprocess.run(['bash', '.github/ci/prefetch-images.sh', str(shard)], cwd=ROOT,
                               env=env, capture_output=True, text=True, check=True)
                self.assertEqual({python_image, 'alpine:3.21'}, set(pulls.read_text().splitlines()))
                self.assertNotIn('stale prefetch key', log.read_text())


if __name__ == '__main__':
    unittest.main()

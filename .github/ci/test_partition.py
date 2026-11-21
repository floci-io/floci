"""Run with python3 -m unittest discover -s .github/ci -p 'test_partition.py'."""
from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

PATH = Path(__file__).with_name('partition.sh').resolve()


class PartitionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.classes = self.root / 'classes.txt'

    def source(self, name, annotation):
        path = self.root / 'src/test/java' / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(annotation + '\nclass Fixture {}\n')

    def invoke(self, total, index):
        # One slow class, as SLOW_CLASSES weighed the EMR runner before it moved to the nightly checks.
        env = dict(os.environ, PARTITION_SLOW_CLASSES='{"SlowDockerIntegrationTest": 650}')
        return subprocess.run([sys.executable, str(PATH), str(self.classes), str(total), str(index)],
                              cwd=self.root, capture_output=True, text=True, env=env)

    def shards(self, classes, total):
        self.classes.write_text('\n'.join(classes) + '\n')
        shards = []
        for index in range(1, total + 1):
            result = self.invoke(total, index)
            self.assertEqual(0, result.returncode, result.stderr)
            lines = result.stdout.splitlines()
            self.assertEqual(sorted(set(lines)), lines)
            shards.append(lines)
        flattened = [name for shard in shards for name in shard]
        expected = {name.strip() for name in classes if name.strip()}
        self.assertEqual(expected, set(flattened))
        self.assertEqual(len(expected), len(flattened), 'Each class must appear exactly once')
        return shards

    def test_legacy_profile_and_plain_class_assignment_is_preserved(self):
        for name in ('p/A', 'p/B'):
            self.source(name, '@TestProfile(P.class)')
        self.assertEqual([['p/A', 'p/B'], ['p/C', 'p/D']],
                         self.shards(['p/D', 'p/C', 'p/B', 'p/A'], 2))

    def test_equal_profile_groups_use_stable_name_tiebreaking(self):
        for name in ('p/A', 'p/B'):
            self.source(name, '@TestProfile(Alpha.class)')
        for name in ('p/C', 'p/D'):
            self.source(name, '@TestProfile(Beta.class)')
        self.assertEqual([['p/A', 'p/B', 'p/E'], ['p/C', 'p/D', 'p/F']],
                         self.shards(['p/F', 'p/E', 'p/D', 'p/C', 'p/B', 'p/A'], 2))

    def test_profile_augmentation_weight_keeps_plain_classes_on_the_other_shard(self):
        for name in ('p/A', 'p/B'):
            self.source(name, '@TestProfile(P.class)')
        self.assertEqual([['p/A', 'p/B'], ['p/C', 'p/D', 'p/E', 'p/F']],
                         self.shards(['p/F', 'p/E', 'p/D', 'p/C', 'p/B', 'p/A'], 2))

    def test_larger_profile_groups_are_assigned_before_alphabetically_earlier_groups(self):
        self.source('p/A', '@TestProfile(Alpha.class)')
        for name in ('p/B', 'p/C', 'p/D'):
            self.source(name, '@TestProfile(Zeta.class)')
        self.assertEqual([['p/B', 'p/C', 'p/D'], ['p/A']],
                         self.shards(['p/D', 'p/C', 'p/B', 'p/A'], 2))

    def test_plain_classes_fill_shards_by_class_count(self):
        self.assertEqual([['p/A', 'p/D'], ['p/B', 'p/E'], ['p/C']],
                         self.shards(['p/E', 'p/D', 'p/C', 'p/B', 'p/A'], 3))

    def test_input_order_duplicates_and_blank_lines_do_not_change_assignment(self):
        classes = ['p/A', 'p/B', 'p/C', 'p/D', 'p/E']
        self.source('p/A', '@TestProfile(P.class)')
        self.source('p/B', '@TestProfile(P.class)')
        expected = self.shards(classes, 3)
        actual = self.shards(list(reversed(classes)) + classes + ['', '  ', ' p/C '], 3)
        self.assertEqual(expected, actual)

    def test_profiles_remain_atomic_with_complete_disjoint_coverage(self):
        for name in ('p/A', 'p/B', 'p/C'):
            self.source(name, '@TestProfile( Shared.class )')
        classes = ['p/' + name for name in 'ABCDEFGHIJKL']
        shards = self.shards(classes, 4)
        self.assertTrue(any({'p/A', 'p/B', 'p/C'} <= set(shard) for shard in shards))

    def test_source_paths_and_profile_tokens_group_across_packages(self):
        self.source('first/A', '@TestProfile(profiles.Shared.class)')
        self.source('second/B', '@TestProfile( profiles.Shared.class )')
        self.assertEqual([['first/A', 'second/B'], ['third/C']],
                         self.shards(['third/C', 'second/B', 'first/A'], 2))

    def test_a_slow_class_in_a_profile_keeps_other_classes_off_its_shard(self):
        self.source('p/SlowDockerIntegrationTest', '@TestProfile(Docker.class)')
        plain = ['p/C%03d' % n for n in range(40)]
        shards = self.shards(['p/SlowDockerIntegrationTest'] + plain, 2)
        self.assertEqual([['p/SlowDockerIntegrationTest'], plain], shards)

    def test_a_group_holding_a_slow_class_is_placed_before_a_larger_group(self):
        self.source('p/SlowDockerIntegrationTest', '@TestProfile(Docker.class)')
        for name in ('p/A', 'p/B', 'p/C', 'p/D'):
            self.source(name, '@TestProfile(Zeta.class)')
        shards = self.shards(['p/A', 'p/B', 'p/C', 'p/D', 'p/SlowDockerIntegrationTest'], 2)
        self.assertEqual(['p/SlowDockerIntegrationTest'], shards[0])

    def test_a_slow_class_without_a_profile_is_placed_before_the_others(self):
        plain = ['p/A', 'p/B', 'p/C']
        shards = self.shards(plain + ['p/SlowDockerIntegrationTest'], 2)
        self.assertEqual([['p/SlowDockerIntegrationTest'], plain], shards)

    def test_a_slow_class_is_known_by_its_name_whatever_its_package(self):
        plain = ['some/other/C%03d' % n for n in range(40)]
        shards = self.shards(['some/where/SlowDockerIntegrationTest'] + plain, 2)
        self.assertEqual(['some/where/SlowDockerIntegrationTest'], shards[0])

    def test_a_slow_class_is_separate_from_ordinary_members_of_its_profile(self):
        self.source('p/SlowDockerIntegrationTest', '@TestProfile(Docker.class)')
        self.source('p/A', '@TestProfile(Docker.class)')
        self.source('p/B', '@TestProfile(Docker.class)')
        classes = ['p/SlowDockerIntegrationTest', 'p/A', 'p/B']
        self.assertEqual([['p/SlowDockerIntegrationTest'], ['p/A', 'p/B']],
                         self.shards(classes, 2))

    def test_a_slow_class_that_no_longer_exists_is_named_and_changes_nothing(self):
        self.classes.write_text('p/A\np/B\n')
        result = self.invoke(2, 1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('p/A\n', result.stdout)
        self.assertIn('::warning title=partition.sh::stale SLOW_CLASSES key: no test class named '
                      'SlowDockerIntegrationTest', result.stderr)

    def test_invalid_shard_arguments_fail_without_emitting_assignments(self):
        self.classes.write_text('p/A\np/B\n')
        for total, index in ((0, 1), (2, 0), (2, 3), ('invalid', 1), (2, 'invalid')):
            with self.subTest(total=total, index=index):
                result = self.invoke(total, index)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', result.stdout)

    def test_empty_shard_fails_instead_of_running_the_entire_suite(self):
        self.classes.write_text('p/A\n')
        result = self.invoke(2, 2)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)
        self.assertIn('would be empty', result.stderr)

    def test_missing_manifest_fails_without_emitting_assignments(self):
        result = self.invoke(2, 1)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', result.stdout)


if __name__ == '__main__':
    unittest.main()

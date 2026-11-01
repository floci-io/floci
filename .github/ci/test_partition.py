"""Run with python3 -m unittest discover -s .github/ci -p 'test_partition.py'."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

PATH = Path(__file__).with_name('partition.sh')
loader = importlib.machinery.SourceFileLoader('partition', str(PATH))
spec = importlib.util.spec_from_loader(loader.name, loader)
module = importlib.util.module_from_spec(spec)
loader.exec_module(module)


class PartitionTest(unittest.TestCase):
    def test_long_plain_classes_are_distributed_before_short_classes(self):
        classes = ['p/A', 'p/B', 'p/C', 'p/D']
        times = dict(zip(classes, [1, 20, 1, 20]))
        result = module.partition(classes, 2, {}, times)
        self.assertNotEqual(result['p/B'], result['p/D'])
        self.assertEqual([21, 21], [sum(times[c] for c in classes if result[c] == s)
                                  for s in range(2)])

    def test_profiles_remain_atomic_with_long_plain_classes(self):
        classes = ['p/A', 'p/B', 'p/C', 'p/D']
        result = module.partition(classes, 2, {'p/A': 'P', 'p/B': 'P'},
                                  {'p/A': 20, 'p/B': 1, 'p/C': 20})
        self.assertEqual(result['p/A'], result['p/B'])
        self.assertNotEqual(result['p/A'], result['p/C'])

    def test_input_order_duplicates_and_duration_order_do_not_change_assignment(self):
        classes = ['p/A', 'p/B', 'p/C', 'p/D']
        durations = {'p/A': 4, 'p/B': 2, 'p/C': 0}
        expected = module.partition(classes, 2, {}, durations)
        actual = module.partition(list(reversed(classes)) + classes, 2, {},
                                  dict(reversed(list(durations.items()))))
        self.assertEqual(expected, actual)
        self.assertEqual(set(classes), set(actual))

    def test_unrelated_stale_timings_do_not_change_unknown_fallback(self):
        classes = ['p/A', 'p/B', 'p/C']
        self.assertEqual(module.partition(classes, 2, {}, {'p/A': 4}),
                         module.partition(classes, 2, {}, {'p/A': 4, 'old/Removed': 9999}))

    def test_all_unknown_and_zero_durations_keep_every_class(self):
        classes = ['p/A', 'p/B', 'p/C', 'p/D']
        for times in ({'old/Removed': 99}, {c: 0 for c in classes}):
            with self.subTest(times=times):
                result = module.partition(classes, 2, {}, times)
                self.assertEqual(set(classes), set(result))
                self.assertEqual({0, 1}, set(result.values()))

    def test_legacy_assignment_is_preserved(self):
        self.assertEqual({'p/A': 0, 'p/B': 0, 'p/C': 1, 'p/D': 1},
                         module.partition(['p/D', 'p/C', 'p/B', 'p/A'], 2,
                                          {'p/A': 'P', 'p/B': 'P'}))

    def test_invalid_duration_inputs_are_rejected(self):
        invalid = [None, [], {}, {'schemaVersion': 2},
                   {'schemaVersion': 1, 'source': '', 'classSeconds': {'p/A': 1}}]
        for values in ({}, [], {'p/A': True}, {'p/A': -1}, {'p/A': float('nan')},
                       {'p/A': float('inf')}, {'p/A': '1'}, {'p.A': 1}, {'/p/A': 1}):
            invalid.append({'schemaVersion': 1, 'source': 'fixture', 'classSeconds': values})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data.json'
            for value in invalid:
                with self.subTest(value=value):
                    path.write_text(json.dumps(value))
                    with self.assertRaises(ValueError):
                        module.read_durations(path)

    def test_valid_zero_duration_and_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'data.json'
            path.write_text(json.dumps({'schemaVersion': 1, 'source': 'run/42',
                                        'classSeconds': {'p/A': 0, 'p/B': 2.5}}))
            self.assertEqual({'p/A': 0, 'p/B': 2.5}, module.read_durations(path))

    def test_cli_fails_before_output_for_bad_or_missing_data(self):
        with tempfile.TemporaryDirectory() as directory:
            classes = Path(directory) / 'classes.txt'
            classes.write_text('p/A\np/B\n')
            data = Path(directory) / 'bad.json'
            data.write_text('{')
            for extra in ([str(data)], [str(data) + '.missing']):
                result = subprocess.run([sys.executable, str(PATH), str(classes), '2', '1'] + extra,
                                        cwd=directory, capture_output=True, text=True)
                self.assertNotEqual(0, result.returncode)
                self.assertEqual('', result.stdout)

    def test_cli_rejects_invalid_schema_and_numeric_evidence_without_traceback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            classes = root / 'classes.txt'
            classes.write_text('p/A\np/B\n')
            data = root / 'data.json'
            invalid = [(True, 1), (1.0, 1), (1, True), (1, -1),
                       (1, float('nan')), (1, float('inf')), (1, 10 ** 400)]
            for schema, seconds in invalid:
                with self.subTest(schema=schema, seconds=seconds):
                    data.write_text(json.dumps({'schemaVersion': schema, 'source': 'fixture',
                                                'classSeconds': {'p/A': seconds}}))
                    result = subprocess.run([sys.executable, str(PATH), str(classes), '2', '1',
                                             str(data)], cwd=root, capture_output=True, text=True)
                    self.assertNotEqual(0, result.returncode)
                    self.assertEqual('', result.stdout)
                    self.assertIn('partition.sh:', result.stderr)
                    self.assertNotIn('Traceback', result.stderr)

    def test_cli_complete_disjoint_and_profile_atomic(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'src/test/java/p'
            source.mkdir(parents=True)
            for name in ('A', 'B'):
                (source / (name + '.java')).write_text('@TestProfile(P.class) class ' + name + ' {}')
            classes = root / 'classes.txt'
            classes.write_text('p/A\np/B\np/C\np/D\np/E\n')
            data = root / 'data.json'
            data.write_text(json.dumps({'schemaVersion': 1, 'source': 'fixture',
                                        'classSeconds': {'p/C': 30, 'p/E': 5}}))
            shards = [set(subprocess.check_output([sys.executable, str(PATH), str(classes), '2',
                                                   str(i), str(data)], cwd=root, text=True).splitlines())
                      for i in (1, 2)]
            self.assertFalse(shards[0] & shards[1])
            self.assertEqual(set(classes.read_text().splitlines()), shards[0] | shards[1])
            self.assertTrue(any({'p/A', 'p/B'} <= shard for shard in shards))


if __name__ == '__main__':
    unittest.main()

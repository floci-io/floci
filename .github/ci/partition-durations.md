# Measured test durations

The fourth argument to `partition.sh` is an optional JSON file:

```json
{"schemaVersion": 1, "source": "Actions run and artifact identity", "classSeconds": {"io/github/example/ExampleTest": 12.5}}
```

Keys use the same class paths as the compiled class manifest. Values are finite,
nonnegative seconds from completed class reports. The manifest remains the sole
source of test inclusion. Old timing entries cannot introduce tests. Missing
classes use the upper median of measured classes present in the manifest, with
a one-second minimum. If none are measured, the fallback is one second. Zero
reported durations receive a small positive scheduling weight.

Profile groups remain atomic. All groups and individual profile-less classes
are sorted by decreasing estimated cost, then by member names. Legacy mode uses ten class-equivalent augmentation units. Measured mode uses
a separate heuristic ten-second profile allowance, not measured startup time. Shard ties use the shard index.
Without a fourth argument, the existing assignments are unchanged.

Before enabling measured mode in CI, retain the training run, exact source,
artifact hashes and report completeness, then assess a separate completed run.
Cancelled partial reports are not complete timing samples. Estimated balance is
not proof of a reduced hosted wall-clock duration. CI supplies the checked-in timing file. The job deadline, compiled-manifest
comparison and complete/disjoint shard-union checks remain unchanged.

Run the regression suite with:

```sh
python3 -m unittest discover -s .github/ci -p 'test_partition.py'
```

The checked-in dataset comes from successful main run `41343908539`, source
`87936c19b3405ba3e91739773637849145f8110a`. Its four shard artifacts are
`13099650472`, `13099290809`, `13099480213`, and `13098773295`.
They contain 3,202 completed class reports for 3,204 discovered classes; the two
`TestImages` utility classes do not produce JUnit reports and use the fallback.
All reported tests completed without failures or errors (136 skipped cases).

Training selection preceded assessment against separate run `41345922413`.
The latter's 3,206-class manifest remains complete, but its cancelled third shard
has only partial reports. Using only its observed class seconds, the maximum
assigned load changes from 1,077.002 to 921.567 seconds. These are lower bounds
for the missing reports and do not model compilation, augmentation or contention
independently.
This projection does not establish future hosted wall-clock improvement or
resolve the separate native InfluxDB reboot failure.

Refresh timings only from complete successful runs. Retain source identity,
artifact identity, report hashes and exclusions, and assess a separately selected
run before replacing the dataset. Never use timing entries as test discovery.

A second completed holdout, run `41345604630`, executed merge
`575a06dc3ada55c00377e2e984d8756609aaa68c`. Its artifacts
`13099314186`, `13099209122`, `13099837525`, and `13099633923`
contain 3,204 class reports for 3,206 assigned classes, with zero failures or
errors and 137 skipped cases. With the training weights frozen, the maximum
modeled assigned class time changes from 1,011.209 to 871.972 seconds.
All classes remain assigned once and profile groups remain unsplit. This is
an independent completed-run assessment, not execution of the candidate workflow.

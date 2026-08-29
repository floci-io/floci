# [see-something] fidelity: StartBuild/RetryBuild edge cases found in an independent review pass on PR #2707

Found via an independent Fable-model review of the cumulative branch diff, specifically requested because
`CodeBuildService.startBuild`'s environment-merge logic had been patched three times already in this PR's review
cycle (environment-override sparse-merge fix, then a retryBuild regression fix on top of it) — repeated patches to
the same logic are a common place for a new edge case to slip in. Two items from that pass were fixed inline (the
native-image `org.tukaani:xz` dependency and `DockerRetry`'s `SocketTimeoutException`-vs-`InterruptedIOException`
over-exclusion); the rest are real but lower-severity fidelity gaps, filed here rather than triggering a seventh
round of environment-merge patches on an already deeply-iterated PR.

## RetryBuild leaks the current project's environment variables when they weren't in the original build
(medium)

`CodeBuildRunner.buildEnvList` applies `project.getEnvironment()`'s variables first, then overlays
`build.getEnvironment()`'s variables (`applyEnvironmentVariables` only adds/overwrites keys, never removes them).
Since `CodeBuildService.startBuild` always makes `build.getEnvironment()` the fully-resolved, authoritative
environment (either the plain project env when no override was requested, or a complete merge when one was), this
project-first pass is redundant in the normal StartBuild path — but for RetryBuild specifically, it means a
variable added to the project *after* the original build ran (and thus absent from the original build's recorded
environment) still gets included in the retried container, because the project pass adds it and nothing in the
build's variable list removes it.

**Deliberately not fixed inline**: an existing test, `CodeBuildRunnerEnvAssemblyTest#buildLevelVariablesWinOverProjectVariables`,
intentionally constructs a *sparse* `build.environment` (only one overridden variable set) and asserts that
`buildEnvList` still resolves the project's other variables underneath it — i.e. the runner's project+build merge
is a designed fallback for partial build environments, not an accident. Removing it to fix the RetryBuild leak
would need that test's contract revisited too, which is a real design discussion (does anything besides tests ever
hand the runner a genuinely partial build environment?), not a one-line fix.
- Fix candidates: either (a) confirm no real caller ever produces a partial `build.environment` and drop the
  project-first pass + update the test, or (b) make RetryBuild snapshot-and-freeze the original build's *complete*
  variable set at DownloadSource time so leakage can't happen regardless of the runner's fallback merge.

## RetryBuild also replays the current project's inline buildspec, not the original build's

(low-medium) `CodeBuildService.java`'s source-merge branch (`sourceTypeOverride != null || sourceLocationOverride
!= null`) always takes `projectSource.getBuildspec()` from the *current* project. `retryBuild` passes the original
build's source type/location as overrides, which forces this merge branch — so if the project's inline buildspec
changed between the original build and the retry (and no explicit `buildspecOverride` was recorded for the
original), the retry runs the *new* buildspec, not the one that actually ran. Same family of issue as the
environment-variable leak above; not fixed alongside it for the same "don't triple-iterate on startBuild merge
logic without stepping back" reason.

## StartBuild's `sourceVersion` parameter is parsed but never stored or used

(low) Parsed in `CodeBuildJsonHandler.startBuild`, passed through to `CodeBuildService.startBuild`, declared as a
parameter there — never assigned to the `Build` object and never read anywhere. Real CodeBuild records
`build.sourceVersion`/`resolvedSourceVersion` and RetryBuild replays it. Currently a silently-accepted-and-ignored
API field. Either wire it onto `Build` (and thread it through RetryBuild) or drop the parameter — accepting and
silently discarding is the worse of the two options.

## Same-name environment variable overrides produce duplicate entries in the stored/returned Build

(low, wire-fidelity) `environmentVariablesOverride` is appended to the base variable list rather than replacing a
same-named entry, so `BatchGetBuilds`/`GetBuild` can return two entries with the same `name` in
`environment.environmentVariables`. Runtime behavior in the container is correct (last-one-wins via the runner's
`LinkedHashMap`), but real CodeBuild's API contract replaces same-name variables rather than appending a duplicate.
Fix: de-duplicate by `name` when merging, override winning, preserving first-seen order for everything else.

## Two possibly-pre-existing patterns worth a blame-check before fixing

- `CodeBuildRunner.buildEnvList` dereferences `project.getEnvironment().getImage()`-style calls without the null
  guard used a few lines later for `build.getEnvironment()` — likely benign given callers always pass a project
  with an environment, but inconsistent.
- The buildspec parameter-store variable-resolution loop debug-logs and skips an unresolvable SSM parameter,
  while the surrounding class's own javadoc documents that a typed `PARAMETER_STORE` entry failing to resolve
  should fail the build (matching real CodeBuild's provisioning-error behavior). If real, this is the same
  "silent fallback where AWS fails fast" pattern already fixed elsewhere in this PR for `missingParameterStoreParameterFailsAssembly` — worth checking whether that test actually covers this exact code path or a different one.

None of the above are regressions from any of this PR's five prior review-fix rounds (verified: `buildEnvList`'s
project-then-build merge predates this branch's environment-override work, from the same
`0f383d9c7 feat(codebuild): apply StartBuild env overrides and stage secondary sources` commit that introduced the
overrides themselves) — they're fidelity gaps in the original feature work, not fix-introduced regressions.

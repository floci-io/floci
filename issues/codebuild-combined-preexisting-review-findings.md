# [see-something] robustness/security: pre-existing gaps found reviewing files touched by feature/codebuild-combined (PR #2707)

Found via the mandatory local code-review pass (`review-local.ts`, isolated per-file review) required before opening
PR #2707. Every HIGH finding was blame-checked (`git log -S` on the named method/field); all of the ones below
predate this branch by a merged upstream PR (cited per file) and are unrelated to CodeBuild build-execution,
Docker-retry, or Lambda-validation work. The two HIGH findings that genuinely traced to this branch's own new code
were fixed inline, not ledgered here: `RetryingTarCopier`'s swallowed-writer-exception (silent data loss on a
missing/unreadable source file) and `CodeBuildRunner.acquireSecondarySources`'s unvalidated secondary-source
identifier (host path traversal + in-container shell injection).

Filing rather than fixing inline to avoid growing a CodeBuild-execution PR into a general security/robustness
hardening pass across seven unrelated services.

## Shared DNS/TLS/Docker files — already ledgered elsewhere

`TlsConfigSource.java` (private key world-readable permissions, HIGH) and `EmbeddedDnsServer.java` (unbounded
recursion on DNS compression-pointer loops, and forwarder accepts spoofed responses with no TXID/source check, both
HIGH) are shared with `fix/dns-docker-tls-reliability-hardening` (#2704) — verified byte-identical between the two
branches. Both are already fully ledgered at `issues/dns-tls-docker-preexisting-review-findings.md` on that branch;
not duplicated here. `ContainerLifecycleManager.java`'s MEDIUM findings (log-stream leak on stop/remove failure,
swallowed shared-volume ownership-init failure, swallowed network-attach failure) are likewise already covered
there.

## CodePipelineService.java (severity 4-3, several HIGH — pre-existing, branch only changed a constructor-arity
call site)
Confirmed pre-existing: this branch's only change to the file is a 1-line constructor-arity fix (`Build`'s widened
constructor). Findings not blame-checked individually given the file's own diff proves none of them are new:
stop/approval signals mutate a store-loaded copy while runner threads poll a different in-memory instance (stops
and approvals may never take effect); unsynchronized concurrent mutation of a shared `CodePipelineExecution` across
parallel same-runOrder actions; `deletePipeline` doesn't signal in-flight runner threads, which resurrect deleted
executions via their `finally { putExecution(...) }`; `@PostConstruct` resume on a lazy `@ApplicationScoped` bean
may never run; cross-thread flags (`stopRequested`, `abandon`, action status) polled without `volatile`/locks;
superseded executions keep running and overwrite their own terminal status; `pollForJobs` hands the same job to
multiple concurrent pollers with no reservation; `executeNestedPipeline` is fire-and-forget (doesn't gate on child
result); stop during CodeBuild/CodeDeploy actions reports Failed instead of Stopped.

## Ec2ContainerManager.java (3 HIGH — confirmed pre-existing, `feat(ec2): real Docker container execution...` #658
and `fix(ec2): retry Docker port collisions` #2029)
`start()` reports `running` unconditionally even when the readiness poll/bridge-IP wait fails; `terminate()` and
the launch worker's `failLaunch()` can double-release the same SSH host port; `reboot()` never re-resolves the
Docker-reassigned bridge IP, leaving stale IMDS registration that can leak the rebooted instance's identity to a
different container reusing the old IP.

## EksClusterManager.java (1 HIGH — confirmed pre-existing, `feat(eks): make real-mode clusters reachable...` #1167)
`writeWebhookKubeconfig`/`writeRegistriesYaml` build paths from an unvalidated cluster name; `Path.normalize()`
resolves `..` segments, so a crafted cluster name can escape `dataPath` and write attacker-influenced content
outside the intended directory tree.

## LambdaLayerService.java (2 HIGH — confirmed pre-existing; branch's diff here is 4 lines)
Path traversal via an unvalidated layer name (the sanitizing regex permits `.`, so `..` survives); TOCTOU race on
version allocation lets two concurrent `PublishLayerVersion` calls silently overwrite each other's stored version
record.

## LambdaService.java (3 HIGH — confirmed pre-existing despite this branch's own substantial validation-adding
diff in the same file)
RevisionId optimistic-lock check in `updateFunctionConfiguration` runs after mutations are already applied to the
live store object (`fix(lambda): add missing FunctionConfiguration fields...` #546); `updateFunctionCode` can
destroy the previous on-disk deployment package before validation completes, leaving the function broken despite
the API reporting failure (`feat(lambda): code-signing endpoints and account settings` #2646); `onS3ObjectUpdated`
has no `$LATEST` filter, so an S3 object update also silently re-extracts and re-saves every published version,
violating version immutability (`feat(lambda): implement reactive S3-to-Lambda sync...` #509).

## ContainerLauncher.java (1 HIGH — confirmed pre-existing, `fix(lambda): reconcile code volumes against real
Docker state...` #2208)
`volumesInFlight`'s increment (`computeIfAbsent` + `incrementAndGet`, two non-atomic steps) races with
`releaseCodeVolumeReference`'s `computeIfPresent` removal; the sweeper can remove a volume a concurrent launch
still believes is in flight.

## MwaaEnvironmentManager.java (1 HIGH — confirmed pre-existing, `feat(mwaa): add Amazon MWAA emulation...` #2086)
`startEnvironment()` never cleans up the already-running Postgres container/volume if the later Airflow-container
start or readiness wait fails; only the Airflow side has cleanup.

## ContainerLogStreamer.java / DockerClientProducer.java (1 HIGH each — confirmed pre-existing)
`attachForAccount` (`feat(rds): model DB proxies...` #1813, unrelated feature reusing shared plumbing) returns
`null` instead of a Closeable on attach failure — a documented-contract violation any caller doing
try-with-resources would NPE on. `DockerClientProducer`'s control-plane client never applies `clientConfig`'s TLS
settings to the Apache transport (`firt commit`, i.e. original project scaffolding) — `DOCKER_TLS_VERIFY`/
`DOCKER_CERT_PATH` are silently ignored for `tcp://` daemon connections.

## MEDIUM/LOW findings not itemized above
Every MEDIUM/LOW finding in `EmulatorConfig.java`, `CodeBuildJsonHandler.java`, `CodeBuildService.java`,
`RetryingDockerHttpClient.java`, and the remaining MEDIUM/LOW items in the files above not already covered by
#2704's ledger are real robustness gaps but out of this PR's scope — a dedicated hardening pass per service would
be more effective than folding them into a build-execution PR. Not individually re-derived here; see the full
`review-local.ts` output this ledger summarizes (available in the PR discussion/CI artifacts if a future pass wants
the complete per-line detail).

None of the above block PR #2707 — they predate its commits and are unrelated to CodeBuild execution, Docker
retry, or Lambda request-validation work.

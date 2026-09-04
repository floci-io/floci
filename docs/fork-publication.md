# Reviewed fork image

This temporary fork is not an official upstream release. Its publication workflow is restricted
to the explicit fork repository and feature branch, and checks out a reviewed, literal source SHA.
The source SHA includes the build recipe, dependency lock, and runtime verification scripts. A
separate workflow commit records that SHA after review. No upstream merge or release is implied.

## Publication gates

1. Independent security and compatibility review of the complete ECS credential change.
2. Focused negative authentication regressions and existing compatibility tests.
3. Build one OCI artifact containing linux/amd64 and linux/arm64 plus SBOM and provenance.
4. Run both platform startup smokes and the isolated ECS SDK lifecycle contract before pushing
   that same artifact. No published host ports or shared application stack are used.
5. Read back the registry digest, both platforms, source-bound provenance, and SBOM. A separate
   unauthenticated job must pull the immutable digest for both platforms. A private package or
   failed readback is incomplete publication, even if the push succeeded.

## Build inputs and trust

`docker/Dockerfile.fork` pins JDK and JRE index digests, Maven distribution checksums, gosu
checksums, a dated signed Ubuntu package snapshot, and a fully hashed Python dependency lock.
The workflow pins actions by commit, BuildKit/QEMU/SBOM-generator images by digest, and Docker
and Buildx by version. The hosted runner image version is recorded, not claimed immutable.

Maven dependencies retain the upstream POM's versions and repository trust. Strict repository
checksum validation is enabled; hashes of resolved JAR/POM files are included at
`/usr/share/floci-build/maven-inputs.sha256`. The installed OS package versions and Python lock
are recorded in the same directory. This is auditable source and dependency provenance, not a
claim of a hermetic build or byte-identical reproducibility across rebuilds.

Only the documented public emulator `test` credentials occur as runtime defaults. Real cloud
credentials, Docker authentication files, local environment files, and private review evidence
must never be build inputs or published metadata. The narrow Docker context allowlist excludes
such files. Registry credentials are used only after the pre-publication tests.

## Runtime boundaries

See [ECS task-role credentials](services/ecs.md#ecs-task-role-credentials) for trusted-network,
bearer-capability, unsupported trust-policy-condition, and image-credential limitations. The
runtime contract must observe its assertions; descriptive or hardcoded lifecycle JSON is not
acceptance evidence. Unit tests alone are not SDK or Docker-network proof.

Run the isolated contract against each local platform image:

```sh
bash tools/fork-ecs-smoke.sh IMAGE --platform amd64
bash tools/fork-ecs-smoke.sh IMAGE --platform arm64
```

For a custom daemon on the same Linux host, pass `--docker-socket /absolute/daemon/socket`.
The CI workflow resolves this from its selected Docker context. Docker Desktop keeps the default
`/var/run/docker.sock` daemon-host path, not the macOS client proxy path. Before creating tasks,
the contract verifies that the mounted socket and controller report the same Docker daemon ID.

The test uses a clean digest-pinned probe image, three real task containers, the default boto3
container-role provider, an allowed object read and an explicit list-buckets denial. It asserts
stable-path rotation, bounded old/new-key overlap before their advertised expirations,
task-scoped revocation of both generations while another task remains allowed,
expired-key denial, default-SDK recovery after a running task is idle past its credential TTL,
and reuse of the same STS/S3 client instances across another 125-second idle interval. It also
checks revocation of the latest renewed credential on task stop and exact Docker cleanup. TTL is
120 seconds and the refresh window is 60 seconds; allow about six minutes per platform. The task
fixture holds for 900 seconds to cover emulated startup and both idle intervals, not to extend
credential validity. A cached-client failure is not retried with a new client. Output contains observed role
identities, one-way credential fingerprints, image manifest identity, and authentication error
codes, never credential values or bearer paths. The runner emits success only after cleanup.
The service-authorization checks use exact service ARNs for `ecs:DescribeServices` and
`ecs:UpdateService`, rather than wildcard permissions. They exercise name and ARN inputs,
multiple requested services, and denied unrelated targets before the timed credential captures.
Service lookup must stay within the requested cluster and region; a missing authorized service
must never resolve to a same-named service elsewhere.
Redacted control timing and final container state are emitted to stderr even on failure. Preserve
stderr separately from the success JSON when collecting CI artifacts. The optional
`--diagnostic-idle-seconds 61` adds a bounded idle interval before task A renewal without skipping
any assertion, and reproduces the old idle-task failure on the previously published image.
The three-container `RunTask` control call permits a 60-second read timeout for emulated cold
starts, with exactly one attempt. Credential assertions, TTL, and all other API deadlines remain
unchanged; a timeout is still a failed contract, never a successful or retried task launch.

The existing S3 directory-permission test is skipped when run as root because root bypasses the
permission under test. This storage-permission skip does not skip an ECS/token regression. It
must remain visible in the recorded test report.

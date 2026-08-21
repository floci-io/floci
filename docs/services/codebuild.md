# CodeBuild

Floci implements the CodeBuild API — stored-state management plus real build execution inside Docker containers.

**Protocol:** JSON 1.1 — `POST /` with `X-Amz-Target: CodeBuild_20161006.<Action>`

**ARN formats:**

- `arn:aws:codebuild:<region>:<account>:project/<name>`
- `arn:aws:codebuild:<region>:<account>:report-group/<name>`
- `arn:aws:codebuild:<region>:<account>:token/<type>-<uuid>`
- `arn:aws:codebuild:<region>:<account>:build/<project>:<uuid>`

## Supported Operations (20 total)

### Projects

| Operation | Notes |
|---|---|
| `CreateProject` | Stores project config; requires `name`, `source.type`, `artifacts.type`, `environment`, `serviceRole` |
| `UpdateProject` | Partial update — only supplied fields are modified |
| `DeleteProject` | Removes project by name |
| `BatchGetProjects` | Returns found projects and a `projectsNotFound` list |
| `ListProjects` | Returns all project names in the region |

### Build Execution

| Operation | Notes |
|---|---|
| `StartBuild` | Launches a real Docker container using the project's image; runs buildspec phases (`INSTALL`, `PRE_BUILD`, `BUILD`, `POST_BUILD`); returns immediately with `IN_PROGRESS` status |
| `BatchGetBuilds` | Returns current build state; poll until `buildComplete` is `true` |
| `ListBuilds` | Returns all build IDs in the region, most recent first |
| `ListBuildsForProject` | Returns build IDs for a specific project |
| `StopBuild` | Signals a running build to stop; build transitions to `STOPPED` |
| `RetryBuild` | Starts a new build using the same config as a completed build; returns a new build record |

### Report Groups

| Operation | Notes |
|---|---|
| `CreateReportGroup` | Stores report group config |
| `UpdateReportGroup` | Partial update by ARN |
| `DeleteReportGroup` | Removes report group by ARN |
| `BatchGetReportGroups` | Returns found report groups and a `reportGroupsNotFound` list |
| `ListReportGroups` | Returns all report group ARNs in the region |

### Source Credentials

| Operation | Notes |
|---|---|
| `ImportSourceCredentials` | Stores server type and auth type; deduplicated by `serverType+authType`; token is accepted but not returned |
| `ListSourceCredentials` | Returns stored credential metadata (no tokens) |
| `DeleteSourceCredentials` | Removes source credentials by ARN |

### Images

| Operation | Notes |
|---|---|
| `ListCuratedEnvironmentImages` | Returns the standard CodeBuild curated image list for the host architecture |

Curated `aws/codebuild/*` image names are **resolved to pullable registries** so a project that
references a curated image runs without the caller re-tagging anything:

- **Amazon Linux** curated images map directly to their `public.ecr.aws` mirrors, and floci picks the
  newest AL2023 curated image for the host architecture.
- The **Ubuntu `standard` family** is not published to a public registry by AWS, so it is substituted.
  By default floci uses the newest public Amazon Linux standard image for the host architecture; set
  `FLOCI_SERVICES_CODEBUILD_CURATED_IMAGE_SUBSTITUTE` to pin a specific substitute image.

## Build Execution Model

Each `StartBuild` call:

1. Pulls the project's Docker image (e.g. `public.ecr.aws/docker/library/alpine:latest`)
2. Starts a container with the working directories pre-created
3. Injects source files into the container via `docker cp` (`NO_SOURCE` builds skip this step)
4. Executes buildspec phases sequentially inside the container via `docker exec`
5. Streams phase output to CloudWatch Logs under `/aws/codebuild/<project>`
6. Extracts artifact files from the container via `docker cp` and uploads them to S3 if `artifacts.type=S3`
7. Marks the build complete with `SUCCEEDED`, `FAILED`, or `STOPPED`

Source injection and artifact extraction both use the Docker API's archive copy endpoints — no bind mounts are required. This works correctly when Floci itself runs inside a Docker container (Docker-in-Docker).

### File-mode and symlink fidelity

The source and artifact archives carry **unix file modes and symlinks** through the round-trip, so a
checked-in executable stays executable inside the build and `node_modules/.bin/*`-style symlinks arrive
as symlinks rather than broken copies. Source `.zip` archives that store symlinks (e.g. produced by
`zip -y`) are honored. Symlink targets are constrained to the workspace — a symlink that would escape
the destination directory is dropped rather than followed. On a filesystem that cannot create symlinks,
floci falls back to writing the link target as a regular file so the build still proceeds.

### Image entrypoint and resilience

- The project image's own **entrypoint is overridden** so an image that would otherwise exit immediately
  (its entrypoint is not a long-running process) cannot kill the build container before the buildspec runs.
- Transient Docker daemon I/O errors are retried with capped backoff, and workspace tars are streamed
  through disk so a large source tree does not have to fit in memory; a build that hits a fatal streaming
  error is failed rather than left hanging.

### Concurrency bounds

Every build stages its whole workspace on the single emulator container's filesystem and streams its
source tar over the shared Docker socket, so a large fan-out (e.g. LZA bootstrapping ~15 targets at
once) can exhaust disk or make the daemon drop connections mid-write. Floci bounds this with two
defaults you can override:

- `FLOCI_SERVICES_CODEBUILD_MAX_CONCURRENT_BUILDS` — max builds staging a workspace at once.
- `FLOCI_SERVICES_CODEBUILD_MAX_CONCURRENT_SOURCE_COPIES` — max builds streaming their source tar at once.

Set either to a positive value to override the bounded default, or a non-positive value to run unbounded
on a well-resourced host.

### Source and environment overrides

`StartBuild` honors the AWS override fields, so a CodePipeline CodeBuild action (or a manual caller) can
retarget a build without editing the project: `sourceTypeOverride` / `sourceLocationOverride`,
`secondarySourcesOverride`, `buildspecOverride`, `imageOverride`, and `environmentVariablesOverride`.
[CodePipeline](codepipeline.md) uses these overrides to hand pipeline input artifacts to a
CodeBuild action as its primary and secondary sources; that wiring ships with the CodePipeline
work rather than here.

## Buildspec Support

Floci parses the `buildspec.yml` embedded in the project or provided via `buildspecOverride`. Supported fields:

- `phases` — `install`, `pre_build`, `build`, `post_build` command lists
- `artifacts.files` — list of file patterns to collect; supports `**/*` glob, specific filenames, and path patterns
- `artifacts.base-directory` — base directory for artifact collection (default: `$CODEBUILD_SRC_DIR`)

### Shell execution model

Floci runs the buildspec the way the real CodeBuild agent does, which several real buildspecs depend on:

- **One shell session across all phases.** Environment variables exported in `install` are visible in
  `build`; a `cd` in one command carries into the next. State (exported vars and working directory) is
  snapshotted after each command and restored before the next, so it persists across commands *and*
  across phase boundaries.
- **Per-command shell isolation.** Each command entry runs in its own child shell, so a `set -e` /
  `set -o pipefail` change or a non-zero exit in one command does not silently leak shell options into
  the next entry — matching the CodeBuild agent's per-command semantics.
- **bash preferred, `sh` fallback.** Floci probes for `bash` in the image and uses it when present
  (so `bash`-only buildspec syntax works); images without `bash` fall back to POSIX `sh`.

### Build environment variables

Floci sets the CodeBuild environment variables buildspecs rely on, including:

- `CODEBUILD_SRC_DIR` — primary source directory.
- `CODEBUILD_SRC_DIR_<identifier>` — one per **secondary source**, keyed by the source `sourceIdentifier`.
- `CODEBUILD_BUILD_SUCCEEDING` — `1` while the build is still passing, `0` after a phase command fails,
  so a `post_build` step can branch on whether the build is succeeding (the standard CodeBuild contract).

Project/build environment variables of type `PLAINTEXT` and `PARAMETER_STORE` are both honored —
`PARAMETER_STORE` values are resolved from SSM and injected into the build environment.

## Artifact Upload

When `artifacts.type=S3`, collected files are uploaded to the configured S3 bucket. The bucket must exist (created via `CreateBucket`). File paths in S3 match the relative path from the artifact base directory.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODEBUILD_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CODEBUILD_CURATED_IMAGE_SUBSTITUTE` | newest AL standard image | Image substituted for the Ubuntu `standard` curated family (which AWS does not publish publicly) |
| `FLOCI_SERVICES_CODEBUILD_MAX_CONCURRENT_BUILDS` | bounded default | Max builds staging a workspace on disk at once; non-positive = unbounded |
| `FLOCI_SERVICES_CODEBUILD_MAX_CONCURRENT_SOURCE_COPIES` | bounded default | Max builds streaming their source tar at once; non-positive = unbounded |
| `FLOCI_SERVICES_CODEBUILD_DOCKER_NETWORK` | unset | Docker network to attach build containers to |

## CLI Examples

```bash
# Create a project with S3 artifacts
aws --endpoint-url http://localhost:4566 codebuild create-project \
  --name my-project \
  --source type=NO_SOURCE \
  --artifacts type=S3,location=my-bucket \
  --environment type=LINUX_CONTAINER,image=public.ecr.aws/docker/library/alpine:latest,computeType=BUILD_GENERAL1_SMALL \
  --service-role arn:aws:iam::000000000000:role/codebuild-role

# Start a build with inline buildspec
aws --endpoint-url http://localhost:4566 codebuild start-build \
  --project-name my-project \
  --buildspec-override 'version: 0.2
phases:
  build:
    commands:
      - echo hello > output.txt
artifacts:
  files:
    - output.txt'

# Poll until complete
aws --endpoint-url http://localhost:4566 codebuild batch-get-builds --ids <build-id>

# List all builds
aws --endpoint-url http://localhost:4566 codebuild list-builds

# List curated images
aws --endpoint-url http://localhost:4566 codebuild list-curated-environment-images
```

# CodeBuild

Floci implements the CodeBuild API — stored-state management plus real build execution inside Docker containers.

**Protocol:** JSON 1.1 — `POST /` with `X-Amz-Target: CodeBuild_20161006.<Action>`

**ARN formats:**

- `arn:aws:codebuild:<region>:<account>:project/<name>`
- `arn:aws:codebuild:<region>:<account>:report-group/<name>`
- `arn:aws:codebuild:<region>:<account>:token/<type>-<uuid>`
- `arn:aws:codebuild:<region>:<account>:build/<project>:<uuid>`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateProject` | Stores project config; requires `name`, `source.type`, `artifacts.type`, `environment`, `serviceRole` |
| `UpdateProject` | Partial update — only supplied fields are modified |
| `DeleteProject` | Removes project by name |
| `BatchGetProjects` | Returns found projects and a `projectsNotFound` list |
| `ListProjects` | Returns all project names in the region |
| `CreateReportGroup` | Stores report group config |
| `UpdateReportGroup` | Partial update by ARN |
| `DeleteReportGroup` | Removes report group by ARN |
| `BatchGetReportGroups` | Returns found report groups and a `reportGroupsNotFound` list |
| `ListReportGroups` | Returns all report group ARNs in the region |
| `ImportSourceCredentials` | Stores server type and auth type; deduplicated by `serverType+authType`; token is accepted but not returned |
| `ListSourceCredentials` | Returns stored credential metadata (no tokens) |
| `DeleteSourceCredentials` | Removes source credentials by ARN |
| `ListCuratedEnvironmentImages` | Returns a static list of standard CodeBuild images for AL2 and Ubuntu |
| `StartBuild` | Launches a real Docker container using the project's image; runs buildspec phases (`INSTALL`, `PRE_BUILD`, `BUILD`, `POST_BUILD`); returns immediately with `IN_PROGRESS` status |
| `BatchGetBuilds` | Returns current build state; poll until `buildComplete` is `true` |
| `ListBuilds` | Returns all build IDs in the region, most recent first |
| `ListBuildsForProject` | Returns build IDs for a specific project |
| `StopBuild` | Signals a running build to stop; build transitions to `STOPPED` |
| `RetryBuild` | Starts a new build using the same config as a completed build; returns a new build record |
<!-- floci:actions:end -->
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

## Buildspec Support

Floci parses the `buildspec.yml` embedded in the project or provided via `buildspecOverride`. Supported fields:

- `phases` — `install`, `pre_build`, `build`, `post_build` command lists
- `artifacts.files` — list of file patterns to collect; supports `**/*` glob, specific filenames, and path patterns
- `artifacts.base-directory` — base directory for artifact collection (default: `$CODEBUILD_SRC_DIR`)

## Artifact Upload

When `artifacts.type=S3`, collected files are uploaded to the configured S3 bucket. The bucket must exist (created via `CreateBucket`). File paths in S3 match the relative path from the artifact base directory.

## Configuration

```yaml
floci:
  services:
    codebuild:
      enabled: true   # default
```

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

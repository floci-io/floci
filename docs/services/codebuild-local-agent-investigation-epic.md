# Investigation epic: AWS CodeBuild local agent backend

## Outcome

Determine whether Floci should use AWS's official CodeBuild local agent as its preferred build
execution backend.

The investigation must produce a tested architectural decision, not merely prove that the agent
container starts. A successful result demonstrates that the agent improves Floci's observable
CodeBuild compatibility while Floci continues to provide the AWS API, stored state, service
integrations, and lifecycle around each build.

## Decision question

Should Floci delegate buildspec interpretation and build-phase execution to
`public.ecr.aws/codebuild/local-builds`, while retaining the existing native runner as a fallback?

The possible decisions are:

1. adopt the local agent as the preferred backend;
2. offer it as an opt-in backend for selected workloads;
3. use it only as a compatibility oracle in tests; or
4. reject it and continue improving the native runner.

### Build-environment decision

For the ARM prototype and LZA validation, use
`public.ecr.aws/codebuild/amazonlinux2-aarch64-standard:4.0`. This is a product decision rather
than an investigation variable. Record its digest for each validation run so a mutable registry
tag cannot make results irreproducible. Treat it as Floci's pullable local substitute, not as a
claim that the same identifier is a currently supported hosted-CodeBuild curated image.

## Why investigate this

Floci currently owns both halves of local CodeBuild:

- the AWS-compatible control plane, including projects, builds, persistence, status, logs,
  artifacts, cancellation, retry, and CodePipeline integration; and
- the execution engine that parses buildspecs, prepares containers, runs phases, and interprets
  phase results.

AWS publishes a local CodeBuild agent for x86_64 and ARM. Its documented launcher gives that
agent a build-environment image, source directory, artifact directory, buildspec, environment
variables, and access to the container-runtime socket. The agent then launches the separate build
environment and simulates CodeBuild phase execution locally.

Delegating the execution half could improve compatibility and reduce the amount of CodeBuild
behavior Floci must reproduce. It does **not** replace Floci's CodeBuild service and does **not**
provide a pullable version of every AWS-managed build image.

## Product hypothesis

An AWS-agent backend will be a better Floci product if it:

- produces results closer to observable AWS CodeBuild behavior;
- reduces custom buildspec and phase-orchestration code;
- integrates without weakening the existing AWS API or persistence contract;
- supports the workloads already demonstrated by Floci, especially LZA; and
- can be versioned, tested, diagnosed, and upgraded predictably.

This is a hypothesis. AWS describes the agent as a way to simulate and troubleshoot builds
locally, not as a stable embedding API or a complete replacement for the hosted service.

## Architectural boundary

The investigation should introduce a backend seam rather than coupling `CodeBuildService`
directly to the agent:

```text
AWS SDK / CLI / CodePipeline
             |
      CodeBuildService
             |
  CodeBuildExecutionBackend
        /             \
NativeFlociBackend  AwsLocalAgentBackend
```

Floci remains responsible for:

- AWS JSON 1.1 request and response compatibility;
- project, build, and report-group persistence;
- account and region isolation;
- IAM, SSM, Secrets Manager, and environment-variable resolution;
- source and secondary-source acquisition;
- build status transitions, phase metadata, timeout, stop, and retry;
- CloudWatch Logs and EventBridge integration;
- artifact publication to S3;
- CodePipeline action callbacks; and
- behavior across a Floci restart.

The candidate agent backend is responsible only for the locally observable execution contract:

- buildspec parsing;
- phase ordering and command execution;
- runtime setup performed by the selected build image;
- command exit and phase-result semantics; and
- local report and artifact production exposed by the agent.

## Important image distinction

The local agent and the build environment are separate images:

```text
public.ecr.aws/codebuild/local-builds:aarch64   # orchestration agent
public.ecr.aws/codebuild/amazonlinux2-aarch64-standard:4.0  # build environment
```

The agent is therefore not a substitute for `aws/codebuild/standard:7.0`. Floci must still map,
build, or otherwise provide a compatible environment image. The backend must record both the
requested image and the resolved executable image so this distinction is visible to users.

## SWOT

### Strengths

- Uses an execution engine distributed and maintained by AWS.
- Supports both x86_64 and ARM local execution.
- May track CodeBuild buildspec and phase behavior more closely than Floci's custom runner.
- Could retire or simplify custom parsing, shell orchestration, report handling, and phase-state
  translation.
- Gives Floci a credible compatibility story grounded in an official AWS tool.

### Weaknesses

- The container is opaque and its environment-variable interface is not a documented stable API.
- It simulates CodeBuild; it is not the hosted CodeBuild service and cannot define AWS behavior by
  itself.
- It still requires a separate, architecture-compatible build-environment image.
- Floci must translate agent-local output back into AWS build, phase, log, report, and artifact
  models.
- Failures inside the agent may be harder to diagnose or patch than failures in Floci-owned code.

### Opportunities

- Make the agent the preferred backend after compatibility is demonstrated.
- Offer selectable `native` and `aws-local-agent` backends during migration.
- Use differential tests to discover and close native-runner compatibility gaps.
- Use the agent as a permanent conformance oracle even if it is not adopted for production use.
- Present a differentiated product capability: a complete local AWS control plane around AWS's
  official local CodeBuild execution agent.

### Threats

- AWS may change tags, digests, inputs, output layout, or behavior without an embedding contract.
- A mutable agent tag could silently change Floci behavior between releases.
- License and redistribution terms may prevent bundling or impose documentation obligations.
- Agent limitations may force two subtly different feature sets across execution backends.
- Users may assume the AWS agent guarantees perfect hosted-CodeBuild parity; documentation must
  state the tested boundary precisely.

## Investigation workstreams

### 1. Distribution, lifecycle, and support contract

- Record supported architectures, tags, published digests, update notifications, and image size.
- Review the image and repository licenses for execution, redistribution, and documentation
  requirements.
- Determine whether Floci should pull by release tag, pin by digest, or require a user-provided
  image.
- Establish how an agent upgrade is tested and intentionally promoted.
- Confirm behavior when the image is absent or cannot be pulled.

### 2. Execution interface

- Document every launcher input used by Floci: image, source, secondary sources, artifacts,
  reports, buildspec override, environment file, credentials, profile, source mounting, and
  privileged mode.
- Capture the agent's output files, exit codes, logs, phase markers, and failure messages.
- Determine which inputs can be passed without shell interpolation or temporary plaintext secret
  files.
- Verify cancellation and timeout behavior at every lifecycle point.
- Define a versioned adapter inside Floci rather than scattering agent-specific variables through
  `CodeBuildRunner`.

### 3. AWS API translation

- Map agent execution states to CodeBuild `BuildStatus`, `BuildPhase`, phase contexts, timestamps,
  and `buildComplete`.
- Preserve current synchronous `StartBuild` response and asynchronous execution behavior.
- Stream or import logs into the existing CloudWatch Logs model with deterministic ordering.
- Translate local artifacts and reports into the existing S3 and report-group implementations.
- Preserve `StopBuild`, `RetryBuild`, batch lookup, list ordering, and CodePipeline polling.
- Ensure an unsupported agent capability fails explicitly rather than producing a false-green
  build.

### 4. Source, environment, and endpoint fidelity

- Test primary and secondary sources, `NO_SOURCE`, S3, CodePipeline artifacts, source overrides,
  and buildspec overrides.
- Test plaintext, Parameter Store, and Secrets Manager environment variables without leaking
  resolved values.
- Verify Floci's account, region, endpoint, DNS-spoofing, and TLS trust configuration inside the
  agent and build environment.
- Preserve Unix modes, symlinks, archive boundaries, and artifact path rules.
- Exercise custom images, curated-image mappings, entrypoints, privileged mode, and both host
  architectures.

### 5. Differential compatibility suite

Create fixtures that run through both `NativeFlociBackend` and `AwsLocalAgentBackend` and compare
observable results:

| Area | Required cases |
|---|---|
| Buildspec | missing file, YAML errors, phase ordering, finally blocks, command failure |
| Shell | quoting, multiline commands, working directory, exported variables, exit codes |
| Environment | project variables, overrides, Parameter Store, Secrets Manager, precedence |
| Sources | primary, secondary, S3, CodePipeline, overrides, executable files, symlinks |
| Artifacts | include/exclude patterns, base directory, names, failure, empty result |
| Reports | discovery, supported formats, malformed reports, report-group publication |
| Lifecycle | start, success, failure, timeout, stop, retry, concurrent builds, restart |
| Integration | CloudWatch Logs, EventBridge, S3, CodePipeline, LZA installer and core pipeline |

Differences must be classified as:

- the agent is closer to documented or observed AWS behavior;
- the native backend is closer;
- both are compatible despite an internal difference;
- AWS behavior is unknown and needs an external compatibility probe; or
- the local agent intentionally does not emulate the hosted feature.

### 6. LZA vertical slice

Use a bounded LZA slice before attempting a complete installation:

1. run the installer project's build through the agent backend;
2. run one Toolkit project action with the same configuration archive;
3. verify CloudFormation, S3 artifacts, logs, and CodePipeline action state;
4. run the complete installer and core pipeline;
5. restart Floci and verify persisted CodeBuild and CodePipeline state; and
6. repeat against the supported LZA compatibility versions.

The LZA run is an integration gate, not the only compatibility evidence.

## Prototype plan

### Phase 0: black-box characterization

Run the published launcher manually with small deterministic buildspec fixtures. Record the exact
container topology, inputs, outputs, logs, artifacts, reports, exit behavior, and architecture
selection. Do not change Floci yet.

### Phase 1: backend seam

Extract the existing execution behavior behind `CodeBuildExecutionBackend` without changing its
observable behavior. Keep the native backend as the default and run the existing CodeBuild suite
against it.

### Phase 2: minimum agent adapter

Implement one `NO_SOURCE` build with inline commands, environment variables, logs, success, and
failure. Pin the agent image by digest. Do not advertise general support yet.

### Phase 3: service integration

Add sources, artifacts, reports, cancellation, timeout, retry, endpoint/TLS integration, and
restart behavior. Run the differential matrix after each capability is added.

### Phase 4: LZA validation


# Floci service/API parity TODO inventory

This is the consolidated backlog from the service/API investigations performed during the
current Floci compatibility work. It turns the existing service guides, parity epics, issue
write-ups, and completed branch work into one actionable queue.

This is a planning document. It does not claim that an AWS operation is unsupported merely
because it is not listed here: the service guide and SDK compatibility tests remain the source
of truth for an individual operation. Every item below names the evidence that caused it to be
included and the next verification or implementation step.

## Priority and status

| Priority | Meaning |
| --- | --- |
| P0 | Blocks a demonstrated LZA path, can cause false-green behavior, or affects account/region correctness. |
| P1 | Important public API parity or lifecycle gap with a clear compatibility consumer. |
| P2 | Deliberate capability limit or lower-frequency API surface; schedule after P0/P1. |

`Open` means work remains. `Implemented — verify` means the code is on a feature branch but
needs rollup, compatibility evidence, or release documentation before it can be called done.

## P0: correctness and product decisions

| ID | Service / area | Status | Evidence / gap | Next step |
| --- | --- | --- | --- | --- |
| PAR-001 | Account and region scoping across services | Open | [issues/0009](../issues/0009-epic-account-region-scoping-audit.md) records that the earlier CFN sweep did not cover the remaining service tree or the region-ambient variant. Its static pre-filter identifies 13 async/ambient-account candidates: Amazon MQ, AppSync, Backup, CloudMap, CloudTrail, CodeDeploy, EKS, Floci UI, Kinesis Analytics, MSK, MWAA, OpenSearch, and SQS. | Re-run `sh scripts/static-checks/find-ambient-account-region-candidates.sh`, then perform bounded, evidence-backed service batches. Record each finding in a numbered issue and exclude only the confirmed global-resource exceptions documented by the epic. |
| PAR-002 | LZA CloudFormation replay/idempotency | Implemented — verify | Branch `feature/lza-cloudformation-idempotency` commit `13687fee` adds status filtering, per-resource checkpoints, security-group/custom-resource idempotency, VPC update handling, and a governed-pipeline integration test. | Roll the branch into integration and run the net-new LZA matrix (including the supported 1.14/1.15/1.16 compatibility targets). Preserve exact stack events and restart behavior as the acceptance record. |
| PAR-003 | CodeBuild execution backend | Open investigation | [CodeBuild local-agent epic](services/codebuild-local-agent-investigation-epic.md) separates the AWS-compatible control plane from the execution backend. The published local agent is not the same image as `aws/codebuild/standard:7.0`; image mapping, output translation, cancellation, secrets, artifacts, and restart behavior remain decisions. | Characterize the published agent with deterministic fixtures, pin image digests, define a versioned backend seam, and run differential native-vs-agent tests before selecting preferred, opt-in, oracle-only, or rejected adoption. |
| PAR-004 | CodePipeline V2 | Open follow-up | [CodePipeline V2 epic](services/codepipeline-v2-epic.md) documents that current support is partial and that LZA currently exercises V1. Trigger execution, validation, queued/superseded/parallel isolation, condition providers, artifact lineage, retry/rollback, events, and CloudFormation round-trips remain. | Implement in slices beginning with V2 validation and trigger fixtures; require AWS SDK integration tests and preserve the existing V1/LZA suite as a regression gate. |
| PAR-005 | Local VPC network data plane | Open investigation | [Network data-plane epic](services/network-data-plane-investigation-epic.md) defines the gap between control-plane records and observable local traffic. Route, security-group, Network Firewall, DNS, endpoint, and logging behavior are not implied by the current API models. | Build a bounded privileged prototype behind a reconciler interface, then decide whether to adopt Podman, an appliance backend, an opt-in experiment, or control-plane-only behavior. Do not make normal API use require host privileges before the decision is proven. |

## P1: public API and lifecycle gaps

| ID | Service / area | Status | Evidence / gap | Next step |
| --- | --- | --- | --- | --- |
| PAR-101 | CloudFormation | Open | `docs/services/cloudformation.md` marks `ValidateTemplate`, stack-policy operations, some intrinsic resolution, and update/delete behaviors as stubs or unimplemented. Unsupported resource types intentionally receive stub physical IDs, which can hide missing service provisioners. | Add AWS SDK contract tests for each advertised stub/error; make unsupported resource handling explicit in the service guide and add exact `Ref`/`Fn::GetAtt` assertions for every newly wired provisioner. |
| PAR-102 | Lambda | Open | `docs/services/lambda.md` marks `ListLayers` and `ListLayerVersions` as empty stubs and notes that SQS event-source `MaximumConcurrency` is tracked but not enforced. | Add layer storage and SDK tests, then enforce event-source concurrency with restart-safe state and throttling/error semantics. |
| PAR-103 | RAM | Open | `docs/services/ram.md` states that resource-share APIs such as `CreateResourceShare` and `GetResourceShares` are not implemented; the persistence branch only addresses resource-share state retention. | Define the supported RAM resource/share model, implement the management API through `StorageFactory`, and verify account/region visibility plus persistence. |
| PAR-104 | AWS Batch | Open | `docs/services/batch.md` states that `process` mode, array-child fan-out, `CancelJob`, `TerminateJob`, and full Batch-specific input transformers are not implemented. Capacity, VCPU, and VPC behavior are metadata-only. | Choose a bounded local scheduler contract, implement cancellation/termination first, and add SDK tests that distinguish accepted metadata from executable behavior. |
| PAR-105 | API Gateway | Open | `docs/services/api-gateway.md` contains explicit “Not Implemented” sections for management/data-plane operations. | Convert each listed operation into an AWS SDK compatibility test, then prioritize operations required by LZA and common IaC providers. |
| PAR-106 | EKS | Open | `docs/services/eks.md` lists Phase 1 features as not implemented; current support does not imply a local Kubernetes control/data plane. | Keep unsupported operations AWS-shaped, document the supported IRSA/issuer boundary, and scope any future cluster behavior as a separate product decision. |
| PAR-107 | RUM | Open | `docs/services/rum.md` says event/data-plane, tag, resource-policy, metric-definition, and metric-destination APIs are not implemented; `CwLogEnabled` does not emit logs. | Implement the management subset only if a consumer requires it; otherwise add negative SDK tests and make the control-plane-only boundary explicit. |
| PAR-108 | Config | Open | `docs/services/config.md` notes that external evaluation does not record resource configurations and that rule evaluation is invocation bookkeeping rather than real evaluation. | Define the minimum resource recorder/configuration model, then add deterministic rule evaluation fixtures and AWS-shaped failure semantics. |
| PAR-109 | CloudWatch Logs Insights | Open | `docs/services/cloudwatch.md` documents a supported subset where unsupported commands are skipped with warnings rather than rejected, and data-protection policy behavior is incomplete. | Decide whether compatibility requires strict rejection or documented degradation; add query corpus tests for `stats`, `parse`, field projection, pagination, and data-protection APIs. |
| PAR-110 | Cost Explorer | Open | `docs/services/ce.md` lists reservation/Savings Plans coverage/utilization, cost categories, and anomaly management as zeroed/empty stubs or out of scope. | Keep stubs clearly marked, then implement only from a concrete consumer requirement with AWS SDK response-shape tests. |
| PAR-111 | RDS Data API | Open | `docs/services/rds-data.md` marks `BatchExecuteStatement`, parameter binding, JSON formatting/result options, and generated fields as unsupported. | Add parameter binding and batch execution against the local JDBC boundary, with tests for malformed requests and engine-specific errors. |
| PAR-112 | IoT Core | Open | `docs/services/iot.md` identifies missing TLS/mTLS, dynamic thing groups, fleet indexing, job rollouts/cancellation, S3 documents, and advanced scheduling. | Treat TLS/mTLS and job lifecycle as separate slices; avoid claiming data-plane parity while the embedded broker remains plaintext-only. |

## P2: deliberate capability boundaries worth tracking

| ID | Service / area | Status | Evidence / gap | Next step |
| --- | --- | --- | --- | --- |
| PAR-201 | DocumentDB | Open | `docs/services/docdb.md` leaves snapshot creation/restore out of scope and returns an empty snapshot result. | Add snapshot persistence only when a real compatibility consumer needs it; otherwise add an explicit negative test. |
| PAR-202 | CUR / BCM Data Exports | Open | `docs/services/cur.md` and `docs/services/bcm-data-exports.md` limit output to Parquet and reject CSV/text and compression variants. | Add format/compression support behind shared export fixtures, or keep the limitation explicit and tested. |
| PAR-203 | MWAA | Open | `docs/services/mwaa.md` notes metadata-only updates for several environment fields, stubbed web-login tokens, and no automatic Docker reconnection after restart. | Prioritize restart-safe reconnection and update semantics if LZA or a compatibility suite exercises them; leave hosted Airflow authentication explicit until then. |
| PAR-204 | OpenSearch | Open | `docs/services/opensearch.md` lists cross-cluster connections, VPC endpoints, packages, applications, and data sources as unsupported. | Add only the resources required by local IaC scenarios; preserve `UnsupportedOperationException`/AWS-shaped errors for the remainder. |
| PAR-205 | Transfer Family | Open | `docs/services/transfer.md` emulates management state but explicitly excludes actual SFTP/FTP protocol handling. | Keep the control/data-plane distinction visible; investigate a local protocol backend separately if a consumer requires end-to-end file transfer. |
| PAR-206 | Textract / Transcribe | Open | `docs/services/textract.md` and `docs/services/transcribe.md` are synthetic/stub control-plane implementations rather than OCR/transcription engines. | Document as deterministic test doubles and add negative/fixture tests; do not describe synthetic output as model parity. |

## Completed work that still needs rollup evidence

These are not new implementation TODOs, but they must not be lost during branch consolidation:

| Area | Branch / commit | Required close-out |
| --- | --- | --- |
| KMS grant metadata | `feature/kms-grant-fidelity` / `0b8724a8` | Roll up and run KMS integration plus persistence/reload assertions. |
| SNS / Control Tower prerequisite | `feature/controltower-sns-prerequisite` / `6e7c1155` | Roll up and run the Control Tower/SNS prerequisite slice. |
| CodeBuild image mapping | `feature/codebuild-local-image` / `ad79c668`, docs `f99bdc39` | Verify the selected ARM image digest and keep the curated-vs-public image distinction in docs. |
| CodePipeline retry artifacts | `feature/codepipeline-artifact-retry` / `cbbd9305` | Run retry/rollback and persistence tests before V2 work changes lineage. |
| DynamoDB index persistence | `feature/dynamodb-hybrid-persistence` / `90a9062f` | Run hybrid-storage reload coverage. |
| RAM persistence | `feature/ram-persistence` / `eeb10ad4` | Pair with the open RAM API work above. |
| Network Firewall and Service Catalog | `feature/network-firewall` / `399d454f`; `feature/service-catalog` / `76eed019` | Roll up service and CloudFormation provisioner tests, then update the service count once both are present. |

## Working rules for turning rows into issues

1. One row becomes an issue only after its source guide or compatibility test is named in the
   issue body.
2. A TODO is not closed by accepting an AWS-shaped request: the observable response, lifecycle,
   persistence, error, and account/region behavior need an SDK-backed test where applicable.
3. Keep intentional stubs explicit. Never silently return success for an unsupported provider,
   trigger, resource type, or data-plane action.
4. Re-run the integration rollup and branch-orphan audit after each batch; parity consolidation
   comes after branch hygiene, not before.

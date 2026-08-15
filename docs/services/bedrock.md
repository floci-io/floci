# Amazon Bedrock (control plane)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/guardrails` (SigV4 service `bedrock`)

This page covers the Bedrock control plane. Model invocation lives in a separate
service — see [Bedrock Runtime](bedrock-runtime.md).

## Supported Actions

| Action | Description |
|---|---|
| `CreateGuardrail` | Create a guardrail; returns the `DRAFT` version, `READY` immediately |
| `GetGuardrail` | Get a guardrail by id or ARN, optionally at a specific `guardrailVersion` |
| `UpdateGuardrail` | Replace the `DRAFT` configuration |
| `DeleteGuardrail` | Delete one version, or the guardrail and all its versions |
| `ListGuardrails` | List every guardrail's `DRAFT`, or every version of one guardrail |
| `CreateGuardrailVersion` | Snapshot the `DRAFT` under the next numerical version |
| `TagResource` | Tag a guardrail (`POST /tagResource`) |
| `UntagResource` | Remove tags from a guardrail (`POST /untagResource`) |
| `ListTagsForResource` | List a guardrail's tags (`POST /listTagsForResource`) |

Guardrails are versioned. The working copy is `DRAFT`; `GetGuardrail` without a
`guardrailVersion` query parameter returns it. `CreateGuardrailVersion` copies the
current `DRAFT` to `1`, `2`, and so on, and those snapshots are immutable —
`UpdateGuardrail` only ever writes `DRAFT`.

`GuardrailStatus` is `READY` from the first read, so `aws_bedrock_guardrail`'s
status poll completes without a transition. Policy blocks are submitted as the
`*Config` shapes (`topicPolicyConfig`, `contentPolicyConfig`, ...) and read back
under their unsuffixed names (`topicPolicy.topics`, `contentPolicy.filters`, ...),
matching the AWS model.

Both an id (`abc123def456`) and a full ARN are accepted wherever the API takes a
`guardrailIdentifier`.

## Not implemented

These return a clean `UnknownOperationException` rather than a stub success,
because emulating them faithfully would require behaviour Floci has no way to
model:

- `ListFoundationModels`, `GetFoundationModel` — the AWS service model carries no
  enumeration of foundation model ids, so any catalogue would be invented data.
- Custom model, model customization, model import and distillation jobs — these
  depend on real training runs.
- Model evaluation and evaluation jobs — these depend on real inference.
- Provisioned throughput, inference profiles and prompt routers — these describe
  real capacity reservations and routing.
- Model invocation logging, batch inference and async invoke — data-plane
  operations backed by real model execution.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws bedrock create-guardrail \
  --name my-guardrail \
  --blocked-input-messaging "Blocked." \
  --blocked-outputs-messaging "Blocked." \
  --content-policy-config '{"filtersConfig":[{"type":"HATE","inputStrength":"HIGH","outputStrength":"HIGH"}]}'

aws bedrock get-guardrail --guardrail-identifier abc123def456

aws bedrock create-guardrail-version --guardrail-identifier abc123def456

aws bedrock list-guardrails

aws bedrock delete-guardrail --guardrail-identifier abc123def456
```

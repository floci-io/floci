# Bedrock AgentCore

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/runtimes/...`

Emulates the Amazon Bedrock AgentCore **control plane** (`bedrock-agentcore-control`)
as a stateful runtime registry. No real agent execution — runtimes reach `READY`
immediately and hold metadata only. See
[the design note](../design/bedrock-agentcore.md) for scope and the roadmap covering
endpoints, the `InvokeAgentRuntime` data-plane stub, tagging, workload identity, and
gateway/memory primitives.

## Supported Actions

| Action | Description |
|---|---|
| `CreateAgentRuntime` | Register an agent runtime; returns an id, versioned ARN, and workload identity |
| `GetAgentRuntime` | Get a runtime, optionally a specific `version` |
| `ListAgentRuntimes` | List runtimes (paginated) |
| `UpdateAgentRuntime` | Update a runtime; appends a new immutable version |
| `ListAgentRuntimeVersions` | List a runtime's versions (paginated) |
| `DeleteAgentRuntime` | Delete a runtime |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CONTROL_ENABLED` | `true` | Enable or disable the service |

## Behavior notes

- `agentRuntimeId` is `<name>-<10 alphanumerics>`; the ARN embeds a UUID and the
  version: `arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>`.
- `agentRuntimeName` must match `[a-zA-Z][a-zA-Z0-9_]{0,47}` (no hyphens); invalid
  names return `ValidationException`.
- Each `UpdateAgentRuntime` increments the version and preserves prior versions for
  `GetAgentRuntime?version=` and `ListAgentRuntimeVersions`.
- Timestamps (`createdAt`, `lastUpdatedAt`) are ISO-8601 strings.
- Config blobs (`agentRuntimeArtifact`, `networkConfiguration`, …) are stored opaquely
  and echoed back; they are not deeply validated.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an agent runtime
aws bedrock-agentcore-control create-agent-runtime \
  --agent-runtime-name myAgent \
  --agent-runtime-artifact '{"containerConfiguration":{"containerUri":"public.ecr.aws/x/agent:latest"}}' \
  --network-configuration '{"networkMode":"PUBLIC"}' \
  --role-arn "arn:aws:iam::000000000000:role/agent-runtime" \
  --endpoint-url $AWS_ENDPOINT_URL

# Get / list
aws bedrock-agentcore-control get-agent-runtime --agent-runtime-id <id> --endpoint-url $AWS_ENDPOINT_URL
aws bedrock-agentcore-control list-agent-runtimes --endpoint-url $AWS_ENDPOINT_URL

# Delete
aws bedrock-agentcore-control delete-agent-runtime --agent-runtime-id <id> --endpoint-url $AWS_ENDPOINT_URL
```

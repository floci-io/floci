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
| `CreateAgentRuntimeEndpoint` | Create a named endpoint (qualifier) targeting a version |
| `GetAgentRuntimeEndpoint` | Get an endpoint |
| `UpdateAgentRuntimeEndpoint` | Retarget an endpoint's version / update its description |
| `ListAgentRuntimeEndpoints` | List a runtime's endpoints (paginated) |
| `DeleteAgentRuntimeEndpoint` | Delete an endpoint |
| `InvokeAgentRuntime` *(data plane)* | Invoke a runtime; returns a fixed canned response |

A `DEFAULT` endpoint is created automatically with each runtime.

## Data plane — `InvokeAgentRuntime`

`POST /runtimes/{agentRuntimeArn}/invocations` returns a fixed, configurable JSON
body (default `{"output":"yes"}`) and echoes the
`X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` header. The request payload (opaque
binary, up to 100 MB) is never parsed. Streaming responses are not emulated — a
single non-streaming `200` is returned.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CONTROL_ENABLED` | `true` | Enable/disable the control plane |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_ENABLED` | `true` | Enable/disable the data plane (invoke) |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_INVOKE_RESPONSE` | `{"output":"yes"}` | Canned `InvokeAgentRuntime` response body |

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

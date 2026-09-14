# Bedrock AgentCore

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/runtimes/...`

Emulates the Amazon Bedrock AgentCore **control plane** (`bedrock-agentcore-control`)
as a stateful local registry across runtimes, endpoints, gateways, memories, browser/code-interpreter
resources, credential providers, gateway rules, and resource policies. No real agent execution:
runtimes reach `READY` immediately and hold metadata only. See
[the design note](../design/bedrock-agentcore.md) for scope and protocol details.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAgentRuntime` | Register an agent runtime; returns an id, versioned ARN, and workload identity |
| `ListAgentRuntimes` | List runtimes (paginated) |
| `GetAgentRuntime` | Get a runtime, optionally a specific `version` |
| `UpdateAgentRuntime` | Update a runtime; appends a new immutable version |
| `ListAgentRuntimeVersions` | List a runtime's versions (paginated) |
| `DeleteAgentRuntime` | Delete a runtime |
| `CreateAgentRuntimeEndpoint` | Creates a named runtime endpoint targeting an agent runtime version |
| `GetAgentRuntimeEndpoint` | Returns a runtime endpoint |
| `UpdateAgentRuntimeEndpoint` | Updates the target version or description of a runtime endpoint |
| `DeleteAgentRuntimeEndpoint` | Deletes a runtime endpoint |
| `ListAgentRuntimeEndpoints` | Lists runtime endpoints for an agent runtime |
| `CreateWorkloadIdentity` | Creates a workload identity |
| `GetWorkloadIdentity` | Returns a workload identity |
| `UpdateWorkloadIdentity` | Updates a workload identity |
| `DeleteWorkloadIdentity` | Deletes a workload identity |
| `ListWorkloadIdentities` | Lists workload identities |
| `CreateApiKeyCredentialProvider` | Creates an API key credential provider |
| `GetApiKeyCredentialProvider` | Returns an API key credential provider |
| `ListApiKeyCredentialProviders` | Lists API key credential providers |
| `UpdateApiKeyCredentialProvider` | Updates an API key credential provider |
| `DeleteApiKeyCredentialProvider` | Deletes an API key credential provider |
| `CreateOauth2CredentialProvider` | Creates an OAuth2 credential provider |
| `GetOauth2CredentialProvider` | Returns an OAuth2 credential provider |
| `ListOauth2CredentialProviders` | Lists OAuth2 credential providers |
| `UpdateOauth2CredentialProvider` | Updates an OAuth2 credential provider |
| `DeleteOauth2CredentialProvider` | Deletes an OAuth2 credential provider |
| `CreateGateway` | Creates a gateway |
| `GetGateway` | Returns a gateway |
| `UpdateGateway` | Updates a gateway |
| `DeleteGateway` | Deletes a gateway |
| `ListGateways` | Lists gateways |
| `CreateGatewayTarget` | Creates a target on a gateway |
| `GetGatewayTarget` | Returns a gateway target |
| `UpdateGatewayTarget` | Updates a gateway target |
| `DeleteGatewayTarget` | Deletes a gateway target |
| `ListGatewayTargets` | Lists targets on a gateway |
| `CreateMemory` | Creates a memory resource |
| `GetMemory` | Returns a memory resource |
| `UpdateMemory` | Updates a memory resource |
| `DeleteMemory` | Deletes a memory resource |
| `ListMemories` | Lists memory resources |
| `CreateEvent` | Appends an event to a memory. `sessionId` is optional and generated when omitted |
| `ListEvents` | Lists a session's events, newest first. `POST` to the session path, not `GET` |
| `GetEvent` | Returns one event |
| `DeleteEvent` | Deletes one event and echoes its id |
| `CreateBrowser` | Creates a custom browser |
| `GetCodeInterpreter` | Returns a custom or system code interpreter |
| `DeleteCodeInterpreter` | Deletes a custom code interpreter |
| `ListCodeInterpreters` | Lists custom and system code interpreters |
| `CreateCodeInterpreter` | Creates a custom code interpreter |
| `CreateBrowserProfile` | Creates a browser profile |
| `ListBrowserProfiles` | Lists browser profiles |
| `DeleteBrowserProfile` | Deletes a browser profile |
| `GetBrowserProfile` | Returns a browser profile |
| `GetBrowser` | Returns a custom or system browser |
| `DeleteBrowser` | Deletes a custom browser |
| `ListBrowsers` | Lists custom and system browsers |
| `CreateGatewayRule` | Creates a gateway rule |
| `GetGatewayRule` | Returns a gateway rule |
| `ListGatewayRules` | Lists rules on a gateway |
| `UpdateGatewayRule` | Updates a gateway rule |
| `DeleteGatewayRule` | Deletes a gateway rule |
| `GetResourcePolicy` | Returns the policy attached to an AgentCore resource |
| `PutResourcePolicy` | Creates or replaces a policy on an AgentCore resource |
| `DeleteResourcePolicy` | Deletes the policy attached to an AgentCore resource |
<!-- floci:actions:end -->

A `DEFAULT` endpoint is created automatically with each runtime, and each runtime
is associated with an auto-created, resolvable workload identity.

## Memory events

The event data plane is backed by real storage, so what a read returns is what was written rather
than a fixed answer. Behaviour measured against real AgentCore:

- events come back **newest first**, and the event id embeds a zero-padded timestamp so ids sort
  chronologically as plain strings
- `includePayloads: false` omits the `payload` key entirely rather than emptying it
- `maxResults` accepts 1 to 100
- a **malformed** memory id is a `ValidationException`, while a **well-formed but unknown** one is a
  `ResourceNotFoundException`. A memory id is a name followed by exactly ten alphanumerics
- an unknown actor or session is an empty list, not an error
- a new event lands on the `main` branch, and an empty payload is accepted, though `payload` is a
  required member: omitting it is a `ValidationException` while `[]` is valid
- `CreateEvent` answers `201`, not `200`
- `ListEvents` pages with `nextToken` and defaults to 20 events when a caller names no `maxResults`

Memory *records* (extraction and retrieval) are not emulated: they depend on an extraction engine
rather than on stored events.

## Data plane — `InvokeAgentRuntime`

`POST /runtimes/{agentRuntimeArn}/invocations` returns a fixed, configurable JSON
body (default `{"output":"yes"}`) and echoes the
`X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` header. The request payload (opaque
binary, up to 100 MB) is never parsed. This operation returns a single non-streaming
`200`; `InvokeHarness` below is the streaming one.

## Data plane — `InvokeHarness`

`POST /harnesses/invoke` returns an `application/vnd.amazon.eventstream` response, the same
framing `ConverseStream` uses, so an SDK client's stream iterator works unchanged. The frames
arrive in AWS's order: `messageStart`, `contentBlockStart`, one `contentBlockDelta` per chunk,
`contentBlockStop`, `messageStop`, `metadata`.

`harnessArn`, `runtimeSessionId` and `messages` are all required, and the first two are validated
against their modelled shapes rather than merely checked for presence: a harness ARN ends in the
same `name-<10 alphanumerics>` form AgentCore uses elsewhere, and a runtime session id is 33 to 100
characters. `messages` may be an empty array, but omitting the member is a `ValidationException`.

Note the wire bindings, which are easy to get wrong: `harnessArn` and `qualifier` are **query
parameters**, `runtimeSessionId` and `runtimeUserId` are **headers**
(`X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` / `-User-Id`), and only `messages`, `model`,
`tools` and friends travel in the JSON body. A missing `harnessArn` or `runtimeSessionId` is a
`ValidationException`.

There is no agent loop and no model. The assistant's reply **echoes the caller's last user
message**, so a chat client visibly works end to end and a request that failed to parse is obvious
rather than hidden behind a fixed string. A request whose `messages` array is present but empty, or
carries no user turn, is legitimate and gets a canned reply rather than an error.

The `harnessArn` is not resolved: the emulator models no harness resource, so there is nothing to
look one up in. Tool execution, skills and multi-turn iteration are not emulated, so no tool-use
block is ever produced even when a request supplies `tools`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CONTROL_ENABLED` | `true` | Enable/disable the control plane |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_ENABLED` | `true` | Enable/disable the data plane (invoke) |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_INVOKE_RESPONSE` | `{"output":"yes"}` | Canned `InvokeAgentRuntime` response body |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_VALIDATE_RUNTIME_EXISTS` | `false` | When `true`, `InvokeAgentRuntime` returns `ResourceNotFoundException` for an unknown runtime ARN instead of the canned response |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_HARNESS_ECHO_PREFIX` | `You said: ` | Prefix on the reply `InvokeHarness` streams back |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_HARNESS_EMPTY_REPLY` | `No user message was supplied.` | Reply used when a request carries no user message |

> **Note on YAML config keys.** The status endpoint reports these services as
> `bedrock-agentcore-control` and `bedrock-agentcore`, but the YAML property paths
> use hyphenated words: `floci.services.bedrock-agent-core-control.enabled` and
> `floci.services.bedrock-agent-core.enabled` (and `…bedrock-agent-core.invoke-response`,
> `…bedrock-agent-core.validate-runtime-exists`, `…bedrock-agent-core.harness-echo-prefix`,
> `…bedrock-agent-core.harness-empty-reply`). The `FLOCI_*` environment variables
> above map to these paths directly and are the recommended way to configure the service.

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
- `CreateMemory` persists `tags`, `encryptionKeyArn`, and `memoryExecutionRoleArn`;
  `UpdateMemory` applies `description`, `eventExpiryDuration`, and
  `memoryExecutionRoleArn`. As in AWS, memory tags are returned only by
  `ListTagsForResource`, never embedded in the `memory` response shape.

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

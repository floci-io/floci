# Bedrock Runtime

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/model/{modelId}/...`

Floci emulates the AWS Bedrock Runtime data-plane API. The response shape matches the real AWS Converse and InvokeModel contracts so AWS SDK and CLI clients accept the reply without error.

Two backends are available, selected via `floci.services.bedrock-runtime.backend`:

- `stub` (default) — no real model inference: every call returns a fixed assistant turn plus synthetic token usage metadata.
- `proxy` — forwards `Converse` requests to any OpenAI-compatible `/chat/completions` endpoint (Ollama, OpenRouter, LiteLLM, vLLM, ...), translating request/response shapes including tool use. `InvokeModel` still falls back to the stub response in this mode. See [Proxy Backend](#proxy-backend) below.

The Bedrock management plane (`aws bedrock ...`: `ListFoundationModels`, `GetFoundationModel`, customization) is not yet emulated.

## Supported Operations

| Operation | Endpoint | Notes |
|-----------|----------|-------|
| `Converse` | `POST /model/{modelId}/converse` | `stub`: returns a static assistant message. `proxy`: forwards to the configured OpenAI-compatible backend and translates the reply. |
| `InvokeModel` | `POST /model/{modelId}/invoke` | Returns Anthropic-shaped body for `anthropic.*` and `*.anthropic.*` model ids; generic `{"outputs": [...]}` shape otherwise. Not proxied yet — always the stub response, regardless of backend. |
| `ConverseStream` | `POST /model/{modelId}/converse-stream` | Returns 501 `UnsupportedOperationException` |
| `InvokeModelWithResponseStream` | `POST /model/{modelId}/invoke-with-response-stream` | Returns 501 `UnsupportedOperationException` |

`modelId` is URL-decoded by JAX-RS and echoed verbatim. Plain model ids (e.g. `anthropic.claude-3-haiku-20240307-v1:0`), inference-profile ids (e.g. `us.anthropic.claude-3-5-sonnet-20241022-v2:0`), and full ARNs containing slashes (e.g. `arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.anthropic.claude-3-5-sonnet-20241022-v2:0`) are all accepted.

Converse accepts `messages`, `system`, `inferenceConfig`, and `toolConfig` fields. Only `messages` is validated (non-empty array). With the `stub` backend, other fields are accepted and ignored, and tool-use round-tripping is not implemented. With the `proxy` backend, `system`, `toolConfig.tools` and `toolConfig.toolChoice`, and `inferenceConfig` (`maxTokens`, `temperature`, `topP`, `stopSequences`) are translated and forwarded (see below).

InvokeModel bodies are passed through as opaque bytes; neither backend parses request payloads.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_RUNTIME_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_BACKEND` | `stub` | `stub` or `proxy` |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_URL` | _(none)_ | Base URL of the OpenAI-compatible backend, e.g. `http://localhost:11434/v1`. Required when `backend=proxy`. |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_API_KEY` | _(none)_ | Sent as `Authorization: Bearer {value}` when set |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL` | _(none)_ | Fallback OpenAI-side model id when no mapping matches and passthrough is disabled |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING` | _(none)_ | Comma-separated `bedrockModelId=openaiModelId` pairs, e.g. `anthropic.claude-3-sonnet-20240229-v1:0=claude-3-sonnet` |
| `FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_PASSTHROUGH` | `false` | When true, forward the raw Bedrock model id as-is if no mapping matches |

## Proxy Backend

Model resolution order for an incoming `modelId`: explicit `model-mapping` entry, then (if `passthrough=true`) the raw `modelId` as-is, then `default-model`, else a `400 ValidationException`.

```bash
# Ollama (local, no API key needed)
export FLOCI_SERVICES_BEDROCK_RUNTIME_BACKEND=proxy
export FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_URL=http://localhost:11434/v1
export FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_DEFAULT_MODEL=llama3.2
```

Useful for integration testing: applications that call Bedrock directly don't need to stand up their own stub/mock of Bedrock — point the existing SDK client at Floci and get real responses from whatever OpenAI-compatible backend is already available, with the stubbing concern handled by Floci instead of the application under test.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# Converse
aws bedrock-runtime converse \
  --model-id anthropic.claude-3-haiku-20240307-v1:0 \
  --messages '[{"role":"user","content":[{"text":"hi"}]}]'

# InvokeModel (Anthropic Claude)
aws bedrock-runtime invoke-model \
  --model-id anthropic.claude-3-haiku-20240307-v1:0 \
  --body '{"anthropic_version":"bedrock-2023-05-31","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}' \
  --cli-binary-format raw-in-base64-out \
  /tmp/response.json
cat /tmp/response.json
```

```python
import boto3
client = boto3.client("bedrock-runtime", endpoint_url="http://localhost:4566")
resp = client.converse(
    modelId="anthropic.claude-3-haiku-20240307-v1:0",
    messages=[{"role": "user", "content": [{"text": "hi"}]}],
)
print(resp["output"]["message"]["content"][0]["text"])
```

## Out of Scope

- Real model inference with the `stub` backend (always returns a fixed string).
- `InvokeModel` against the `proxy` backend (still returns the stub response).
- Streaming (`ConverseStream`, `InvokeModelWithResponseStream`) return 501, regardless of backend.
- Bedrock management plane (`ListFoundationModels`, `GetFoundationModel`, model customisation).
- Bedrock Agents, Knowledge Bases, Guardrails, provisioned throughput.

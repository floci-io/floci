# Bedrock Runtime

**Protocol:** REST JSON
**Endpoint:** `POST http://localhost:4566/model/{modelId}/...`

Floci emulates the AWS Bedrock Runtime data-plane API with a dummy response stub. The response shape matches the real AWS Converse and InvokeModel contracts so AWS SDK and CLI clients accept the reply without error. No real model inference is performed: every call returns a fixed assistant turn plus synthetic token usage metadata.

The Bedrock management plane (`aws bedrock ...`: `ListFoundationModels`, `GetFoundationModel`, customization) is not yet emulated.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `Converse` |
| `InvokeModel` |
| `InvokeModelWithResponseStream` |
| `ConverseStream` |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    bedrock-runtime:
      enabled: true
```

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

- Real model inference (always returns a fixed string).
- Streaming (`ConverseStream`, `InvokeModelWithResponseStream`) return 501.
- Bedrock management plane (`ListFoundationModels`, `GetFoundationModel`, model customisation).
- Bedrock Agents, Knowledge Bases, Guardrails, provisioned throughput.
- Tool-use round-tripping in Converse.

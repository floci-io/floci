# Amazon Bedrock

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566`
**Signing name:** `bedrock`

Floci emulates the Amazon Bedrock control plane with full support for **Batch Inference** (`ModelInvocationJob` lifecycle, S3 dataset processing, manifest generation, and EventBridge event publication).

For real-time single-request model inference (`Converse` / `InvokeModel`), see [Bedrock Runtime](bedrock-runtime.md).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateModelInvocationJob` | Submits and triggers an asynchronous batch inference job reading input records from S3 and writing results and manifest to S3 (`POST /model-invocation-job`) |
| `GetModelInvocationJob` | Retrieves properties, metadata, metrics, and current status of a batch model invocation job by ARN, job ID, or job name (`GET /model-invocation-job/{jobIdentifier}`) |
| `StopModelInvocationJob` | Stops an in-progress batch model invocation job (`POST /model-invocation-job/{jobIdentifier}/stop`) |
| `ListModelInvocationJobs` | Returns a paginated list of batch model invocation jobs with support for status filtering, name search, time range filters, and sorting (`GET /model-invocation-jobs`) |
<!-- floci:actions:end -->

## How Batch Inference Works

1. **Input S3 Dataset**: Place a JSON Lines (`.jsonl`) file in an S3 bucket. Each line contains a record with a unique `recordId` and the `modelInput` payload formatted for the target model:
   - **Anthropic Claude 3 / 3.5**:
     ```json
     {"recordId": "rec-001", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "max_tokens": 1024, "messages": [{"role": "user", "content": [{"type": "text", "text": "Explain quantum computing"}]}]}}
     ```
   - **Amazon Titan**:
     ```json
     {"recordId": "rec-002", "modelInput": {"inputText": "Hello world", "textGenerationConfig": {"maxTokenCount": 512, "temperature": 0.7}}}
     ```
   - **Meta Llama**:
     ```json
     {"recordId": "rec-003", "modelInput": {"prompt": "What is gravity?", "max_gen_len": 512, "temperature": 0.5}}
     ```
2. **Job Submission**: Call `CreateModelInvocationJob` specifying `inputDataConfig` (`s3Uri`), `outputDataConfig` (`s3Uri`), `modelId`, `roleArn`, and `jobName`.
3. **Execution & Backend Delegation**:
   - Floci reads the input JSONL file from the specified S3 bucket.
   - For each record, inference is executed via the configured Bedrock Runtime backend:
     - `stub` (default): generates simulated model outputs.
     - `proxy`: forwards Converse/InvokeModel requests to any OpenAI-compatible backend (Ollama, vLLM, LiteLLM, OpenRouter).
4. **S3 Output Files**:
   - Output records are written to `<outputS3Uri>/<jobId>/<inputFilename>.out`:
     ```json
     {"recordId": "rec-001", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "max_tokens": 1024, "messages": [{"role": "user", "content": [{"type": "text", "text": "Explain quantum computing"}]}]}, "modelOutput": {"id": "msg_01...", "type": "message", "role": "assistant", "content": [{"type": "text", "text": "Quantum computing is..."}], "stop_reason": "end_turn", "usage": {"input_tokens": 12, "output_tokens": 25}}}
     ```
   - If an individual record fails, an error entry is recorded:
     ```json
     {"recordId": "rec-002", "modelInput": {}, "error": {"errorCode": "400", "errorMessage": "Malformed record: recordId and modelInput object are required."}}
     ```
5. **Manifest Generation**:
   - A summary manifest is written to `<outputS3Uri>/<jobId>/manifest.json.out`:
     ```json
     {
       "totalRecordCount": 2,
       "processedRecordCount": 2,
       "successRecordCount": 2,
       "errorRecordCount": 0,
       "inputTokenCount": 24,
       "outputTokenCount": 50
     }
     ```

## Job Status Lifecycle

Floci models all AWS Bedrock batch job states:

| Status | Description |
|---|---|
| `Submitted` | Job has been created and queued for validation |
| `Validating` | Input data configuration and S3 paths are being validated |
| `Scheduled` | Job is scheduled for batch processing |
| `InProgress` | Records are being processed and model inference is executing |
| `Completed` | All records were processed successfully (`errorRecordCount == 0`) |
| `PartiallyCompleted` | Processing finished with both successful and failed records |
| `Failed` | Job failed to process (e.g., missing S3 input file or all records errored) |
| `Stopping` | Stop request received, halting execution |
| `Stopped` | Job stopped before completion |
| `Expired` | Job reached expiration threshold |

## EventBridge Integration

Every batch job transition emits state change notifications to EventBridge matching AWS event patterns:

- **Source**: `aws.bedrock`
- **Detail Type**: `Batch Inference Job State Change`
- **Resources**: `["arn:aws:bedrock:<region>:<accountId>:model-invocation-job/<jobId>"]`

Example EventBridge event payload:

```json
{
  "version": "0",
  "id": "c1a2b3c4-d5e6-7f80-9101-112131415161",
  "detail-type": "Batch Inference Job State Change",
  "source": "aws.bedrock",
  "account": "000000000000",
  "time": "2026-08-30T12:00:00Z",
  "region": "us-east-1",
  "resources": [
    "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/abc123def456"
  ],
  "detail": {
    "version": "0.0",
    "accountId": "000000000000",
    "batchJobName": "my-batch-job",
    "batchJobArn": "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/abc123def456",
    "batchModelId": "anthropic.claude-3-haiku-20240307-v1:0",
    "status": "Completed",
    "failureMessage": "",
    "creationTime": "Aug 30, 2026, 12:00:00 PM"
  }
}
```

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_ENABLED` | `true` | Enable or disable the Bedrock service |
| `FLOCI_STORAGE_SERVICES_BEDROCK_MODE` | `memory` | Storage mode for batch jobs: `memory`, `persistent`, `hybrid`, `wal` |
| `FLOCI_STORAGE_SERVICES_BEDROCK_FLUSH_INTERVAL_MS` | `5000` | Flush interval in milliseconds for persistent storage |

## Examples

### AWS CLI

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1

# 1. Create S3 buckets and upload prompts
aws s3 mb s3://bedrock-batch-input
aws s3 mb s3://bedrock-batch-output
echo '{"recordId": "rec-1", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "max_tokens": 1024, "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}]}}' > prompts.jsonl
aws s3 cp prompts.jsonl s3://bedrock-batch-input/prompts.jsonl

# 2. Create Model Invocation Job
JOB_ARN=$(aws bedrock create-model-invocation-job \
  --job-name "my-first-batch-job" \
  --model-id "anthropic.claude-3-haiku-20240307-v1:0" \
  --role-arn "arn:aws:iam::000000000000:role/BedrockBatchRole" \
  --input-data-config '{"s3InputDataConfig": {"s3Uri": "s3://bedrock-batch-input/prompts.jsonl"}}' \
  --output-data-config '{"s3OutputDataConfig": {"s3Uri": "s3://bedrock-batch-output/results"}}' \
  --query 'jobArn' --output text)

# 3. Get Job Status
aws bedrock get-model-invocation-job --job-identifier "$JOB_ARN"

# 4. List Jobs
aws bedrock list-model-invocation-jobs --status-equals Completed

# 5. Inspect S3 Output and Manifest
aws s3 ls s3://bedrock-batch-output/results/ --recursive
```

### Python (boto3)

```python
import boto3

bedrock = boto3.client("bedrock", endpoint_url="http://localhost:4566", region_name="us-east-1")
s3 = boto3.client("s3", endpoint_url="http://localhost:4566", region_name="us-east-1")

# Create buckets and upload prompts
s3.create_bucket(Bucket="batch-input")
s3.create_bucket(Bucket="batch-output")

prompts = '{"recordId": "p1", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "max_tokens": 1024, "messages": [{"role": "user", "content": [{"type": "text", "text": "Summarize Floci"}]}]}}\n'
s3.put_object(Bucket="batch-input", Key="prompts.jsonl", Body=prompts.encode("utf-8"))

# Submit batch inference job
response = bedrock.create_model_invocation_job(
    jobName="batch-summarization",
    modelId="anthropic.claude-3-haiku-20240307-v1:0",
    roleArn="arn:aws:iam::000000000000:role/BedrockBatchRole",
    inputDataConfig={"s3InputDataConfig": {"s3Uri": "s3://batch-input/prompts.jsonl"}},
    outputDataConfig={"s3OutputDataConfig": {"s3Uri": "s3://batch-output/results"}},
)
job_arn = response["jobArn"]

# Check job status
job = bedrock.get_model_invocation_job(jobIdentifier=job_arn)
print(f"Status: {job['status']}")
```

## Current Scope & Limitations

- **Batch Processing**: S3 JSON Lines input/output, manifest creation, and EventBridge notifications are fully supported.
- **Record Limits**: Real AWS Bedrock enforces minimum record limits for certain models during batch inference; these minimum limits are not currently enforced in Floci.
- **Model Customization & Fine-Tuning**: `CreateModelCustomizationJob`, `ListCustomModels`, and model evaluation endpoints are not yet emulated.
- **Knowledge Bases & Agents**: For agent runtimes, gateways, and memory, see [Bedrock AgentCore](bedrock-agentcore.md).

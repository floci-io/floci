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

1. **Input S3 Dataset**: Place a JSON Lines (`.jsonl`) file in an S3 bucket. Each line contains a record with a unique `recordId` and the `modelInput` payload:
   ```json
   {"recordId": "rec-001", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Hello world"}]}]}}
   {"recordId": "rec-002", "modelInput": {"anthropic_version": "bedrock-2023-05-31", "max_tokens": 100, "messages": [{"role": "user", "content": [{"type": "text", "text": "Explain quantum computing"}]}]}}
   ```
2. **Job Submission**: Call `CreateModelInvocationJob` specifying `inputDataConfig` (`s3Uri`), `outputDataConfig` (`s3Uri`), `modelId`, `roleArn`, and `jobName`.
3. **Execution & Backend Delegation**:
   - Floci reads the input JSONL file from the specified S3 bucket.
   - For each record, inference is executed via the configured Bedrock Runtime backend:
     - `stub` (default): generates simulated model outputs.
     - `proxy`: forwards Converse requests to any OpenAI-compatible backend (Ollama, vLLM, LiteLLM, OpenRouter).
4. **S3 Output Files**:
   - Output records are written to `<outputS3Uri>/<jobId>/<inputFilename>.out`:
     ```json
     {"recordId": "rec-001", "modelInput": {...}, "modelOutput": {...}}
     ```
   - If an individual record fails, an error entry is recorded:
     ```json
     {"recordId": "rec-002", "modelInput": {...}, "error": {"errorCode": "400", "errorMessage": "Invalid model input"}}
     ```
5. **Manifest Generation**:
   - A summary manifest is written to `<outputS3Uri>/<jobId>/manifest.json.out`:
     ```json
     {
       "total-record-count": 2,
       "processed-record-count": 2,
       "success-record-count": 2,
       "error-record-count": 0,
       "input": "s3://my-input-bucket/prompts.jsonl",
       "output": "s3://my-output-bucket/results/job-123456789abc/prompts.jsonl.out"
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
| `Completed` | All records were processed successfully (`error-record-count == 0`) |
| `PartiallyCompleted` | Processing finished with both successful and failed records |
| `Failed` | Job failed to process (e.g., missing S3 input file or all records errored) |
| `Stopping` | Stop request received, halting execution |
| `Stopped` | Job stopped before completion |
| `Expired` | Job reached expiration threshold |

## EventBridge Integration

Every batch job transition emits state change notifications to EventBridge matching AWS event patterns:

- **Source**: `aws.bedrock`
- **Detail Type**: `Bedrock Model Invocation Job State Change`
- **Resources**: `["arn:aws:bedrock:<region>:<accountId>:model-invocation-job/<jobId>"]`

Example EventBridge event payload:

```json
{
  "version": "0",
  "id": "c1a2b3c4-d5e6-7f80-9101-112131415161",
  "detail-type": "Bedrock Model Invocation Job State Change",
  "source": "aws.bedrock",
  "account": "000000000000",
  "time": "2026-08-30T12:00:00Z",
  "region": "us-east-1",
  "resources": [
    "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/abc123def456"
  ],
  "detail": {
    "jobArn": "arn:aws:bedrock:us-east-1:000000000000:model-invocation-job/abc123def456",
    "jobName": "my-batch-job",
    "modelId": "anthropic.claude-3-haiku-20240307-v1:0",
    "status": "Completed",
    "submitTime": "2026-08-30T12:00:00Z",
    "endTime": "2026-08-30T12:00:05Z",
    "inputDataConfig": {
      "s3InputDataConfig": {
        "s3Uri": "s3://input-bucket/prompts.jsonl"
      }
    },
    "outputDataConfig": {
      "s3OutputDataConfig": {
        "s3Uri": "s3://output-bucket/results"
      }
    },
    "roleArn": "arn:aws:iam::000000000000:role/BedrockBatchRole"
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
echo '{"recordId": "rec-1", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Hello"}]}]}}' > prompts.jsonl
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

prompts = '{"recordId": "p1", "modelInput": {"messages": [{"role": "user", "content": [{"text": "Summarize Floci"}]}]}}\n'
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
- **Model Customization & Fine-Tuning**: `CreateModelCustomizationJob`, `ListCustomModels`, and model evaluation endpoints are not yet emulated.
- **Knowledge Bases & Agents**: For agent runtimes, gateways, and memory, see [Bedrock AgentCore](bedrock-agentcore.md).

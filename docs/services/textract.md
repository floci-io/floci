# Textract

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: Textract.<Action>`

Floci emulates the AWS Textract API with a dummy response stub. The response shape matches the real AWS Textract contracts so AWS SDK and CLI clients accept the reply without error. No real OCR or document analysis is performed: every call returns a fixed set of `Block` objects with synthetic metadata.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `DetectDocumentText` | Returns stub PAGE + LINE + WORD blocks |
| `AnalyzeDocument` | Returns stub blocks; `FeatureTypes` accepted but ignored |
| `StartDocumentTextDetection` | Returns a `JobId`; job is immediately SUCCEEDED |
| `GetDocumentTextDetection` | Returns `SUCCEEDED` + stub blocks for a known `JobId` |
| `StartDocumentAnalysis` | Returns a `JobId`; job is immediately SUCCEEDED |
| `GetDocumentAnalysis` | Returns `SUCCEEDED` + stub blocks for a known `JobId` |

`Document` and `DocumentLocation` inputs (bytes or S3 references) are accepted but not parsed.

### Block shape

Each response includes a 3-block hierarchy matching the [AWS Block API shape](https://docs.aws.amazon.com/textract/latest/dg/API_Block.html):

| BlockType | Text | Relationships |
|-----------|------|---------------|
| `PAGE` | *(none)* | CHILD → LINE |
| `LINE` | `"Floci"` | CHILD → WORD |
| `WORD` | `"Floci"` | *(none)* |

Every block includes: `Id` (UUID), `Confidence` (99.9), `Page` (1), and a `Geometry` with `BoundingBox` + 4-point `Polygon`.

### Async job lifecycle

`Start*` operations store a job ID in memory and return it immediately. `Get*` calls with a valid job ID always return `JobStatus: SUCCEEDED`. Job IDs are not persisted across restarts. Using a `GetDocumentTextDetection` job ID in `GetDocumentAnalysis` (or vice-versa) returns `InvalidJobIdException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TEXTRACT_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

`DetectDocumentText` and `AnalyzeDocument` return the same stub `Block` list for every
call by default. To exercise application logic that reads the detected text, point
`AI_MOCK_CONFIG` at a JSON file shared across Textract, Comprehend, and Rekognition:

```json
{
  "textract": {
    "my-bucket/invoice.pdf": {
      "DetectDocumentText": {
        "DocumentMetadata": { "Pages": 1 },
        "Blocks": [{ "BlockType": "LINE", "Id": "line-1", "Confidence": 99.5, "Text": "Invoice Total: $42.00" }],
        "DetectDocumentTextModelVersion": "1.0"
      }
    }
  }
}
```

The lookup key is **`"<Bucket>/<Name>"`** from `Document.S3Object`. A `Bytes`-backed
`Document` has no such key, so mocking only applies to S3Object-based calls — a
`Bytes`-backed request always gets the default stub. The file is re-read when its
modification time changes, so it can be edited without restarting the emulator. A missing
file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to the default stub.
The async `Start*`/`Get*` job flow does not support mocking.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

# DetectDocumentText
aws textract detect-document-text \
  --document '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}'

# AnalyzeDocument
aws textract analyze-document \
  --document '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}' \
  --feature-types TABLES FORMS

# Async: start + poll
JOB_ID=$(aws textract start-document-text-detection \
  --document-location '{"S3Object":{"Bucket":"my-bucket","Name":"test.pdf"}}' \
  --query JobId --output text)

aws textract get-document-text-detection --job-id "$JOB_ID"
```

```python
import boto3

client = boto3.client("textract", endpoint_url="http://localhost:4566")

# Sync
resp = client.detect_document_text(
    Document={"S3Object": {"Bucket": "my-bucket", "Name": "test.pdf"}}
)
for block in resp["Blocks"]:
    print(block["BlockType"], block.get("Text", ""))

# Async
job = client.start_document_text_detection(
    DocumentLocation={"S3Object": {"Bucket": "my-bucket", "Name": "test.pdf"}}
)
result = client.get_document_text_detection(JobId=job["JobId"])
print(result["JobStatus"])  # SUCCEEDED
```

## Out of Scope

- Real OCR or document analysis (always returns a fixed stub block list).
- `AnalyzeExpense`, `AnalyzeID`, `AnalyzeLendingDocument` and other specialized analysis operations.
- `GetAdapterVersion`, `CreateAdapter`, `ListAdapters` (Adapter management API).
- `GetDocumentTextDetection` / `GetDocumentAnalysis` pagination via `NextToken`.
- Persistent job storage across restarts.


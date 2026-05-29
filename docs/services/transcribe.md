# Transcribe

**Protocol:** JSON 1.1
**Header:** `X-Amz-Target: Transcribe.<Action>`
**Endpoint prefix:** `transcribe`

Floci emulates a small Amazon Transcribe control-plane stub for applications
that create, inspect, list, and delete transcription jobs or custom
vocabularies. Jobs transition to `COMPLETED` immediately and vocabularies
transition to `READY` immediately.

No real audio transcription is performed. `StartTranscriptionJob` accepts the
media URI and returns a synthetic `TranscriptFileUri` in the response so AWS SDK
and CLI clients can exercise their Transcribe control-flow code locally.

## Supported Operations

| Operation | Notes |
|-----------|-------|
| `StartTranscriptionJob` | Creates an in-memory job and returns it as `COMPLETED` |
| `GetTranscriptionJob` | Returns a stored job by `TranscriptionJobName` |
| `ListTranscriptionJobs` | Lists jobs with optional `Status`, `JobNameContains`, and `MaxResults` filters |
| `DeleteTranscriptionJob` | Removes a stored job |
| `CreateVocabulary` | Creates an in-memory vocabulary and returns it as `READY` |
| `GetVocabulary` | Returns a stored vocabulary by `VocabularyName` |
| `ListVocabularies` | Lists vocabularies with optional `StateEquals`, `NameContains`, and `MaxResults` filters |
| `DeleteVocabulary` | Removes a stored vocabulary |

`LanguageCode` defaults to `en-US` and `MediaFormat` defaults to `mp4` when
they are omitted from `StartTranscriptionJob`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TRANSCRIBE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws transcribe start-transcription-job \
  --transcription-job-name floci-demo \
  --media MediaFileUri=s3://my-bucket/audio.mp3 \
  --language-code en-US \
  --media-format mp3

aws transcribe get-transcription-job \
  --transcription-job-name floci-demo

aws transcribe create-vocabulary \
  --vocabulary-name floci-vocab \
  --language-code en-US

aws transcribe list-vocabularies \
  --name-contains floci
```

```python
import boto3

client = boto3.client(
    "transcribe",
    endpoint_url="http://localhost:4566",
    region_name="us-east-1",
)

client.start_transcription_job(
    TranscriptionJobName="floci-demo",
    Media={"MediaFileUri": "s3://my-bucket/audio.mp3"},
    LanguageCode="en-US",
    MediaFormat="mp3",
)

job = client.get_transcription_job(TranscriptionJobName="floci-demo")
print(job["TranscriptionJob"]["TranscriptionJobStatus"])  # COMPLETED
```

## Related Docs

- [Services overview](index.md)
- [AWS CLI & SDK setup](../getting-started/aws-setup.md)
- [Environment variables](../configuration/environment-variables.md)

## Out of Scope

- Real speech-to-text processing or audio file parsing.
- Writing transcript JSON objects to S3.
- Persistent job or vocabulary storage across restarts.
- Streaming, medical transcription, call analytics, and other Transcribe APIs
  beyond the operations listed above.

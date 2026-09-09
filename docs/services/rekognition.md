# Rekognition

**Protocol:** JSON 1.1 (`X-Amz-Target: RekognitionService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectLabels` | Detect objects, scenes, and concepts in an image |
| `DetectFaces` | Detect faces in an image and their attributes |
| `DetectText` | Detect text in an image |
| `CompareFaces` | Compare a face in a source image with faces in a target image |
| `DetectModerationLabels` | Detect unsafe or inappropriate content in an image |
<!-- floci:actions:end -->

## Emulation Behavior

- **Stub responses:** every action returns a fixed, AWS-shaped response — no real
  image analysis is performed. `Image`/`SourceImage`/`TargetImage` content is
  accepted but never decoded, the same way Textract accepts `Document` without
  reading it.
- **Content-listing operations return one canned entry:** `DetectLabels` and
  `DetectText` always report a single stub label/text detection, matching the
  Textract stub's block-hierarchy convention.
- **Face/moderation operations return empty results:** `DetectFaces`,
  `CompareFaces`, and `DetectModerationLabels` always report nothing found
  (empty `FaceDetails`/`FaceMatches`/`ModerationLabels`) rather than fabricating
  biometric attributes or a moderation flag for content that was never analyzed
  — an honest "nothing detected" response, matching the Comprehend PII-detection
  stub's precedent.
- **Real input validation:** `Image` (or `SourceImage`/`TargetImage` for
  `CompareFaces`) is required, must be a JSON structure, and must specify
  `Bytes` (string-typed) or `S3Object` (structure-typed) — matching AWS's own
  modeling and rejecting wrong JSON types as `SerializationException`. This is
  protocol compatibility, not content analysis.
- **Intentional deviations:** `Bytes`'s content is not validated as
  well-formed base64 (the `S3Object` shape has no required members in AWS's
  own model, so its fields aren't validated either), and supplying both
  `Bytes` and `S3Object` is accepted rather than rejected — nothing in the
  Rekognition API model declares that combination invalid, and the stub
  never reads either field's content regardless.
- **Out of scope:** face-collection persistence (`CreateCollection`,
  `IndexFaces`, `SearchFaces`), custom-model training (`Projects`, `Datasets`,
  `ProjectVersions`), live video (`StreamProcessor`), async video jobs
  (`Start*`/`Get*` Celebrity/Content/Face/Label/Person/Segment/Text detection),
  and Face Liveness sessions are not implemented.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REKOGNITION_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

The default stub responses above are the same for every call. To exercise application
logic that branches on detection results, point `AI_MOCK_CONFIG` at a JSON file shared
across Rekognition, Textract, and Comprehend:

```json
{
  "rekognition": {
    "my-bucket/cat.jpg": {
      "DetectLabels": {
        "Labels": [{ "Name": "Cat", "Confidence": 97.2 }, { "Name": "Animal", "Confidence": 98.1 }],
        "LabelModelVersion": "1.0"
      }
    }
  }
}
```

The lookup key is **`"<Bucket>/<Name>"`** from `Image.S3Object` (or `SourceImage.S3Object`
for `CompareFaces` — `TargetImage` has no independent key in this scheme). A `Bytes`-backed
image has no such key, so mocking only applies to S3Object-based calls. The file is re-read
when its modification time changes, so it can be edited without restarting the emulator. A
missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to the default
stub.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rekognition detect-labels \
  --image '{"S3Object":{"Bucket":"my-bucket","Name":"photo.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

aws rekognition detect-text \
  --image '{"S3Object":{"Bucket":"my-bucket","Name":"photo.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL

aws rekognition compare-faces \
  --source-image '{"S3Object":{"Bucket":"my-bucket","Name":"source.jpg"}}' \
  --target-image '{"S3Object":{"Bucket":"my-bucket","Name":"target.jpg"}}' \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
RekognitionClient rekognition = RekognitionClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

DetectLabelsResponse response = rekognition.detectLabels(req -> req
    .image(img -> img.s3Object(s3 -> s3.bucket("my-bucket").name("photo.jpg"))));

System.out.println("Labels: " + response.labels());
```

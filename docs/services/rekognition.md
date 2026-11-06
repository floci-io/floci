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

- **Stub responses:** every action returns a fixed, AWS-shaped response. No image
  analysis is performed: what an image shows never changes the result.
- **Images are checked as Rekognition checks them:** an image Rekognition refuses is
  refused here, with Rekognition's error, before any response. Measured against
  Rekognition in us-east-2, for each operation and for both `Bytes` and `S3Object`:
    - Anything but a JPEG or PNG (WebP, GIF, other bytes) is refused with
      `InvalidImageFormatException`, and so is a cut-off or hollow file the way Rekognition
      judges it: a PNG without an `IDAT` or `IEND` chunk, or a JPEG cut inside its headers or
      whose frame or scan header is shorter than its component count requires. A JPEG whose
      headers are complete through the start-of-scan header is accepted however much of the
      image data after it is missing.
    - A JPEG with stray bytes between its segments is accepted, as Rekognition accepts it.
    - `Bytes` that are not base64 are refused with `SerializationException`; base64
      without its `=` padding is accepted.
    - `Bytes` that are empty or longer than 5,242,880 bytes are refused with
      `ValidationException`, naming the member (`image.bytes`, `sourceImage.bytes`,
      `targetImage.bytes`). For `CompareFaces` both images' lengths are checked before
      either image is read, and every violation is reported in one error.
    - An `S3Object` that cannot be read (a missing bucket or object, or a bucket in
      another region than the request) is refused with `InvalidS3ObjectException`. A bucket
      created without a location constraint is in the default region. The object is read
      from Floci's S3, so it has to be uploaded first; only as much of it as its format
      needs is read, so a large object is not loaded into memory.
    - A side over 10,000 pixels is refused with `ImageTooLargeException` by
      `DetectModerationLabels` and `DetectLabels`, and with
      `InvalidImageFormatException` by the other operations.
    - Two documented limits are not enforced, because Rekognition does not enforce them:
      images under 80 pixels are accepted, and an S3 object over 15 MB is accepted.
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
- **Both `Bytes` and `S3Object`:** supplying both is accepted rather than rejected,
  since nothing in the Rekognition API model declares that combination invalid. The
  bytes are what is checked.
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

The object named by the key has to exist in Floci's S3 and be an image Rekognition
accepts: the image is checked before the mock is looked up, so a call naming an object
that was never uploaded fails with `InvalidS3ObjectException`, as it does on AWS.

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

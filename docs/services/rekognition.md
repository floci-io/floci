# Rekognition

**Protocol:** JSON 1.1 (`X-Amz-Target: RekognitionService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateCollection` | Create an account- and region-scoped face collection |
| `DeleteCollection` | Delete a collection and all its indexed face records |
| `DescribeCollection` | Return collection metadata, face count, and model version |
| `ListCollections` | List collections with pagination |
| `IndexFaces` | Store configured face detections in a collection |
| `ListFaces` | List indexed face metadata with pagination and face ID filtering |
| `DeleteFaces` | Remove indexed face records |
| `SearchFaces` | Search a collection using an indexed face ID |
| `SearchFacesByImage` | Search a collection using the largest configured face in an image |
| `DetectLabels` | Detect objects, scenes, and concepts in an image |
| `DetectFaces` | Detect faces in an image and their attributes |
| `DetectText` | Detect text in an image |
| `CompareFaces` | Compare a face in a source image with faces in a target image |
| `DetectModerationLabels` | Detect unsafe or inappropriate content in an image |
<!-- floci:actions:end -->

## Emulation Behavior

- **Stub image analysis:** image-analysis actions return fixed, AWS-shaped responses. No image
  analysis is performed: what an image shows never changes the result.
- **Images are checked as Rekognition checks them:** an image Rekognition refuses is
  refused here, with Rekognition's error, before any response. Measured against
  Rekognition in us-east-2 for the synchronous image-analysis operations and for both
  `Bytes` and `S3Object`. Collection image operations reuse this validation:
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
- **Out of scope:** custom-model training (`Projects`, `Datasets`,
  `ProjectVersions`), live video (`StreamProcessor`), async video jobs
  (`Start*`/`Get*` Celebrity/Content/Face/Label/Person/Segment/Text detection),
  and Face Liveness sessions are not implemented.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REKOGNITION_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_STORAGE_SERVICES_REKOGNITION_MODE` | global storage mode | Collection and face metadata storage: `memory`, `persistent`, `hybrid`, or `wal` |
| `FLOCI_STORAGE_SERVICES_REKOGNITION_FLUSH_INTERVAL_MS` | `5000` | Metadata flush interval in hybrid mode |
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

## Face Collections

Collections and their indexed face records are isolated by account and region. They use
Floci's configured storage backend, survive restarts in durable modes, and are removed by
emulator reset. Deleting a collection removes its faces. Creating an existing collection
returns `ResourceAlreadyExistsException`; operations on a missing collection return
`ResourceNotFoundException`. Collection names are case-sensitive.

Face records are stored separately from collection metadata. Durable modes partition faces
by collection into 32 shards, so indexing and deleting faces write only affected shards
and leave other collections untouched. WAL mode journals individual face changes.

`IndexFaces` stores the `FaceDetails` from the same `DetectFaces` mock configuration used
by synchronous detection. Without a matching mock, it returns empty `FaceRecords` and
`UnindexedFaces`, consistent with the default empty face detection. For example:

```json
{
  "rekognition": {
    "my-bucket/person.jpg": {
      "DetectFaces": {
        "FaceDetails": [{
          "BoundingBox": { "Width": 0.4, "Height": 0.4, "Left": 0.1, "Top": 0.1 },
          "Confidence": 99.9
        }]
      }
    }
  }
}
```

Upload a valid JPEG or PNG at that S3 key before indexing. Face IDs and image IDs are
generated by Floci. Re-indexing the same image reference, face position, and
`ExternalImageId` returns the existing records. `MaxFaces` selects the largest bounding
boxes, up to 100; remaining detections appear in `UnindexedFaces` with reason
`EXCEEDS_MAX_FACES`. `DetectionAttributes` and `QualityFilter` do not change configured
detections; supply the desired attributes and quality in the mock file. The collection
model version is an emulated `7.0`, independent of the synchronous stub model version.

`ListCollections` and `ListFaces` accept `MaxResults` and opaque `NextToken` cursors.
Results are ordered by collection ID or face ID. `MaxResults: 0` returns an empty page
with a continuation token when results remain; resuming with a positive page size returns
those results. Tokens are scoped to the account, region, operation, and filters, remain
within the 255-character limit, and expire when the emulator restarts.
`ListFaces` also supports `FaceIds` filtering. User association actions are not implemented,
so `UserCount` is zero and a `UserId` filter returns no faces. Creation-time tags are
retained; collection tagging APIs are not implemented.

Search is deterministic and does no biometric analysis. A match requires the same image
reference and bounding-box position, and returns similarity `100`. S3 references include
bucket, key, and optional version; equal bytes at different S3 keys are distinct. Raw image
bytes are never persisted. `SearchFaces` excludes the source face itself, so indexing the
same reference under another `ExternalImageId` allows a match. `SearchFacesByImage` uses
the largest configured detection and returns `InvalidParameterException` if there are no
configured faces. Both search operations enforce `MaxFaces` and validate
`FaceMatchThreshold`; every deterministic match meets any valid threshold from 0 to 100.

These semantics support local collection lifecycle and application workflow tests.
They do not reproduce AWS's face recognition model or quality filtering.

The AWS action contracts are documented in the
[Rekognition API Reference](https://docs.aws.amazon.com/rekognition/latest/APIReference/API_CreateCollection.html)
and [face collection guide](https://docs.aws.amazon.com/rekognition/latest/dg/collections.html).

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws rekognition create-collection --collection-id tenant-1
aws rekognition index-faces --collection-id tenant-1 \
  --external-image-id person-1 \
  --image '{"S3Object":{"Bucket":"my-bucket","Name":"person.jpg"}}'
aws rekognition list-faces --collection-id tenant-1
aws rekognition delete-collection --collection-id tenant-1

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

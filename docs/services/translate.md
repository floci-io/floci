# Translate

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSShineFrontendService_20170701.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `TranslateText` | Translate a text string from a source language to a target language |
| `TranslateDocument` | Translate a document's content from a source language to a target language |
| `ListLanguages` | List the languages Translate supports |
<!-- floci:actions:end -->

## Emulation Behavior

- **Stub responses:** `TranslateText` always returns a fixed placeholder translated
  string, no real machine translation is performed. `TranslateDocument` returns the
  input document's content unchanged — its content is accepted but never decoded,
  the same "accept but don't decode" pattern floci already uses for Textract's
  `Document` and Rekognition's `Image`. `ListLanguages` returns a fixed catalog.
- **Real input validation:** `Text` (`TranslateText`) or `Document.Content` +
  `Document.ContentType` (`TranslateDocument`), and `SourceLanguageCode` +
  `TargetLanguageCode` (both), are required and validated against a fixed
  supported-language catalog — this is protocol compatibility, not translation
  logic. `SourceLanguageCode` accepts `auto`, which always resolves to `en` in the
  response (real Translate would call Amazon Comprehend to detect it).
- **Fixed language catalog:** `en`, `es`, `fr`, `de`, `it`, `pt`, `ar`, `hi`, `ja`,
  `ko`, `zh`, `zh-TW` — the same set Comprehend accepts for its general
  `LanguageCode` parameter. Real Translate supports a much larger set (~75
  languages); an unrecognized or unsupported code returns
  `UnsupportedLanguagePairException`, matching AWS's real error for language pairs
  it can't translate between.
- **Out of scope:** custom terminology management (`ImportTerminology`,
  `GetTerminology`, `DeleteTerminology`, `ListTerminologies`), parallel data
  resources (`CreateParallelData`, `GetParallelData`, `ListParallelData`,
  `UpdateParallelData`, `DeleteParallelData`), the async batch job surface
  (`StartTextTranslationJob`, `StopTextTranslationJob`,
  `DescribeTextTranslationJob`, `ListTextTranslationJobs`), and tagging
  (`TagResource`, `UntagResource`, `ListTagsForResource`) are not implemented —
  each needs persisted resource state, unlike the three stateless sync actions
  above.
- **Intentional deviation:** real Translate enforces a maximum input size on `Text`
  (10,000 bytes) and `Document` (100 KB), and returns `TextSizeLimitExceededException`
  for oversized text. Neither limit is enforced here — any non-empty `Text` or
  well-formed `Document` is accepted regardless of size.
- **Intentional deviation:** `ListLanguages`' `DisplayLanguageCode` controls which
  language the returned `LanguageName` strings are localized into on real AWS. This is
  not implemented — `LanguageName` is always English regardless of `DisplayLanguageCode`,
  and any string value is accepted without validation against
  `UnsupportedDisplayLanguageCodeException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TRANSLATE_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

The default `TranslateText` stub above is the same for every call. To exercise
application logic that reads the translated text, point `AI_MOCK_CONFIG` at a JSON
file shared across Translate, Comprehend, Textract, and Rekognition:

```json
{
  "translate": {
    "Hello, world!": {
      "TranslateText": { "TranslatedText": "¡Hola, mundo!", "SourceLanguageCode": "en", "TargetLanguageCode": "es" }
    }
  }
}
```

The lookup key is the **exact `Text` value** sent in the request. The file is re-read
when its modification time changes, so it can be edited without restarting the
emulator. A missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall
back to the default stub — mocking is opt-in and never breaks a call that isn't
using it. `TranslateDocument` is always `Content`-backed (Translate's `Document`
shape has no S3 variant), so it has no natural lookup key and is not mockable, same
as a `Bytes`-backed Textract/Rekognition input.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws translate translate-text \
  --text "Hello, world!" \
  --source-language-code en \
  --target-language-code es \
  --endpoint-url $AWS_ENDPOINT_URL

aws translate list-languages \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
TranslateClient translate = TranslateClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

TranslateTextResponse response = translate.translateText(req -> req
    .text("Hello, world!")
    .sourceLanguageCode("en")
    .targetLanguageCode("es"));

System.out.println("Translated: " + response.translatedText());
```

# Translate

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSShineFrontendService_20170701.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `TranslateText` | Translate a UTF-8 text string between two languages |
| `TranslateDocument` | Translate a document (≤100 KB) supplied as a base64 blob |
| `ListLanguages` | List the language codes and names Floci recognizes |
<!-- floci:actions:end -->

## Emulation Behavior

- **No machine translation:** `TranslateText` returns the request `Text` verbatim as
  `TranslatedText`, and `TranslateDocument` returns the request `Document.Content` bytes
  unchanged as `TranslatedDocument.Content`. To exercise logic that branches on a specific
  translation, use the mock config below.
- **Language detection:** a `SourceLanguageCode` of `auto` is accepted and reported back
  as `en` (the stub's fixed "detected" language); no Comprehend call is made.
- **Real input validation:** `Text` / `Document.Content` / `Document.ContentType` and both
  language codes are required. `SourceLanguageCode` must be `auto` or a code from the
  catalog returned by `ListLanguages`; `TargetLanguageCode` must be a catalog code. An
  unknown code returns `UnsupportedLanguagePairException`.
  - `TranslateText` rejects a `Text` longer than 10,000 UTF-8 bytes with
    `TextSizeLimitExceededException`, matching AWS. Any language pair is allowed
    (`TranslateText` is any-to-any).
  - `TranslateDocument` requires an **English pivot**: either the source or the target
    language must resolve to `en`, otherwise it returns `UnsupportedLanguagePairException`.
    It also rejects a `ContentType` other than `text/plain`, `text/html`, or the
    Word (`.docx`) MIME type, a `Content` that is not valid base64, and a decoded
    document over 100 KB, all with `InvalidRequestException` (`TranslateDocument` does
    not model `TextSizeLimitExceededException`).
- **Fixed catalog:** `ListLanguages` returns a curated ~35-language subset of Translate's
  full list, enough to drive an i18n pipeline. `MaxResults` / `NextToken` are accepted
  but ignored (the whole catalog fits in one page, so no `NextToken` is returned).
  `DisplayLanguageCode` is validated against the AWS enum
  (`de`, `en`, `es`, `fr`, `it`, `ja`, `ko`, `pt`, `zh`, `zh-TW`) but every `LanguageName`
  is returned in English regardless.
- **Out of scope:** custom terminology, parallel data, asynchronous batch translation jobs
  (`StartTextTranslationJob` and friends), and tagging, all needing persistent state.
- **Deviation:** `ListLanguages` always returns `LanguageName`s in English regardless of
  `DisplayLanguageCode` (real Translate localizes them), and the `Formality` /
  `Profanity` / `Brevity` translation settings are accepted but ignored.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TRANSLATE_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file; see "Mock Responses" below |

## Mock Responses

To return a real translated string instead of the echoed input, point `AI_MOCK_CONFIG` at a
JSON file shared across Translate, Comprehend, Textract, and Rekognition:

```json
{
  "translate": {
    "Hello world": {
      "TranslateText": { "TranslatedText": "Hola mundo", "SourceLanguageCode": "en", "TargetLanguageCode": "es" }
    }
  }
}
```

The lookup key is the **exact `Text` value** sent in the request, so only `TranslateText`
can be mocked; `TranslateDocument` carries a binary payload with no stable key. The file is
re-read when its modification time changes, so it can be edited without restarting the
emulator. A missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to
the echoed-input default. Mocking is opt-in and never breaks a call that isn't using it.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws translate translate-text \
  --text "Floci makes local AWS testing painless" \
  --source-language-code en \
  --target-language-code fr \
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
    .text("Floci makes local AWS testing painless")
    .sourceLanguageCode("en")
    .targetLanguageCode("fr"));

System.out.println("Translated: " + response.translatedText());
```

# Comprehend

**Protocol:** JSON 1.1 (`X-Amz-Target: Comprehend_20171127.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectSentiment` | Detect the overall sentiment of a text (POSITIVE/NEGATIVE/NEUTRAL/MIXED) |
| `DetectKeyPhrases` | Detect key noun-phrases in a text |
| `DetectDominantLanguage` | Detect the dominant language of a text |
| `DetectPiiEntities` | Detect personally identifiable information (PII) in a text |
| `ContainsPiiEntities` | Report whether a text contains PII, without locating it |
<!-- floci:actions:end -->

## Emulation Behavior

- **Stub responses:** every action returns a fixed, AWS-shaped response — no real NLP/ML
  is performed. `DetectSentiment` always returns `NEUTRAL`, `DetectKeyPhrases` always
  returns one stub phrase, `DetectDominantLanguage` always reports `en`, and
  `DetectPiiEntities`/`ContainsPiiEntities` always report no PII found.
- **Real input validation:** `Text` and `LanguageCode` (where applicable) are still
  required and `LanguageCode` is validated against Comprehend's supported set —
  this is protocol compatibility, not content analysis. `DetectSentiment` and
  `DetectKeyPhrases` accept the general set (`en`, `es`, `fr`, `de`, `it`, `pt`,
  `ar`, `hi`, `ja`, `ko`, `zh`, `zh-TW`); `DetectPiiEntities` and
  `ContainsPiiEntities` accept only `en`/`es`, matching AWS's narrower support
  for PII detection.
- **Out of scope:** the async job/model-training surface (`DocumentClassifier`,
  `EntityRecognizer`, `Flywheel`, `Dataset`, `Endpoint` operations), `DetectEntities`,
  and `DetectSyntax` are not implemented.
- **Intentional deviation:** real Comprehend enforces a maximum input size on `Text`
  and returns `TextSizeLimitExceededException` when exceeded. This is not enforced
  here — any non-empty `Text` is accepted regardless of length.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_COMPREHEND_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws comprehend detect-sentiment \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-key-phrases \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-dominant-language \
  --text "Floci makes local AWS testing painless" \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-pii-entities \
  --text "My SSN is 123-45-6789" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
ComprehendClient comprehend = ComprehendClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

DetectSentimentResponse response = comprehend.detectSentiment(req -> req
    .text("Floci makes local AWS testing painless")
    .languageCode("en"));

System.out.println("Sentiment: " + response.sentiment());
```

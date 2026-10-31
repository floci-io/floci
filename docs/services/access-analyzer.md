# IAM Access Analyzer

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the analyzer lifecycle and basic policy validation used by local governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ValidatePolicy` | - |
| `ListAnalyzers` | - |
| `CreateAnalyzer` | - |
| `DeleteAnalyzer` | - |
| `GetAnalyzer` | - |
<!-- floci:actions:end -->

Analyzer state is account and Region scoped and persisted through `StorageFactory`.

## Policy validation

`ValidatePolicy` accepts a JSON policy string at `POST /policy/validation`. It does not require an analyzer, persist the submitted policy, or change permissions. It supports `IDENTITY_POLICY`, `RESOURCE_POLICY`, `SERVICE_CONTROL_POLICY`, and `RESOURCE_CONTROL_POLICY` requests. With IAM enforcement enabled, callers need `access-analyzer:ValidatePolicy` on `Resource: "*"`.

The local checks cover malformed JSON, repeated object keys, document and statement shapes, required elements, mutually exclusive elements, policy versions, duplicate statement identifiers, principal key names, condition operator names, and selected empty-value suggestions. Resource policies accept the API's supported `validatePolicyResourceType` values; the IAM trust-policy type permits statements without a resource element. Generic resource policies containing trust actions use the same trust-policy context.

Findings include an issue code, severity, explanation, documentation link, JSON path, and source span. Spans refer to the original policy string, with zero-based offsets and columns, one-based lines, and an exclusive end. Malformed policy JSON and repeated object keys return `JSON_SYNTAX_ERROR` findings in a successful response. Repeated-key findings point to the repeated key in the submitted text. Invalid request members return `ValidationException`; malformed outer request JSON returns `SerializationException`.

Use `maxResults` and `nextToken` query parameters to page through findings. The default page size is 100 and the supported range is 1 to 1000. Tokens are opaque, bound to the policy document, policy type, resource type, and locale, and expire when Floci restarts. The page size may change between requests.

These are basic syntax and grammar checks, not the full AWS policy analyzer. Floci does not validate action existence, service-specific resource or condition semantics, or security best practices. An empty findings list only means that the implemented checks found no issue. Accepted `locale` values currently return English explanations. These limits also apply when a resource type is supplied.

## AWS-compatible failures

Analyzer names, types, tags, pagination, duplicate names, and local analyzer quotas are validated. Floci returns `ValidationException`, `ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException` for deterministic conditions represented by local state.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not inject provider-side failures that cannot be derived from the request or emulator state.

See the [IAM Access Analyzer API Reference](https://docs.aws.amazon.com/access-analyzer/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCESSANALYZER_ENABLED` | `true` | Enable or disable IAM Access Analyzer |

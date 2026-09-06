# IAM Access Analyzer

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci supports the analyzer lifecycle used by local governance workflows.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListAnalyzers` | - |
| `CreateAnalyzer` | - |
| `DeleteAnalyzer` | - |
<!-- floci:actions:end -->

Analyzer state is account and Region scoped and persisted through `StorageFactory`.

## AWS-compatible failures

Analyzer names, types, tags, pagination, duplicate names, and local analyzer quotas are validated. Floci returns `ValidationException`, `ConflictException`, `ResourceNotFoundException`, and `ServiceQuotaExceededException` for deterministic conditions represented by local state.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not inject provider-side failures that cannot be derived from the request or emulator state.

See the [IAM Access Analyzer API Reference](https://docs.aws.amazon.com/access-analyzer/latest/APIReference/Welcome.html).

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ACCESSANALYZER_ENABLED` | `true` | Enable or disable IAM Access Analyzer |

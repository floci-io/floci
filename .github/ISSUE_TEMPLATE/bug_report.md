---
name: Bug report
about: An AWS API call returns wrong behavior or an error
title: '[BUG] '
labels: bug
assignees: ''
---

## Service

<!-- e.g. SQS, DynamoDB, Lambda -->

## AWS API Action

<!-- e.g. SendMessage, PutItem, CreateFunction -->

## Expected behavior

<!-- What the real AWS SDK/CLI returns -->

## Actual behavior

<!-- What Floci returns — include the full error message or response body -->

## Reproduction

```bash
# Minimal AWS CLI or SDK snippet that triggers the issue
aws --endpoint-url http://localhost:4566 sqs send-message ...
```

## Environment

- Floci version / image tag:
- Java SDK version (if applicable):
- How you're running Floci (Docker / native / `mvn quarkus:dev`):
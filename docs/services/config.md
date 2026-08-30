# AWS Config

**Protocol:** JSON 1.1 (`X-Amz-Target: StarlingDoveService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

### Config Rules

| Action | Description |
|---|---|
| `PutConfigRule` | Create or update a config rule (full rule shape round-trips: description, scope, source details, input parameters, execution frequency, evaluation modes) |
| `DeleteConfigRule` | Delete a config rule and its evaluation results |
| `DescribeConfigRules` | List config rules, optionally filtered by name, paginated via `NextToken` |
| `DescribeConfigRuleEvaluationStatus` | Get evaluation status for config rules |
| `StartConfigRulesEvaluation` | Trigger evaluation for config rules |

### Compliance & Evaluations

| Action | Description |
|---|---|
| `PutEvaluations` | Report evaluation results for a config rule (supports `TestMode`) |
| `PutExternalEvaluation` | Report a single external evaluation for a config rule |
| `DeleteEvaluationResults` | Delete all evaluation results for a config rule |
| `DescribeComplianceByConfigRule` | Get rule-level compliance aggregated from reported evaluations |
| `DescribeComplianceByResource` | Get resource-level compliance aggregated across rules |
| `GetComplianceDetailsByConfigRule` | List the evaluation results recorded for a rule |
| `GetComplianceDetailsByResource` | List the evaluation results recorded for a resource |
| `GetComplianceSummaryByConfigRule` | Count compliant and non-compliant rules |
| `GetComplianceSummaryByResourceType` | Count compliant and non-compliant resources, grouped by type |

### Configuration Recorder

| Action | Description |
|---|---|
| `PutConfigurationRecorder` | Create or update a configuration recorder |
| `DescribeConfigurationRecorders` | List configuration recorders |
| `DeleteConfigurationRecorder` | Delete a configuration recorder |
| `StartConfigurationRecorder` | Start recording configuration changes |
| `StopConfigurationRecorder` | Stop recording configuration changes |
| `DescribeConfigurationRecorderStatus` | Get the status of configuration recorders |

### Delivery Channel

| Action | Description |
|---|---|
| `PutDeliveryChannel` | Create or update a delivery channel |
| `DescribeDeliveryChannels` | List delivery channels |
| `DeleteDeliveryChannel` | Delete a delivery channel (fails while the recorder is running) |

### Retention Configuration

| Action | Description |
|---|---|
| `PutRetentionConfiguration` | Set the retention period (30-2557 days) |
| `DescribeRetentionConfigurations` | List retention configurations |
| `DeleteRetentionConfiguration` | Delete the retention configuration |

### Conformance Packs

| Action | Description |
|---|---|
| `PutConformancePack` | Create or update a conformance pack |
| `DeleteConformancePack` | Delete a conformance pack |
| `DescribeConformancePacks` | List conformance packs, paginated via `Limit`/`NextToken` |
| `DescribeConformancePackStatus` | Get the deployment status of conformance packs |

### Tagging

| Action | Description |
|---|---|
| `TagResource` | Add tags to a Config resource |
| `UntagResource` | Remove tags from a Config resource |
| `ListTagsForResource` | List tags on a Config resource |

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CONFIGSERVICE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a config rule
aws configservice put-config-rule --config-rule '{
  "ConfigRuleName": "s3-bucket-versioning",
  "Description": "Checks that versioning is enabled",
  "Scope": {"ComplianceResourceTypes": ["AWS::S3::Bucket"]},
  "Source": {
    "Owner": "AWS",
    "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
  }
}'

# List config rules
aws configservice describe-config-rules

# Report evaluation results (the ResultToken is the rule name, see Deviations)
aws configservice put-evaluations \
  --result-token s3-bucket-versioning \
  --evaluations '[{
    "ComplianceResourceType": "AWS::S3::Bucket",
    "ComplianceResourceId": "my-bucket",
    "ComplianceType": "NON_COMPLIANT",
    "Annotation": "Versioning is suspended",
    "OrderingTimestamp": 1700000000.0
  }]'

# Rule compliance now reflects the reported evaluations
aws configservice describe-compliance-by-config-rule \
  --config-rule-names s3-bucket-versioning

# Inspect per-resource evaluation results
aws configservice get-compliance-details-by-config-rule \
  --config-rule-name s3-bucket-versioning

# Set the retention period
aws configservice put-retention-configuration --retention-period-in-days 365

# Create a configuration recorder
aws configservice put-configuration-recorder --configuration-recorder '{
  "name": "default",
  "roleARN": "arn:aws:iam::012345678901:role/config-role",
  "recordingGroup": {
    "allSupported": true,
    "includeGlobalResourceTypes": true
  }
}'

# Start recording
aws configservice start-configuration-recorder --configuration-recorder-name default

# Check recorder status
aws configservice describe-configuration-recorder-status

# Create a conformance pack
aws configservice put-conformance-pack \
  --conformance-pack-name my-pack \
  --template-body "Resources: {}"

# List conformance packs
aws configservice describe-conformance-packs

# Tag a resource
aws configservice tag-resource \
  --resource-arn arn:aws:config:us-east-1:000000000000:config-rule/config-rule-abc123 \
  --tags Key=env,Value=dev

# Delete a config rule
aws configservice delete-config-rule --config-rule-name s3-bucket-versioning
```

## Deviations

!!! note
    Compliance is driven entirely by evaluations reported through `PutEvaluations` and
    `PutExternalEvaluation` — Floci does not record resource configurations or run rule
    evaluations itself. Until an evaluation is reported, compliance returns
    `INSUFFICIENT_DATA`.

    - **ResultToken**: real AWS hands custom rules an opaque token inside the evaluation
      event. Floci has no evaluation event, so `PutEvaluations` expects the config rule
      name as the token (a `<rule-name>:<suffix>` form is also accepted).
    - `StartConfigRulesEvaluation` marks the rule as invoked but does not call the rule's
      Lambda function.
    - `FirstActivatedTime` and invocation timestamps are runtime state and are not
      persisted across restarts.
    - `DescribeConfigRules` and `DescribeComplianceByConfigRule` use a fixed page size
      of 25.
    - Configuration recorders and delivery channels are stored and validated, but no
      configuration items or snapshots are produced.
    - Conformance pack status is always `CREATE_SUCCESSFUL`; pack templates are stored,
      not evaluated.

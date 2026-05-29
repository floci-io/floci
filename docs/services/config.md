# AWS Config

**Protocol:** JSON 1.1 (`X-Amz-Target: StarlingDoveService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

### Config Rules

| Action | Description |
|---|---|
| `PutConfigRule` | Create or update a config rule |
| `DeleteConfigRule` | Delete a config rule |
| `DescribeConfigRules` | List config rules, optionally filtered by name |
| `DescribeComplianceByConfigRule` | Get compliance summary for config rules |
| `DescribeConfigRuleEvaluationStatus` | Get evaluation status for config rules |
| `StartConfigRulesEvaluation` | Trigger evaluation for config rules |

### Configuration Recorder

| Action | Description |
|---|---|
| `PutConfigurationRecorder` | Create or update a configuration recorder |
| `DescribeConfigurationRecorders` | List configuration recorders |
| `StartConfigurationRecorder` | Start recording configuration changes |
| `StopConfigurationRecorder` | Stop recording configuration changes |
| `DescribeConfigurationRecorderStatus` | Get the status of configuration recorders |

### Delivery Channel

| Action | Description |
|---|---|
| `PutDeliveryChannel` | Create or update a delivery channel |
| `DescribeDeliveryChannels` | List delivery channels |

### Conformance Packs

| Action | Description |
|---|---|
| `PutConformancePack` | Create or update a conformance pack |
| `DeleteConformancePack` | Delete a conformance pack |
| `DescribeConformancePacks` | List conformance packs |
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
  "Source": {
    "Owner": "AWS",
    "SourceIdentifier": "S3_BUCKET_VERSIONING_ENABLED"
  }
}'

# List config rules
aws configservice describe-config-rules

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

!!! note
    Compliance status always returns `INSUFFICIENT_DATA` since Floci does not perform actual resource evaluation. Config rules, recorders, and conformance packs are stored and returned correctly, but no real configuration recording or compliance checking takes place.

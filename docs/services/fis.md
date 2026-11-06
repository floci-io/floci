# AWS Fault Injection Service (FIS)

- **Protocol:** REST JSON
- **Endpoint:** `http://localhost:4566/`
- **Credential scope:** `fis`

Floci implements the AWS FIS management API for local SDK, CLI, infrastructure-as-code, and resilience-workflow tests. Templates, experiments, target-account configurations, tags, and safety-lever state are isolated by account and region and use the configured Floci storage mode.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateExperimentTemplate` | Creates a template (`POST /experimentTemplates`) |
| `CreateTargetAccountConfiguration` | Adds an account to a multi-account template (`POST /experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}`) |
| `DeleteExperimentTemplate` | Deletes a template (`DELETE /experimentTemplates/{id}`) |
| `DeleteTargetAccountConfiguration` | Removes a template target-account configuration (`DELETE /experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}`) |
| `GetAction` | Gets an AWS FIS action definition (`GET /actions/{id}`) |
| `GetExperiment` | Gets experiment configuration and state (`GET /experiments/{id}`) |
| `GetExperimentTargetAccountConfiguration` | Gets an experiment target-account snapshot (`GET /experiments/{experimentId}/targetAccountConfigurations/{accountId}`) |
| `GetExperimentTemplate` | Gets a template (`GET /experimentTemplates/{id}`) |
| `GetSafetyLever` | Gets safety-lever state (`GET /safetyLevers/{id}`) |
| `GetTargetAccountConfiguration` | Gets a template target-account configuration (`GET /experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}`) |
| `GetTargetResourceType` | Gets a target resource-type definition (`GET /targetResourceTypes/{resourceType}`) |
| `ListActions` | Lists AWS FIS action definitions (`GET /actions`) |
| `ListExperimentResolvedTargets` | Lists resources resolved for an experiment target (`GET /experiments/{experimentId}/resolvedTargets`) |
| `ListExperimentTargetAccountConfigurations` | Lists target-account snapshots used by an experiment (`GET /experiments/{experimentId}/targetAccountConfigurations`) |
| `ListExperimentTemplates` | Lists experiment-template summaries (`GET /experimentTemplates`) |
| `ListExperiments` | Lists experiments, optionally filtered by template (`GET /experiments`) |
| `ListTargetAccountConfigurations` | Lists template target-account configurations (`GET /experimentTemplates/{experimentTemplateId}/targetAccountConfigurations`) |
| `ListTargetResourceTypes` | Lists target resource types (`GET /targetResourceTypes`) |
| `StartExperiment` | Starts a locally simulated experiment (`POST /experiments`) |
| `StopExperiment` | Stops an experiment (`DELETE /experiments/{id}`) |
| `UpdateExperimentTemplate` | Updates supplied template fields (`PATCH /experimentTemplates/{id}`) |
| `UpdateSafetyLeverState` | Engages or disengages a safety lever (`PATCH /safetyLevers/{id}/state`) |
| `UpdateTargetAccountConfiguration` | Updates a target-account role or description (`PATCH /experimentTemplates/{experimentTemplateId}/targetAccountConfigurations/{accountId}`) |
| `ListTagsForResource` | Lists tags on an FIS resource (`GET /tags/{resourceArn}`) |
| `TagResource` | Adds or replaces tags on an FIS resource (`POST /tags/{resourceArn}`) |
| `UntagResource` | Removes tag keys from an FIS resource (`DELETE /tags/{resourceArn}`) |
<!-- floci:actions:end -->

List operations accept AWS-compatible `maxResults` and `nextToken` parameters where the AWS API defines them. `ListExperimentTargetAccountConfigurations` follows AWS and accepts `nextToken` without a `maxResults` parameter.

## Local Experiment Semantics

Experiment execution is a safe local simulation. Starting or stopping an experiment creates and updates AWS-shaped FIS control-plane state, including action state, resolved targets, timestamps, target-account snapshots, and safety-lever effects. Floci does **not** inject latency, terminate resources, interrupt networking, or otherwise mutate resources in other emulated services.

Explicit resource ARNs are sampled at random according to the target selection mode and recorded as locally resolved targets. Floci does not query other emulated services to resolve tag, filter, or parameter selectors, so those selectors resolve to an empty set. The template's `emptyTargetResolutionMode` then follows AWS behavior: `fail` fails the experiment, while `skip` skips actions whose selector-based targets are empty. Targets defined by unique identifiers such as ARNs cannot be skipped and fail the experiment if their selection resolves to nothing. Selector criteria are never reported as if they were resolved resources.

The action and target-resource catalogs let clients discover and validate AWS-defined action IDs and target shapes. Floci validates the Smithy-modeled string shapes, known and required parameters, target slots and resource types, plus documented selector restrictions. It does not yet enforce every action-specific parameter range, enum, or conditional rule that AWS defines only in the FIS actions user guide. The catalogs are metadata used by the local simulation, not adapters that execute the corresponding faults.

Regional, account-scoped limits mirror the default AWS FIS quotas for 500 experiment templates, 5 active experiments, 20 actions per template, 10 actions that can run in parallel, 5 stop conditions, and 40 target-account configurations per template. Quota violations use the modeled `ServiceQuotaExceededException` response.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_FIS_ENABLED` | `true` | Enable or disable AWS FIS |
| `FLOCI_STORAGE_SERVICES_FIS_MODE` | *(inherits global)* | Optional FIS storage-mode override |
| `FLOCI_STORAGE_SERVICES_FIS_FLUSH_INTERVAL_MS` | `5000` | Hybrid storage flush interval in milliseconds |

Unless the FIS-specific override is set, FIS state follows the global `FLOCI_STORAGE_MODE` setting. Persistent, hybrid, and write-ahead-log modes restore FIS resources after restart.

## Example

Create `template.json`:

```json
{
  "description": "Local stop-instance experiment",
  "roleArn": "arn:aws:iam::000000000000:role/fis-role",
  "stopConditions": [
    { "source": "none" }
  ],
  "targets": {
    "instances": {
      "resourceType": "aws:ec2:instance",
      "selectionMode": "ALL",
      "resourceArns": [
        "arn:aws:ec2:us-east-1:000000000000:instance/i-local"
      ]
    }
  },
  "actions": {
    "stopInstances": {
      "actionId": "aws:ec2:stop-instances",
      "targets": {
        "Instances": "instances"
      }
    }
  },
  "tags": {
    "environment": "test"
  }
}
```

Then use the AWS CLI against Floci:

```bash
export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_DEFAULT_REGION=us-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test

aws fis create-experiment-template --cli-input-json file://template.json
aws fis list-experiment-templates
aws fis start-experiment --experiment-template-id <template-id>
aws fis list-experiments
aws fis get-safety-lever --id default
```

The commands exercise the AWS-compatible management workflow only; the example EC2 instance is not stopped.

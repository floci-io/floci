# Amazon Data Lifecycle Manager

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/policies` (SigV4 service `dlm`)

Floci emulates the DLM management plane for EBS snapshot and AMI lifecycle policy
configuration. Policies, nested policy details, and resource tags are persisted and
isolated by account and region.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateLifecyclePolicy` | Creates an EBS snapshot or AMI lifecycle policy configuration. |
| `GetLifecyclePolicy` | Returns one lifecycle policy and its nested details. |
| `GetLifecyclePolicies` | Lists lifecycle policies with the supported DLM filters. |
| `UpdateLifecyclePolicy` | Updates the supplied members of a lifecycle policy. |
| `DeleteLifecyclePolicy` | Deletes a lifecycle policy configuration. |
| `ListTagsForResource` | Lists the tags attached to a lifecycle policy. |
| `TagResource` | Adds or replaces tags on a lifecycle policy. |
| `UntagResource` | Removes selected tags from a lifecycle policy. |
<!-- floci:actions:end -->

`GetLifecyclePolicies` supports policy id, state, resource type, target-tag,
schedule-tag, and default-policy filters. `UpdateLifecyclePolicy` changes only the
members supplied by the caller and preserves the rest of the policy.

Policy timestamps use ISO 8601, as required by the DLM SDK model. Tags supplied on
create are returned by `GetLifecyclePolicy` and can also be managed with the resource
tagging actions.

## Not emulated

Floci stores DLM policy configuration but does not execute schedules. It does not
create EBS snapshots or AMIs, copy them across Regions, or enforce retention and
exclusion rules. The nested `PolicyDetails` document is retained so SDK and
infrastructure-as-code configuration can round-trip without claiming that backup
jobs have run.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DLM_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

policy_id=$(aws dlm create-lifecycle-policy \
  --execution-role-arn arn:aws:iam::000000000000:role/dlm \
  --description daily \
  --state ENABLED \
  --policy-details '{"PolicyType":"EBS_SNAPSHOT_MANAGEMENT","ResourceTypes":["VOLUME"],"TargetTags":[{"Key":"backup","Value":"true"}],"Schedules":[{"Name":"daily","CreateRule":{"Interval":24,"IntervalUnit":"HOURS"},"RetainRule":{"Count":7}}]}' \
  --query PolicyId \
  --output text)

aws dlm get-lifecycle-policy --policy-id "$policy_id"
aws dlm get-lifecycle-policies --state ENABLED --resource-types VOLUME
aws dlm delete-lifecycle-policy --policy-id "$policy_id"
```

# Application Auto Scaling

**Protocol:** JSON 1.1 (`X-Amz-Target: AnyScaleFrontendService.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `application-autoscaling`

Application Auto Scaling is the API behind `aws_appautoscaling_target` and
`aws_appautoscaling_policy`, and is what scales ECS services, MSK broker storage,
DynamoDB capacity, Lambda provisioned concurrency, and similar resources.

It is **not** the same service as [Auto Scaling](autoscaling.md), which scales EC2
Auto Scaling groups over the Query protocol under the `autoscaling` signing name.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RegisterScalableTarget` | Registers or updates a scalable target and returns its ARN |
| `DescribeScalableTargets` | Lists scalable targets in a namespace, optionally filtered |
| `DeregisterScalableTarget` | Deregisters a target and deletes its policies and alarms |
| `PutScalingPolicy` | Creates or updates a scaling policy and its CloudWatch alarms |
| `DescribeScalingPolicies` | Lists scaling policies in a namespace, optionally filtered |
| `DeleteScalingPolicy` | Deletes a scaling policy and its CloudWatch alarms |
| `ListTagsForResource` | Returns the tags on a scalable target |
| `TagResource` | Adds or overwrites tags on a scalable target |
| `UntagResource` | Removes tags from a scalable target |
<!-- floci:actions:end -->

## Identity

A scalable target is keyed by the triple **(ServiceNamespace, ResourceId,
ScalableDimension)** — there is no separate identifier. `RegisterScalableTarget` is an
upsert on that triple: parameters you omit are left unchanged, matching AWS.

A scaling policy is keyed by that same triple plus `PolicyName`.

Both `ServiceNamespace` and `ScalableDimension` are validated against the AWS enums; an
unknown value returns `ValidationException`.

## ARN formats

The two ARN families deliberately use different service names, mirroring AWS:

```
ScalableTargetARN  arn:aws:application-autoscaling:<region>:<account>:scalable-target/<id>
PolicyARN          arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:resource/<namespace>/<resourceId>:policyName/<name>
```

`ScalableTargetARN` is the tagging identifier. The Terraform AWS provider reads it from
`DescribeScalableTargets` into the resource's `arn` attribute and then passes it to
`ListTagsForResource` on every read, so it is always populated.

## CloudWatch alarms

A `TargetTrackingScaling` policy creates a real pair of CloudWatch alarms, exactly as AWS
does on your behalf:

```
TargetTracking-<resourceId>-AlarmHigh-<uuid>
TargetTracking-<resourceId>-AlarmLow-<uuid>
```

They are visible through `DescribeAlarms` and are deleted when the policy is deleted or
its scalable target is deregistered.

## Service-linked roles

When `RoleARN` is omitted, Floci synthesizes and returns a service-linked role ARN in the
AWS shape, since the provider treats the attribute as computed:

```
arn:aws:iam::<account>:role/aws-service-role/<namespace>.application-autoscaling.amazonaws.com/AWSServiceRoleForApplicationAutoScaling_<Suffix>
```

## Limitations

- **Scaling policies are stored but inert.** Nothing evaluates them, no alarm ever fires,
  and no capacity is ever adjusted — an ECS service's desired count will not change, and
  MSK broker storage will not grow. This matches the existing behavior of EC2 Auto
  Scaling's `PutScalingPolicy` in Floci. The control plane is faithful; the control loop
  is not emulated.
- `PutScheduledAction`, `DescribeScheduledActions`, and `DeleteScheduledAction` are not
  implemented.
- `DescribeScalingActivities` is not implemented; there are no scaling activities to
  report because policies never fire.
- `PredictiveScalingPolicyConfiguration` is accepted only insofar as `PolicyType` is
  validated; the configuration block is not stored.
- Pagination is not implemented — `DescribeScalableTargets` and `DescribeScalingPolicies`
  return all matching results and never emit a `NextToken`.

## Terraform

Point the `appautoscaling` endpoint at Floci:

```hcl
provider "aws" {
  endpoints {
    appautoscaling = "http://localhost:4566"
  }
}
```

`aws_appautoscaling_target` and `aws_appautoscaling_policy` support create, read, update,
and delete, and converge to a clean plan.

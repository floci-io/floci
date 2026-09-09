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
| `DescribeScalingActivities` | Lists recorded capacity-change events for a scalable target |
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

For `ecs` targets, the alarm is created with the same `ClusterName`/`ServiceName`
dimensions real AWS attaches to the metric, so a metric you push with
`cloudwatch:PutMetricData` using those dimensions is what the control loop below
evaluates against.

## Control loop (ECS only)

A background evaluator (ticking every ~10s) evaluates every CloudWatch alarm with
metric-math configuration — the two alarms above, and any hand-created alarm behind a
`StepScaling` policy, since AWS does not auto-create alarms for those — against the
metric data pushed via `PutMetricData`, transitioning `StateValue` the same way real
CloudWatch does, over the last `EvaluationPeriods` **complete** periods (the still-forming
current period is never counted, matching real CloudWatch). While an alarm is in `ALARM`,
its policy fires on every tick, not only the first transition — a scale-out blocked by
cooldown on one tick is retried on a later one instead of being dropped. For
`ScalableDimension=ecs:service:DesiredCount`, Floci calls the ECS `UpdateService` action
to actually change `desiredCount`, respecting `MinCapacity`/`MaxCapacity` and
`SuspendedState`.

- `TargetTrackingScaling` computes the new capacity as
  `ceil(current * metricValue / TargetValue)`. `DisableScaleIn` suppresses the scale-in
  direction only; the alarm still transitions.
- `StepScaling` resolves the matching `StepAdjustment` by comparing
  `metricValue - Threshold` against each step's interval bounds, then applies
  `AdjustmentType` (`ChangeInCapacity`, `PercentChangeInCapacity` with
  `MinAdjustmentMagnitude`, or `ExactCapacity`).
- **Cooldown** (`ScaleInCooldown`/`ScaleOutCooldown` for target tracking, `Cooldown` for
  step scaling) defaults to AWS's documented 300s ECS default when unset, not `0`. Per
  AWS's own cooldown semantics, a scale-out blocked by cooldown still proceeds immediately
  if the newly computed capacity is *larger* than what the last scale-out already applied
  — cooldown exists to stop flapping on small repeated moves, not to cap a sustained
  breach's climb toward `MaxCapacity`. Scale-in has no such carve-out and is simply
  blocked for the full cooldown window, same as AWS.
- When `TreatMissingData=breaching` reaches `ALARM` with no populated datapoint actually
  breaching (only missing periods pushed it there), the policy still fires: target
  tracking nudges capacity by one unit in the alarm's own direction (the only substitute
  that's guaranteed not to reverse direction), and step scaling resolves the step at
  `delta=0` (right at the alarm's own threshold).
- `TreatMissingData=ignore` keeps the alarm's current state rather than transitioning it,
  but if that current state is already `ALARM`, a deferred action still retries — "ignore"
  only means "don't let this gap change my state," not "stop trying to act."
- Every capacity change is recorded and visible through `DescribeScalingActivities`.
- No metric data pushed for the alarm's window means `INSUFFICIENT_DATA` and no action —
  Floci does not synthesize ECS CPU/memory metrics itself, so an operator (or a compat
  test) has to push them to exercise the loop, the same way a real CloudWatch agent
  would against real AWS.
- Scalable dimensions other than `ecs:service:DesiredCount` (MSK broker storage,
  DynamoDB capacity, Lambda provisioned concurrency, ...) remain stored but inert, as
  described below.

## Service-linked roles

When `RoleARN` is omitted, Floci synthesizes and returns a service-linked role ARN in the
AWS shape, since the provider treats the attribute as computed:

```
arn:aws:iam::<account>:role/aws-service-role/<namespace>.application-autoscaling.amazonaws.com/AWSServiceRoleForApplicationAutoScaling_<Suffix>
```

## Limitations

- **Only `ecs:service:DesiredCount` is driven by the control loop above.** Every other
  scalable dimension (MSK broker storage, DynamoDB capacity, Lambda provisioned
  concurrency, EC2 Spot Fleet, ...) is stored and described faithfully, but nothing
  evaluates its policies or adjusts its capacity. This matches the existing behavior of
  EC2 Auto Scaling's `PutScalingPolicy` in Floci for those dimensions. The control plane
  is faithful for all dimensions; the control loop is only emulated for ECS.
- `PutScheduledAction`, `DescribeScheduledActions`, and `DeleteScheduledAction` are not
  implemented.
- `PredictiveScalingPolicyConfiguration` is accepted only insofar as `PolicyType` is
  validated; the configuration block is not stored.
- Pagination is not implemented — `DescribeScalableTargets` and `DescribeScalingPolicies`
  return all matching results and never emit a `NextToken`.
- **The evaluation range's exact width is an approximation.** Alarm evaluation follows
  CloudWatch's documented precedence: a wider "evaluation range" than
  `Period × EvaluationPeriods` is queried, and whenever enough *real* datapoints exist within
  it the alarm is evaluated on those and `TreatMissingData` is ignored entirely; the setting
  fills in only what real data cannot cover. The premature-transition rule is implemented too,
  so a breach that has aged past `DatapointsToAlarm` with only missing periods after it reaches
  `ALARM`, while a breach at the very end of the window does not. AWS does not publish how wide
  the range is (only that it varies with period length and metric resolution), so Floci uses
  `EvaluationPeriods + 2`, which reproduces the single worked example in AWS's documentation.
  Both published example tables are transcribed as tests in
  `AlarmEvaluatorMissingDataTablesTest`.
- **Scale-out cooldown rarely blocks a repeated scale-out in practice.** Its "proceed if
  larger than the last applied capacity" carve-out (see above) is almost always satisfied
  when nothing external has changed capacity, because both capacity formulas grow
  monotonically with `current` — this matches AWS's documented intent for scale-out to add
  capacity "as fast as it can," so cooldown's practical effect for this control loop is
  mostly to gate scale-*in*, and to eventually halt scale-out once `MaxCapacity` is
  reached (via the ordinary no-op-when-unchanged check, not the cooldown check itself).

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

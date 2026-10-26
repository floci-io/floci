# Auto Scaling

Floci implements the EC2 Auto Scaling API — stored-state management for launch configurations, auto scaling groups, lifecycle hooks, and scaling policies, plus a real capacity reconciler that launches and terminates EC2 instances to maintain desired capacity.

**Protocol:** Query — `POST /` with `Action=` form parameter, credential scope `autoscaling`

**ARN formats:**

- `arn:aws:autoscaling:<region>:<account>:autoScalingGroup:<uuid>:autoScalingGroupName/<name>`
- `arn:aws:autoscaling:<region>:<account>:launchConfiguration:<uuid>:launchConfigurationName/<name>`
- `arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:autoScalingGroupName/<group>/policyName/<name>`

## Supported Operations (53 total)

### Launch Configurations

| Operation | Notes |
|---|---|
| `CreateLaunchConfiguration` | Stores template: `ImageId`, `InstanceType`, `KeyName`, `SecurityGroups`, `UserData`, `IamInstanceProfile`, `InstanceMonitoring`, `BlockDeviceMappings`, `MetadataOptions` |
| `DescribeLaunchConfigurations` | Filtered by name list; returns all if no filter |
| `DeleteLaunchConfiguration` | Removes the named launch configuration |

### Auto Scaling Groups

| Operation | Notes |
|---|---|
| `CreateAutoScalingGroup` | Creates a group with min/max/desired capacity, AZs, tags, launch configuration, launch template, or mixed instances policy; starts capacity reconciliation loop |
| `DescribeAutoScalingGroups` | Filtered by name list; returns all if no filter; includes current instance list with lifecycle state and mixed instances policy shape when configured |
| `UpdateAutoScalingGroup` | Updates capacity bounds, cooldown, launch source, AZs |
| `DeleteAutoScalingGroup` | `ForceDelete=true` terminates all instances before deletion |
| `DescribeTags` | Group tags; filters `auto-scaling-group`, `key`, `value`, `propagate-at-launch` (values ORed, filters ANDed); `MaxRecords`/`NextToken` paging |
| `CreateOrUpdateTags` | Creates or updates group tags and their propagate-at-launch setting |
| `DeleteTags` | Removes named group tags |
| `EnableMetricsCollection` | Records the named group metrics, or all 20 when none are named, as `EnabledMetrics` on `DescribeAutoScalingGroups`. `Granularity` is required and must be `1Minute`; an unknown metric name raises `ValidationError`. No metrics are published to CloudWatch |
| `DisableMetricsCollection` | Removes the named metrics from `EnabledMetrics`, or all of them when none are named |

`CreateAutoScalingGroup` accepts both `subnet-a,subnet-b` and
`subnet-a, subnet-b` in `VPCZoneIdentifier`. `DescribeAutoScalingGroups` returns the
comma-only form. This matches a live AWS comparison in `us-east-1` on October 12,
2026 at 17:30 UTC: two subnets, matching Availability Zones, one launch template
and zero capacity. Both groups had identical configuration except for name, ARN
and creation time. The SDK regression is
`compatibility-tests/sdk-test-python/tests/test_autoscaling_subnet_whitespace.py`.

Scale-out follows the [AWS placement rules](https://docs.aws.amazon.com/autoscaling/ec2/userguide/auto-scaling-benefits.html):
choose the zone with the fewest active instances, then its subnet with the most
free IPv4 addresses. Declared subnets and actual EC2 placement take precedence
over stale group zone metadata. Exhausted IPv4 subnets are skipped when another
subnet has capacity. If all are full, EC2 reports the selected subnet's allocation
error. IPv6-only subnets do not require free IPv4 addresses. Retrying another zone
after a launch error and rebalancing without a capacity increase are not implemented.

AWS launch errors appear as failed scaling activities after preserving partial
launches. Repeated identical failures reuse the recorded activity, following the
scale-in behavior, and do not complete an instance refresh. Unexpected runtime
failures in ordinary scale-out, and save and cleanup failures, still propagate.
Refresh launch failures retain the original replacement attempt and appear as
failed scaling activities while other replacements can continue. Refresh and
ordinary launches share the same launch settings, including template block-device
mappings; accepted refresh launches keep their recorded source identity.

For groups configured only with Availability Zones, Floci preserves its unpinned
EC2 launch behavior and counts each member under the requested zone. This permits
real zones outside the local subnet catalog, such as `us-east-1d`; the underlying
EC2 instance can use a default subnet in a different zone.

### Instance Management

| Operation | Notes |
|---|---|
| `DescribeAutoScalingInstances` | Returns all ASG-tracked instances with lifecycle state and health status |
| `SetInstanceHealth` | Updates an instance's health status immediately; accepts but does not enforce the grace-period flag |
| `SetInstanceProtection` | Enables or disables scale-in protection for named instances in a group |
| `SetDesiredCapacity` | Updates desired count; reconciler handles scale-out / scale-in within 10 s |
| `AttachInstances` | Attaches existing EC2 instances to a group; sets lifecycle state to `InService`. Refuses the whole batch with `ValidationError` when an instance already belongs to a group |
| `DetachInstances` | Detaches instances from a group; optionally decrements desired capacity |
| `TerminateInstanceInAutoScalingGroup` | Terminates a specific instance; optionally decrements desired capacity |
| `SuspendProcesses` | Records suspended scaling processes (empty list suspends all); reported back by `DescribeAutoScalingGroups` |
| `ResumeProcesses` | Clears suspended processes (empty list resumes all) |

Member instances carry the tags the
[EC2 instance tagging lifecycle](https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-tagging.html#tag-lifecycle)
describes. An instance the group launches gets its launch template's instance tags, then the
group's `PropagateAtLaunch` tags, which win a key both set, then `aws:autoscaling:groupName` with
the group's name. `AttachInstances` adds the same group tags and `aws:autoscaling:groupName`,
overwriting existing tags with the same keys. `DetachInstances` removes only
`aws:autoscaling:groupName`, and only while it names the group being detached from.

`AttachInstances` does not yet refuse what AWS refuses for the instance's state or zone or for
the group's `MaxSize`, and does not grow desired capacity by the number of instances attached.
An ID EC2 has no instance for joins the group without tags.

### Instance Refresh

| Operation | Notes |
|---|---|
| `StartInstanceRefresh` | Starts replacement of group instances and returns a refresh ID |
| `DescribeInstanceRefreshes` | Returns refresh status and progress, with `MaxRecords`/`NextToken` paging |

### Instance Refresh Recovery

New instance refreshes retain the selected instances and their replacement progress
across persistent, hybrid, and WAL storage reloads. Suspending `InstanceRefresh`,
`Launch`, or `Terminate` pauses refresh replacements; resuming those processes lets
the refresh continue. A failed replacement launch remains part of the refresh, so
ordinary desired-capacity reconciliation does not bypass the pause. Explicit
`TerminateInstanceInAutoScalingGroup` requests still run, and independent capacity
increases can proceed while only `InstanceRefresh` is suspended. Paused original
instances keep their target-group registrations until replacement resumes.
Explicitly detached instances leave refresh ownership and are not adopted again
when the refresh resumes. Recovery of an accepted EC2 launch keeps the source
identity recorded for that attempt even if the group configuration has changed.
Older attempts without a recorded source use the available EC2 instance metadata
without assigning the current launch template to the recovered instance.

This does not implement rolling health-percentage limits, checkpoints, rollback,
or cancellation. Refreshes saved by older versions without replacement ownership
retain the previous reconciliation behavior. Storage reload coverage does not
imply an atomic transaction spanning Auto Scaling and EC2 after a process crash.

### Load Balancer Attachment

| Operation | Notes |
|---|---|
| `AttachLoadBalancerTargetGroups` | Attaches ELB v2 target group ARNs; new instances auto-registered on InService |
| `DetachLoadBalancerTargetGroups` | Detaches target groups; instances deregistered |
| `DescribeLoadBalancerTargetGroups` | Lists target groups attached to a group |
| `AttachLoadBalancers` | Classic ELB attachment (stored; no ELB v1 routing) |
| `DetachLoadBalancers` | Classic ELB detachment |
| `DescribeLoadBalancers` | Lists classic ELBs attached to a group |

### Traffic Sources

| Operation | Notes |
|---|---|
| `AttachTrafficSources` | Unified elb/elbv2/vpc-lattice attachment API; stored per identifier with its type |
| `DetachTrafficSources` | Removes a traffic source by identifier |
| `DescribeTrafficSources` | Lists attached sources, optionally filtered by type; every source reports `InService` |

### Lifecycle Hooks

| Operation | Notes |
|---|---|
| `PutLifecycleHook` | Creates or updates a hook: `LifecycleTransition`, `DefaultResult`, `HeartbeatTimeout` |
| `DescribeLifecycleHooks` | Lists hooks for a group |
| `DeleteLifecycleHook` | Removes a hook |
| `CompleteLifecycleAction` | Signals `CONTINUE` or `ABANDON` for a pending lifecycle action |
| `RecordLifecycleActionHeartbeat` | Extends the heartbeat timeout for an in-progress lifecycle action |

### Scaling Policies

| Operation | Notes |
|---|---|
| `PutScalingPolicy` | Creates or updates a policy: `SimpleScaling` fields or `TargetTrackingScaling` with predefined metric, target value, and estimated warmup |
| `DescribePolicies` | Lists policies filtered by group or policy name, including stored target tracking configuration |
| `DeletePolicy` | Removes a scaling policy |

### Scheduled Actions

| Operation | Notes |
|---|---|
| `PutScheduledUpdateGroupAction` | Creates or updates a schedule: recurrence, time zone, start/end time, capacity bounds |
| `DescribeScheduledActions` | Lists scheduled actions filtered by group or action name |
| `DeleteScheduledAction` | Removes a scheduled action |

### Warm Pools

| Operation | Notes |
|---|---|
| `PutWarmPool` | Full replace per the wire model: omitted fields reset to defaults; `MaxGroupPreparedCapacity=-1` clears the value |
| `DescribeWarmPool` | Returns the stored configuration; also embedded in `DescribeAutoScalingGroups` per the AutoScalingGroup shape |
| `DeleteWarmPool` | Removes the configuration; idempotent when none exists |

### Activities

| Operation | Notes |
|---|---|
| `DescribeScalingActivities` | Returns the activity log for a group; activities recorded on scale-out and scale-in events |

### Metadata

| Operation | Notes |
|---|---|
| `DescribeTerminationPolicyTypes` | Returns the standard termination policy names |
| `DescribeAccountLimits` | Returns max group / config / instance limits |
| `DescribeLifecycleHookTypes` | Returns `autoscaling:EC2_INSTANCE_LAUNCHING` and `autoscaling:EC2_INSTANCE_TERMINATING` |
| `DescribeAdjustmentTypes` | Returns the four standard adjustment types |
| `DescribeMetricCollectionTypes` | Returns standard metric and granularity names |
| `DescribeAutoScalingNotificationTypes` | Returns all notification type names |

## Suspended Process Reasons

`SuspendProcesses` records when each newly suspended process entered that state.
`DescribeAutoScalingGroups` returns `SuspensionReason` in the form
`User suspended at 2026-10-18T16:00:00Z`. Repeating a suspension preserves its
original time. Resuming a process removes its recorded time; suspending it again
starts a new interval.

Saved groups without a recorded suspension time still report the process name
and omit the optional reason. Floci does not invent a date for those existing
suspensions. A repeated suspension leaves an unknown time unknown until the
process is resumed and suspended again.

## Capacity Reconciler (Phase 2)

Floci runs a background reconciler (10 s fixed rate) that keeps each group's InService instance count aligned with `DesiredCapacity`:

- **Scale-out**: calls `RunInstances` with the group's launch configuration; new instances are tracked as `Pending` until the EC2 state transitions to `running`, at which point they move to `InService` and are registered with all attached ELB v2 target groups.
- **Scale-in**: selects InService instances not protected from scale-in, deregisters them from target groups, then calls `TerminateInstances`.
- Activity records are written on each scale-out and scale-in event.

`SuspendProcesses` pauses desired-capacity scale-out for `Launch` and scale-in for
`Terminate`. The desired count remains stored, and `ResumeProcesses` allows the
next reconciliation pass to continue toward it. Omitting process names suspends
or resumes all processes. Suspending another process does not pause these capacity
adjustments. Suspension and resumption wait for any desired-capacity adjustment already
in progress for that group, so a completed suspension cannot be bypassed by an earlier
reconciliation check.

New owned refresh replacements also pause for `InstanceRefresh`, `Launch`, or
`Terminate`, as described above. Explicitly requested termination and stale-instance
cleanup continue independently.
Other suspended process names are stored and returned but do not pause their
associated activities.

## Launch Source Compatibility

New launch configurations preserve their original base64 user data when launching instances, including
binary and gzip payloads. Configurations derived from an instance retain its original encoding when
available. Existing stored configurations retain their previous interpretation because older records
mixed encoded input and decoded text; recreate a configuration to use byte-preserving launches.
When deriving a configuration from an instance, omitted and blank user-data overrides continue to
inherit the source instance's data. Creation checks the final selected data before saving: it must
pass the shared EC2 decoder's base64 and 16 KiB raw-data checks. The decoder also rejects malformed
gzip and expansion beyond 10 MiB; this is Floci's execution safety limit, not an AWS API limit.
Invalid marked data in an older saved configuration is contained as a launch failure so reconciliation
can still save the group and check refresh progress.

Direct and mixed launch templates retain the selected version's original user-data
encoding for instance attributes and metadata, including compressed and binary
payloads. Execution still uses decoded text. Older templates that store only
execution text retain that text through a UTF-8 fallback.

Auto Scaling groups preserve either a launch configuration, a top-level launch template, or a `MixedInstancesPolicy`. These launch sources are mutually exclusive in create/update requests. When a mixed instances policy is supplied, Floci stores and returns:

- `LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateId`
- `LaunchTemplate.LaunchTemplateSpecification.LaunchTemplateName`
- `LaunchTemplate.LaunchTemplateSpecification.Version`
- `LaunchTemplate.Overrides.member.N.InstanceType`
- `LaunchTemplate.Overrides.member.N.WeightedCapacity`
- `LaunchTemplate.Overrides.member.N.InstanceRequirements`
- `InstancesDistribution.OnDemandBaseCapacity`
- `InstancesDistribution.OnDemandPercentageAboveBaseCapacity`
- `InstancesDistribution.SpotAllocationStrategy`

An override selects instance types either by name or by attribute. Setting both `InstanceType` and
`InstanceRequirements` on the same override raises `ValidationError`, matching AWS. An override that
specifies `InstanceRequirements` must specify both `VCpuCount` and `MemoryMiB`, the two members the
AWS model marks required on that shape, and raises `ValidationError` otherwise. Every member of the
`InstanceRequirements` shape round-trips except `BaselinePerformanceFactors`, which is accepted and
dropped.

`WeightedCapacity` must be an integer from 1 to 999 written in at most 32 characters, and once
one override sets it every override must, as the AWS model requires. Either rule raises
`ValidationError` otherwise.

A group uses attribute-based instance type selection when the mixed instances policy left in effect
by the request carries at least one launch template override with `InstanceRequirements`. On create
that is the policy the request supplies. On update it is the policy the request supplies, the stored
policy when the request names no launch source, or none when the request switches the group to a
launch configuration or a plain launch template.

## Optional Group Fields

`CreateAutoScalingGroup` and `UpdateAutoScalingGroup` both accept the fields below, and
`DescribeAutoScalingGroups` returns each one only when the group has a value for it. A group that
never set a field omits it from the response rather than reporting a default.

| Field | Notes |
|---|---|
| `DesiredCapacityType` | One of `units`, `vcpu`, `memory-mib`. Any other value raises `ValidationError`. AWS supports it for attribute-based instance type selection only, so `vcpu` and `memory-mib` raise `ValidationError` unless the effective mixed instances policy uses `InstanceRequirements`. `units` is the documented default and is always accepted |
| `CapacityRebalance` | Stored and echoed as a boolean |
| `MaxInstanceLifetime` | Seconds. Must be `0` or at least `86400`. `0` means no maximum and is echoed back as `0` |
| `DefaultInstanceWarmup` | Seconds. Pass `-1` to remove a value already set, after which the field is omitted again |
| `ServiceLinkedRoleARN` | Stored and echoed as given, 1 to 1600 characters. Floci does not check that the role exists, and a group that never set it omits it rather than reporting the default `AWSServiceRoleForAutoScaling` role |
| `InstanceMaintenancePolicy` | `MinHealthyPercentage` 0 to 100 and `MaxHealthyPercentage` 100 to 200, both required, at most 100 apart. `-1` on both clears a policy already set. Any other combination raises `ValidationError` |
| `AvailabilityZoneDistribution` | `CapacityDistributionStrategy` is one of `balanced-only`, `balanced-best-effort`, `reservations-then-balanced` |
| `CapacityReservationSpecification` | `CapacityReservationPreference` is one of `capacity-reservations-only`, `capacity-reservations-first`, `none`, `default`. `CapacityReservationTarget` (`CapacityReservationIds`, `CapacityReservationResourceGroupArns`) round-trips, accepts at most one ID or resource group ARN, and is rejected alongside `none`. Each ID is 1 to 255 characters of `[A-Za-z0-9-_/]` and each resource group ARN is 1 to 1600 characters |

Except for `CapacityReservationSpecification`, these fields are stored and reported only.

The group capacity reservation preference applies to scale-out and replacement launches.
`default` or an omitted preference retains the launch template specification. `none` launches
without consuming reservations. `capacity-reservations-only` requires matching reserved capacity;
`capacity-reservations-first` falls back to unreserved On-Demand capacity on a capacity shortage.
An omitted group target inherits the template target; with neither target, matching open
reservations are eligible. Updating the group affects subsequent launches, not existing instances.
Missing or invalid targets do not trigger On-Demand fallback. Resource group targeting remains
unsupported, as in EC2, and reports a failed scaling activity without allocating an instance.

## Scaling Policy Compatibility

Target tracking policies preserve `TargetTrackingConfiguration` for predefined metrics. `DescribePolicies` returns `PredefinedMetricSpecification.PredefinedMetricType`, `TargetValue`, and `EstimatedInstanceWarmup` when present.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AUTOSCALING_ENABLED` | `true` | Enable or disable the service |

## Usage Example

```bash
# Create a launch configuration
aws autoscaling create-launch-configuration \
  --launch-configuration-name my-lc \
  --image-id ami-12345678 \
  --instance-type t3.micro

# Create a group targeting desired=2
aws autoscaling create-auto-scaling-group \
  --auto-scaling-group-name my-asg \
  --launch-configuration-name my-lc \
  --min-size 1 \
  --max-size 5 \
  --desired-capacity 2 \
  --availability-zones us-east-1a

# Attach an ELB v2 target group
aws autoscaling attach-load-balancer-target-groups \
  --auto-scaling-group-name my-asg \
  --target-group-arns arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-tg/abc123

# Watch instances appear
aws autoscaling describe-auto-scaling-groups \
  --auto-scaling-group-names my-asg

# Scale out
aws autoscaling set-desired-capacity \
  --auto-scaling-group-name my-asg \
  --desired-capacity 3
```

Launch configurations accept an IAM instance profile name or ARN. Unknown and foreign profiles fail creation with `ValidationError`. At launch, the reconciler resolves a stored name to its ARN in the owning account. `CreateAutoScalingGroup` and `UpdateAutoScalingGroup` refuse, with `ValidationError`, a launch template that names both an instance profile name and an ARN, directly or through the mixed instances policy.

Launch-configuration metadata options (`HttpTokens`, `HttpEndpoint`, and `HttpPutResponseHopLimit`) round-trip through Query and CloudFormation and reach EC2 launches. CloudFormation resolves intrinsics inside the block, so `Fn::If` and `AWS::NoValue` can select or omit the block or any of its members. When the structure is omitted it stays absent from `DescribeLaunchConfigurations`; a supplied partial structure reports defaults for unspecified fields. Launch templates, mixed-instance templates and EC2 Fleet launches likewise forward their existing metadata options.

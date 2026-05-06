# Auto Scaling

Floci implements the EC2 Auto Scaling API — stored-state management for launch configurations, auto scaling groups, lifecycle hooks, and scaling policies, plus a real capacity reconciler that launches and terminates EC2 instances to maintain desired capacity.

**Protocol:** Query — `POST /` with `Action=` form parameter, credential scope `autoscaling`

**ARN formats:**

- `arn:aws:autoscaling:<region>:<account>:autoScalingGroup:<uuid>:autoScalingGroupName/<name>`
- `arn:aws:autoscaling:<region>:<account>:launchConfiguration:<uuid>:launchConfigurationName/<name>`
- `arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:autoScalingGroupName/<group>/policyName/<name>`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateLaunchConfiguration` | Stores template: `ImageId`, `InstanceType`, `KeyName`, `SecurityGroups`, `UserData`, `IamInstanceProfile` |
| `DescribeLaunchConfigurations` | Filtered by name list; returns all if no filter |
| `DeleteLaunchConfiguration` | Removes the named launch configuration |
| `CreateAutoScalingGroup` | Creates a group with min/max/desired capacity, AZs, tags; starts capacity reconciliation loop |
| `UpdateAutoScalingGroup` | Updates capacity bounds, cooldown, launch configuration, AZs |
| `DeleteAutoScalingGroup` | `ForceDelete=true` terminates all instances before deletion |
| `DescribeAutoScalingGroups` | Filtered by name list; returns all if no filter; includes current instance list with lifecycle state |
| `SetDesiredCapacity` | Updates desired count; reconciler handles scale-out / scale-in within 10 s |
| `DescribeAutoScalingInstances` | Returns all ASG-tracked instances with lifecycle state and health status |
| `AttachInstances` | Attaches existing EC2 instances to a group; sets lifecycle state to `InService` |
| `DetachInstances` | Detaches instances from a group; optionally decrements desired capacity |
| `TerminateInstanceInAutoScalingGroup` | Terminates a specific instance; optionally decrements desired capacity |
| `AttachLoadBalancerTargetGroups` | Attaches ELB v2 target group ARNs; new instances auto-registered on InService |
| `DetachLoadBalancerTargetGroups` | Detaches target groups; instances deregistered |
| `DescribeLoadBalancerTargetGroups` | Lists target groups attached to a group |
| `AttachLoadBalancers` | Classic ELB attachment (stored; no ELB v1 routing) |
| `DetachLoadBalancers` | Classic ELB detachment |
| `DescribeLoadBalancers` | Lists classic ELBs attached to a group |
| `PutLifecycleHook` | Creates or updates a hook: `LifecycleTransition`, `DefaultResult`, `HeartbeatTimeout` |
| `DeleteLifecycleHook` | Removes a hook |
| `DescribeLifecycleHooks` | Lists hooks for a group |
| `CompleteLifecycleAction` | Signals `CONTINUE` or `ABANDON` for a pending lifecycle action |
| `RecordLifecycleActionHeartbeat` | Extends the heartbeat timeout for an in-progress lifecycle action |
| `PutScalingPolicy` | Creates or updates a policy: `SimpleScaling`, `AdjustmentType`, `ScalingAdjustment`, `Cooldown` |
| `DeletePolicy` | Removes a scaling policy |
| `DescribePolicies` | Lists policies filtered by group or policy name |
| `DescribeScalingActivities` | Returns the activity log for a group; activities recorded on scale-out and scale-in events |
| `DescribeAutoScalingNotificationTypes` | Returns all notification type names |
| `DescribeTerminationPolicyTypes` | Returns the standard termination policy names |
| `DescribeAdjustmentTypes` | Returns the four standard adjustment types |
| `DescribeAccountLimits` | Returns max group / config / instance limits |
| `DescribeLifecycleHookTypes` | Returns `autoscaling:EC2_INSTANCE_LAUNCHING` and `autoscaling:EC2_INSTANCE_TERMINATING` |
| `DescribeMetricCollectionTypes` | Returns standard metric and granularity names |
<!-- floci:actions:end -->
## Capacity Reconciler (Phase 2)

Floci runs a background reconciler (10 s fixed rate) that keeps each group's InService instance count aligned with `DesiredCapacity`:

- **Scale-out**: calls `RunInstances` with the group's launch configuration; new instances are tracked as `Pending` until the EC2 state transitions to `running`, at which point they move to `InService` and are registered with all attached ELB v2 target groups.
- **Scale-in**: selects InService instances not protected from scale-in, deregisters them from target groups, then calls `TerminateInstances`.
- Activity records are written on each scale-out and scale-in event.

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

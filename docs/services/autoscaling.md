# Auto Scaling

Floci implements the EC2 Auto Scaling API — stored-state management for launch configurations, auto scaling groups, lifecycle hooks, and scaling policies, plus a real capacity reconciler that launches and terminates EC2 instances to maintain desired capacity.

**Protocol:** Query — `POST /` with `Action=` form parameter, credential scope `autoscaling`

**ARN formats:**

- `arn:aws:autoscaling:<region>:<account>:autoScalingGroup:<uuid>:autoScalingGroupName/<name>`
- `arn:aws:autoscaling:<region>:<account>:launchConfiguration:<uuid>:launchConfigurationName/<name>`
- `arn:aws:autoscaling:<region>:<account>:scalingPolicy:<uuid>:autoScalingGroupName/<group>/policyName/<name>`

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateLaunchConfiguration` |
| `DescribeLaunchConfigurations` |
| `DeleteLaunchConfiguration` |
| `CreateAutoScalingGroup` |
| `UpdateAutoScalingGroup` |
| `DeleteAutoScalingGroup` |
| `DescribeAutoScalingGroups` |
| `SetDesiredCapacity` |
| `DescribeAutoScalingInstances` |
| `AttachInstances` |
| `DetachInstances` |
| `TerminateInstanceInAutoScalingGroup` |
| `AttachLoadBalancerTargetGroups` |
| `DetachLoadBalancerTargetGroups` |
| `DescribeLoadBalancerTargetGroups` |
| `AttachLoadBalancers` |
| `DetachLoadBalancers` |
| `DescribeLoadBalancers` |
| `PutLifecycleHook` |
| `DeleteLifecycleHook` |
| `DescribeLifecycleHooks` |
| `CompleteLifecycleAction` |
| `RecordLifecycleActionHeartbeat` |
| `PutScalingPolicy` |
| `DeletePolicy` |
| `DescribePolicies` |
| `DescribeScalingActivities` |
| `DescribeAutoScalingNotificationTypes` |
| `DescribeTerminationPolicyTypes` |
| `DescribeAdjustmentTypes` |
| `DescribeAccountLimits` |
| `DescribeLifecycleHookTypes` |
| `DescribeMetricCollectionTypes` |
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

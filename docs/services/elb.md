# Elastic Load Balancing v2

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

Floci supports Application Load Balancers (ALB) and Network Load Balancers (NLB) through the ELBv2 management API. The control plane is AWS SDK / CLI / Terraform compatible, and HTTP listeners can forward to registered instance targets using the target's reachable local address.

## Supported Actions

### Load Balancers
`CreateLoadBalancer` · `DescribeLoadBalancers` · `DeleteLoadBalancer` · `ModifyLoadBalancerAttributes` · `DescribeLoadBalancerAttributes` · `DescribeCapacityReservation` · `SetSecurityGroups` · `SetSubnets` · `SetIpAddressType`

### Target Groups
`CreateTargetGroup` · `DescribeTargetGroups` · `ModifyTargetGroup` · `DeleteTargetGroup` · `ModifyTargetGroupAttributes` · `DescribeTargetGroupAttributes`

### Targets
`RegisterTargets` · `DeregisterTargets` · `DescribeTargetHealth`

### Listeners
`CreateListener` · `DescribeListeners` · `ModifyListener` · `ModifyListenerAttributes` · `DescribeListenerAttributes` · `DeleteListener` · `AddListenerCertificates` · `RemoveListenerCertificates` · `DescribeListenerCertificates`

### Rules
`CreateRule` · `DescribeRules` · `ModifyRule` · `DeleteRule` · `SetRulePriorities`

### Tags
`AddTags` · `RemoveTags` · `DescribeTags`

### Metadata
`DescribeSSLPolicies` · `DescribeAccountLimits`

## Behavior Notes

- Load balancer, target group, listener, rule, and tag state is persisted through Floci storage and rebuilt on service startup.
- Load balancers are created in `active` state.
- HTTP listener sockets are preserved when listener actions change and are restarted only when socket-level settings such as port change.
- Instance targets are resolved through EC2 instance private addresses so local load balancer traffic can reach containers.
- Target health starts in `initial` state with reason `Elb.RegistrationInProgress` and is updated by Floci's health checker when monitoring is active.
- Each `CreateListener` automatically creates an immutable default rule (`priority=default`, `isDefault=true`). This rule cannot be deleted; use `ModifyListener` to change its action.
- Rule priorities are validated for uniqueness. `SetRulePriorities` is atomic: all priority assignments are validated before any change is committed.
- `DeleteTargetGroup` is rejected with `ResourceInUse` while the target group is referenced by any listener or rule.
- `DeleteRule` is rejected with `OperationNotPermitted` for the default rule.
- `DescribeSSLPolicies` returns a pre-seeded list of standard AWS SSL policies (`ELBSecurityPolicy-*`).
- `DescribeAccountLimits` returns standard default limits (e.g., 50 load balancers per region, 100 target groups, etc.).

## ARN Format

```
arn:aws:elasticloadbalancing:{region}:{account-id}:loadbalancer/app/{name}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:targetgroup/{name}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:listener/app/{lb-name}/{lb-id}/{hex16}
arn:aws:elasticloadbalancing:{region}:{account-id}:listener-rule/app/{lb-name}/{lb-id}/{listener-id}/{hex16}
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a load balancer
aws elbv2 create-load-balancer \
  --name my-alb \
  --type application \
  --scheme internet-facing

# Create a target group
aws elbv2 create-target-group \
  --name my-targets \
  --protocol HTTP \
  --port 80 \
  --target-type instance

# Register targets
aws elbv2 register-targets \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123 \
  --targets Id=i-00000000001,Port=8080

# Create a listener with a default forward action
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123 \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Add a path-based routing rule
aws elbv2 create-rule \
  --listener-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-alb/abc123/def456 \
  --priority 10 \
  --conditions Field=path-pattern,Values='/api/*' \
  --actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Describe load balancers
aws elbv2 describe-load-balancers

# Describe target health
aws elbv2 describe-target-health \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123

# Tag a resource
aws elbv2 add-tags \
  --resource-arns arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123 \
  --tags Key=env,Value=dev

# Clean up
aws elbv2 delete-listener \
  --listener-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/my-alb/abc123/def456
aws elbv2 delete-load-balancer \
  --load-balancer-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/my-alb/abc123
aws elbv2 delete-target-group \
  --target-group-arn arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/my-targets/abc123
```

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELBV2_ENABLED` | `true` | Enable or disable the ELBv2 service |

## Listener Ports

Listener sockets bind on the Floci host. Expose any listener ports you need in Docker Compose when Floci itself runs in a container, similar to RDS and ElastiCache proxy ports.

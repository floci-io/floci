# Elastic Load Balancing v2

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

Floci supports Application Load Balancers (ALB) and Network Load Balancers (NLB) through the ELBv2 management API. This is a Phase 1 implementation: the full CRUD control plane is available and AWS SDK / CLI / Terraform compatible. Data-plane traffic forwarding (actual TCP listener ports) is planned for Phase 2.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateLoadBalancer` |
| `DescribeLoadBalancers` |
| `DeleteLoadBalancer` |
| `ModifyLoadBalancerAttributes` |
| `DescribeLoadBalancerAttributes` |
| `SetSecurityGroups` |
| `SetSubnets` |
| `SetIpAddressType` |
| `CreateTargetGroup` |
| `DescribeTargetGroups` |
| `DeleteTargetGroup` |
| `ModifyTargetGroup` |
| `ModifyTargetGroupAttributes` |
| `DescribeTargetGroupAttributes` |
| `CreateListener` |
| `DescribeListeners` |
| `DeleteListener` |
| `ModifyListener` |
| `CreateRule` |
| `DescribeRules` |
| `DeleteRule` |
| `ModifyRule` |
| `SetRulePriorities` |
| `RegisterTargets` |
| `DeregisterTargets` |
| `DescribeTargetHealth` |
| `AddTags` |
| `RemoveTags` |
| `DescribeTags` |
| `DescribeAccountLimits` |
| `DescribeSSLPolicies` |
| `AddListenerCertificates` |
| `RemoveListenerCertificates` |
| `DescribeListenerCertificates` |
<!-- floci:actions:end -->
## Behavior Notes

- Load balancers are created in `provisioning` state and transition to `active` immediately on subsequent describes.
- Target health always returns `initial` state with reason `Elb.RegistrationInProgress` — data-plane health checks are not performed in Phase 1.
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

```yaml
floci:
  services:
    elbv2:
      enabled: true   # default: true
```

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELBV2_ENABLED` | `true` | Enable or disable the ELBv2 service |

## Phase 2 (Planned)

Phase 2 will bind real TCP listener ports on the host so traffic sent to a listener port is forwarded to registered targets. This requires exposing a port range (e.g., `8300-8399`) in the Docker Compose configuration, similar to how ElastiCache and RDS proxy ports work today.

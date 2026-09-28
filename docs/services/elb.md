# Elastic Load Balancing v2

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` parameter

Floci supports Application Load Balancers (ALB) and Network Load Balancers (NLB) through the ELBv2 management API. The control plane is AWS SDK / CLI / Terraform compatible, and HTTP listeners can forward to registered instance targets using the target's reachable local address.

> Classic (v1) Elastic Load Balancing is a **different API** served from the same endpoint host —
> see [ELB Classic (v1)](elb-classic.md). Requests are routed by their `Version` parameter:
> `2015-12-01` here, `2012-06-01` there.

## Supported Actions

### Load Balancers

| Action | Description |
|--------|-------------|
| CreateLoadBalancer | Creates an ALB or NLB in active state with persisted attributes and tags. |
| DescribeLoadBalancers | Lists or returns stored load balancers. |
| DeleteLoadBalancer | Deletes a load balancer and stops its listener sockets. |
| ModifyLoadBalancerAttributes | Updates persisted load balancer attributes. |
| DescribeLoadBalancerAttributes | Returns attributes stored for a load balancer. |
| DescribeCapacityReservation | Returns the stored capacity reservation fields for a load balancer. |
| SetSecurityGroups | Replaces the security groups associated with a load balancer. |
| SetSubnets | Replaces the subnets associated with a load balancer. |
| SetIpAddressType | Updates the IP address type stored for a load balancer. |

### Target Groups

| Action | Description |
|--------|-------------|
| CreateTargetGroup | Creates a target group with protocol, port, health check, and target-type settings. |
| DescribeTargetGroups | Lists or returns stored target groups. |
| ModifyTargetGroup | Updates mutable target group settings. |
| DeleteTargetGroup | Deletes an unused target group. |
| ModifyTargetGroupAttributes | Updates persisted target group attributes. |
| DescribeTargetGroupAttributes | Returns attributes stored for a target group. |

### Targets

| Action | Description |
|--------|-------------|
| RegisterTargets | Registers targets with a target group. |
| DeregisterTargets | Removes targets from a target group. |
| DescribeTargetHealth | Returns target health records maintained by Floci. |

### Listeners

| Action | Description |
|--------|-------------|
| CreateListener | Creates a listener and its non-deletable default rule. |
| DescribeListeners | Lists or returns stored listeners. |
| ModifyListener | Updates a listener's configuration and default actions. |
| ModifyListenerAttributes | Updates persisted listener attributes. |
| DescribeListenerAttributes | Returns attributes stored for a listener. |
| DeleteListener | Deletes a listener and stops its socket. |
| AddListenerCertificates | Adds certificates to a listener. |
| RemoveListenerCertificates | Removes certificates from a listener. |
| DescribeListenerCertificates | Lists certificates associated with a listener. |

### Rules

| Action | Description |
|--------|-------------|
| CreateRule | Creates a non-default listener rule with conditions, actions, and priority. |
| DescribeRules | Lists or returns listener rules. |
| ModifyRule | Updates a listener rule's conditions and actions. |
| DeleteRule | Deletes a non-default listener rule. |
| SetRulePriorities | Atomically updates rule priorities after validating uniqueness. |

### Tags

| Action | Description |
|--------|-------------|
| AddTags | Adds tags to supported ELBv2 resources. |
| RemoveTags | Removes tags from supported ELBv2 resources. |
| DescribeTags | Returns tags for supported ELBv2 resources. |

### Metadata

| Action | Description |
|--------|-------------|
| DescribeSSLPolicies | Returns Floci's pre-seeded standard SSL policy list. |
| DescribeAccountLimits | Returns standard default ELBv2 account limits. |

## Behavior Notes

- Load balancer, target group, listener, rule, and tag state is persisted through Floci storage and rebuilt on service startup.
- Load balancers are created in `active` state.
- HTTP listener sockets are preserved when listener actions change and are restarted only when socket-level settings such as port change.
- Instance targets are resolved through EC2 instance private addresses so local load balancer traffic can reach containers.
- Target health starts in `initial` state with reason `Elb.RegistrationInProgress` and is updated by Floci's health checker when monitoring is active.
- Health checks probe the target group's `HealthCheckPort` when it is a port number, and each target's own port when it is `traffic-port` (the default).
- Each `CreateListener` automatically creates an immutable default rule (`priority=default`, `isDefault=true`). This rule cannot be deleted; use `ModifyListener` to change its action.
- Rule priorities are validated for uniqueness. `SetRulePriorities` is atomic: all priority assignments are validated before any change is committed.
- `DeleteTargetGroup` is rejected with `ResourceInUse` while the target group is referenced by any listener or rule.
- `DeleteRule` is rejected with `OperationNotPermitted` for the default rule.
- `DescribeSSLPolicies` returns a pre-seeded list of standard AWS SSL policies (`ELBSecurityPolicy-*`).
- `DescribeAccountLimits` returns standard default limits (e.g., 50 load balancers per region, 100 target groups, etc.).
- `routing.http.preserve_host_header.enabled` (default `false`) controls whether the original client Host header is forwarded to targets unchanged, or replaced with the target's `host:port`.
- `RegisterTargets` rejects link-local and cloud instance-metadata addresses (`169.254.0.0/16`, `fe80::/10`, `fd00:ec2::254`) with `InvalidTarget`, and the load balancer and its health checks refuse to connect to them. Loopback and private addresses stay reachable so a load balancer can front a neighbouring container.

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

### Two load balancers on the same port

In AWS each load balancer has its own DNS name and addresses, so two of them can both listen on
port 80 and never interact: the network picks the load balancer, and a `host-header` rule only picks
a rule inside one listener. Floci serves every load balancer from a single socket per port, so the
Host header has to carry both decisions. A template that puts a public and an internal load balancer
on port 80 is correct for AWS and stays accepted here, but the request has to name the one it wants.

A listener is chosen in this order:

1. The Host header equals a load balancer's own DNS name (`{name}-{id}.elb.localhost.floci.io`).
2. Exactly one listener on the port has a rule whose `host-header` condition matches the Host
   header. This uses the same matching as the rule evaluation that follows, so it is
   case-insensitive, ignores the port, and applies AWS's wildcards: `*` matches 0 or more
   characters and `?` matches exactly 1, which means `*.example.com` claims `test.example.com`
   but not `example.com`. `HostHeaderConfig.RegexValues` is not supported, by rule evaluation or
   by selection, so a load balancer that names itself only through a regex cannot be selected
   this way.
3. Only one listener is on the port, in which case it answers any Host header.

If none of those resolve, and in particular when two load balancers declare the same hostname or
neither declares any, the request answers `502 No listener for host`. Floci logs a warning naming
both load balancers the first time a second one takes a port, because the targets behind them stay
healthy and nothing else reports the collision.

Giving each load balancer a `host-header` rule for the hostname it serves is usually enough, and it
is the same configuration AWS needs. Moving one load balancer to a different listener port also
works, at the cost of a Floci-only difference from the deployed template.

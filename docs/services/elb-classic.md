# Elastic Load Balancing (Classic, v1)

**Protocol:** Query (XML) — `POST http://localhost:4566/` with `Action=` and `Version=2012-06-01`

Classic Elastic Load Balancing is the 2012-06-01 API behind `aws elb` and Terraform's `aws_elb`
resource. It is a **different API** from [ELB v2](elb.md) (ALB/NLB, 2015-12-01), even though both
are served from the same endpoint host and signed with the same `elasticloadbalancing` credential
scope.

## How Floci tells the two apart

Every Query-protocol request carries the API version it is speaking as the `Version` form
parameter, and that is what Floci routes on:

| `Version` | Answered by |
|---|---|
| `2012-06-01` | Classic (this page) |
| `2015-12-01` | [ELB v2](elb.md) |

Responses are emitted in the matching namespace —
`http://elasticloadbalancing.amazonaws.com/doc/2012-06-01/` for Classic. A Classic
`CreateLoadBalancer` returns `DNSName` and nothing else; Classic load balancers have no ARN and
are addressed by `LoadBalancerName` for their whole life.

If a hand-rolled client omits `Version`, Floci falls back to the request shape: an action unique
to one API decides, and for the action names both APIs define (`CreateLoadBalancer`,
`DescribeLoadBalancers`, `AddTags`, …) the presence of `LoadBalancerName` or `LoadBalancerNames`
selects Classic. With neither present the request goes to ELB v2.

## Supported Actions

| Action | Description |
|--------|-------------|
| CreateLoadBalancer | Creates a Classic load balancer with listeners, subnets or AZs, security groups, scheme, and tags. Returns `DNSName`. |
| DescribeLoadBalancers | Returns `LoadBalancerDescription` records, optionally filtered by `LoadBalancerNames`. |
| DeleteLoadBalancer | Deletes a load balancer; deleting one that does not exist succeeds. |
| CreateLoadBalancerListeners | Adds listeners. A conflicting listener on a port already in use is rejected with `DuplicateListener`. |
| DeleteLoadBalancerListeners | Removes listeners by `LoadBalancerPorts`. |
| ConfigureHealthCheck | Replaces the health check and re-arms Floci's checker. |
| RegisterInstancesWithLoadBalancer | Registers EC2 instances and returns the full registered set. |
| DeregisterInstancesFromLoadBalancer | Removes instances and returns the remaining set. |
| DescribeInstanceHealth | Returns `InstanceStates` for the named instances, or for every registered instance. |
| ModifyLoadBalancerAttributes | Updates cross-zone, access log, connection draining, connection settings, and additional attributes. Unsent members keep their current value. |
| DescribeLoadBalancerAttributes | Returns the full attribute structure. |
| ApplySecurityGroupsToLoadBalancer | Replaces the security groups. |
| AttachLoadBalancerToSubnets / DetachLoadBalancerFromSubnets | Adds or removes subnets and recomputes the availability zones and VPC. |
| EnableAvailabilityZonesForLoadBalancer / DisableAvailabilityZonesForLoadBalancer | Adds or removes availability zones. |
| AddTags / RemoveTags / DescribeTags | Tagging, keyed by `LoadBalancerNames`. |
| DescribeAccountLimits | Returns standard default Classic limits. |

## Not implemented

These Classic operations return `UnsupportedOperation` in the 2012-06-01 namespace rather than a
fabricated result:

`CreateAppCookieStickinessPolicy`, `CreateLBCookieStickinessPolicy`, `CreateLoadBalancerPolicy`,
`DeleteLoadBalancerPolicy`, `DescribeLoadBalancerPolicies`, `DescribeLoadBalancerPolicyTypes`,
`SetLoadBalancerPoliciesForBackendServer`, `SetLoadBalancerPoliciesOfListener`,
`SetLoadBalancerListenerSSLCertificate`.

Floci also does not run a Classic **data plane**: listener ports are recorded but no socket is
opened and no traffic is forwarded. Only the control plane and health checking are emulated.

## Behavior Notes

- Load balancer state is persisted through Floci storage and health checking is restarted on
  startup.
- A new load balancer gets AWS's default health check (`TCP:80`, interval 30, timeout 5,
  unhealthy 2, healthy 10) and AWS's default attributes (cross-zone off, access log off,
  connection draining off with a 300 second timeout, 60 second idle timeout).
- Health checks probe the instance's reachable local address. `HTTP:`/`HTTPS:` targets are probed
  with an HTTP request to the target's path and any 2xx/3xx is healthy; `TCP:`/`SSL:` targets are
  probed by connecting. TLS is not terminated — an `HTTPS:` target is probed over plain HTTP on
  the same port.
- Between registration and the first successful check an instance reports `OutOfService` with
  reason code `ELB`, as it does on AWS.
- An Auto Scaling group that names Classic load balancers in `LoadBalancerNames` has its instances
  registered and deregistered automatically, so a Terraform `min_elb_capacity` wait can be
  satisfied.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ELB_ENABLED` | `true` | Enable or disable the Classic ELB service |
| `FLOCI_SERVICES_ELB_MOCK` | `false` | When `true`, never probe: a registered instance is `InService` immediately |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws elb create-load-balancer \
  --load-balancer-name my-classic-elb \
  --listeners "Protocol=HTTP,LoadBalancerPort=80,InstanceProtocol=HTTP,InstancePort=8080" \
  --subnets subnet-12345678

aws elb configure-health-check \
  --load-balancer-name my-classic-elb \
  --health-check Target=HTTP:8080/,Interval=10,Timeout=3,UnhealthyThreshold=2,HealthyThreshold=2

aws elb register-instances-with-load-balancer \
  --load-balancer-name my-classic-elb \
  --instances i-1234567890abcdef0

aws elb describe-instance-health --load-balancer-name my-classic-elb

aws elb delete-load-balancer --load-balancer-name my-classic-elb
```

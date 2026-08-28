# Investigation epic: local VPC network data plane

## Outcome

Determine whether Floci should provide a bounded Linux network data plane that makes emulated
EC2 networking resources affect real traffic between local workloads.

The investigation must produce a tested architectural decision, not merely demonstrate that a
container can run Suricata or that Linux namespaces can exchange packets. A successful result
shows that AWS SDK and CloudFormation control-plane changes are reconciled into observable,
deterministic routing, filtering, DNS, endpoint, and logging behavior.

## Decision question

Should Floci translate its AWS-compatible VPC, route-table, security-group, network ACL, and
Network Firewall state into a managed Linux networking topology?

The possible decisions are:

1. adopt an embedded Podman-backed data plane for supported local environments;
2. adopt a separate privileged network-appliance backend controlled by Floci;
3. provide both backends behind one versioned reconciliation interface;
4. keep the data plane as an opt-in experimental capability; or
5. reject the approach and retain control-plane-only network emulation.

## Product hypothesis

A local data plane will improve Floci if an AWS API change can cause the corresponding observable
network effect without weakening protocol compatibility or making normal control-plane use depend
on privileged host access.

For example, after a route is changed to target a Network Firewall endpoint, traffic from an
attached workload should actually traverse the configured inspection path. An allow, drop, reject,
or alert decision should be observable in the workload and in the configured Floci logging service.

This is behavioral emulation. It is not an attempt to reproduce AWS's internal network fabric,
capacity, availability, latency, or proprietary implementation.

## Architectural boundary

Floci remains the AWS-compatible control plane:

```text
AWS SDK / CLI / CloudFormation
              |
      Floci service models
              |
   NetworkDataPlaneReconciler
              |
    NetworkDataPlaneBackend
       /                \
PodmanNetworkBackend  ApplianceBackend
              |
 Linux namespaces, routes, nftables/eBPF, Suricata, DNS
```

Floci remains responsible for:

- AWS request, response, validation, and error compatibility;
- account, region, VPC, subnet, and resource ownership;
- persistent desired state and lifecycle transitions;
- EC2, Route 53, Network Firewall, CloudWatch Logs, S3, and Firehose integration;
- dependency and in-use validation;
- stable identifiers and AWS-shaped status responses;
- reconciliation scheduling, retries, health, and recovery; and
- reporting a capability as ready only after the backend confirms it.

The backend is responsible for:

- isolated network domains and interfaces;
- address assignment, forwarding, and route programming;
- stateless and stateful policy enforcement;
- packet inspection and deterministic verdicts;
- DNS forwarding and private-zone attachment where selected;
- flow, alert, and health event production; and
- safe cleanup of backend resources owned by Floci.

The backend must not introduce a public convenience API. Users continue to configure networking
through the published AWS APIs and CloudFormation resource types.

## Candidate behavioral slice

The smallest credible slice is two workload containers in separate emulated subnets with a route
through an emulated Network Firewall endpoint:

```text
workload A
    |
subnet A namespace
    |
EC2 route-table decision
    |
Network Firewall endpoint namespace
    |
nftables stateless rules + Suricata stateful rules
    |
subnet B namespace
    |
workload B
```

The slice must demonstrate:

1. workload attachment through existing AWS resource state;
2. deterministic subnet addressing and routing;
3. route-table selection of a firewall endpoint;
4. allow, drop, reject, and alert outcomes;
5. policy updates without rebuilding unrelated workloads;
6. observable flow and alert records; and
7. restart reconciliation from Floci's persisted desired state.

Simply connecting containers to the same Podman network does not satisfy this slice because it
bypasses the AWS route and policy models being investigated.

## Candidate implementation mechanisms

### Network isolation and connectivity

- Linux network namespaces for VPCs, subnets, endpoints, or routing domains.
- Bridges and virtual Ethernet pairs for workload attachment.
- Linux routes and policy-routing tables compiled from EC2 route tables.
- Network address translation for explicitly supported internet and egress paths.
- Deterministic address allocation derived from VPC and subnet CIDRs.

The investigation must determine the minimum namespace granularity that preserves isolation while
remaining diagnosable and inexpensive. A namespace per subnet may be simpler to reason about than
a namespace per VPC, but it creates more interfaces and reconciliation work.

### Policy enforcement

- `nftables` as the baseline mechanism for stateless rules, security groups, network ACLs, NAT,
  counters, and packet marking.
- Suricata for stateful Network Firewall rules and AWS-compatible Suricata rule strings.
- eBPF only if a concrete experiment demonstrates a material capability or performance benefit;
  it must not be a prerequisite for the first useful slice.

Security groups are stateful and network ACLs are stateless. Compiling both into one undifferentiated
firewall chain would produce incorrect behavior and is not an acceptable prototype shortcut.

### DNS

- Connect the data plane to Floci's Route 53 private hosted-zone and Resolver state.
- Provide per-VPC or per-subnet DNS endpoints only where their lifecycle is represented by AWS
  resources.
- Preserve normal host DNS as an explicit forwarding path rather than silently mixing host and
  emulated private-zone answers.

### Observability

- Translate packet counters and Suricata events into stable internal flow and alert records.
- Deliver records through the configured Floci CloudWatch Logs, S3, or Firehose implementation.
- Correlate events with account, region, VPC, subnet, endpoint, firewall, policy, and rule IDs.
- Expose reconciliation health in existing AWS status fields and normal Floci diagnostics.

## Deployment models to compare

### Embedded Podman topology

Floci creates and owns networks, namespaces, helper containers, and inspection containers through
the configured container runtime.

Potential advantages:

- fits Floci's existing container-runtime integration;
- provides a single-product installation path;
- can reuse container lifecycle, image, retry, and cleanup infrastructure; and
- makes workload attachment straightforward for other container-backed Floci services.

Questions to answer:

- Which behaviors work with rootless Podman?
- Which require capabilities unavailable through the Docker-compatible API?
- Can topology changes be reconciled without restarting the Podman machine?
- How are leaked namespaces and interfaces detected and cleaned safely?
- Does Docker provide a meaningfully equivalent implementation path?

### Privileged network appliance

A dedicated Linux VM or privileged container owns namespaces, routing, firewall rules, and
Suricata. Floci sends desired state through a private, versioned reconciliation interface.

Potential advantages:

- isolates privileges from the Java process;
- provides direct access to Linux networking primitives;
- creates one consistent backend on macOS, Linux, and Windows hosts; and
- can expose precise health and reconciliation results.

Questions to answer:

- Is the appliance lifecycle manageable without becoming a second product?
- How are API compatibility, upgrades, and state recovery versioned?
- Can the control channel be authenticated and kept inaccessible to workloads?
- Can multiple Floci instances or accounts safely share one appliance?

### Host-platform implications

The actual packet machinery requires Linux. On macOS or Windows, it would run inside the Podman
machine or another Linux VM. The investigation must not assume that host-side network interfaces
or namespaces are directly visible from the Floci process.

Rootless operation is preferred, but the investigation must report capability boundaries rather
than fabricating success. If a behavior requires privileged networking, it must be isolated,
opt-in, and documented.

## Reconciliation and state model

The data plane must be derived from persisted AWS desired state. Linux objects are disposable
materializations, not the authoritative database.

Each reconciliation unit needs:

- an account and region scope;
- an owning AWS resource identifier;
- a desired-state generation or revision;
- observed backend state and health;
- a deterministic backend object name;
- idempotent create, update, and delete behavior;
- retry classification and bounded backoff; and
- orphan detection that cannot delete objects not owned by Floci.

Updates must be safe under concurrent AWS operations and after process interruption. A failed
reconciliation must leave the AWS resource in an accurate pending or failed state rather than
returning an unconditional `READY` status.

## AWS capability mapping to investigate

| AWS capability | Candidate local behavior |
|---|---|
| VPC and subnet | Isolated routing domain, CIDR, gateway, and attachment boundary |
| Elastic network interface | Stable virtual interface and addresses attached to a workload |
| Route table | Linux route or policy-routing decision compiled from AWS routes |
| Security group | Stateful ingress and egress policy tied to interfaces or identities |
| Network ACL | Ordered stateless subnet-boundary rules |
| Internet gateway | Explicit controlled forwarding to the host or appliance uplink |
| NAT gateway | Source NAT with connection tracking and observable counters |
| VPC peering | Routed connection between isolated VPC domains with AWS validation |
| Transit Gateway | Shared routing domain and attachment/propagation model |
| VPC endpoint | Local service destination and DNS behavior without public egress |
| Network Firewall endpoint | Mandatory inspection hop selected by EC2 route state |
| Stateless rule group | Ordered `nftables` rules and default actions |
| Stateful rule group | Suricata-compatible inspection and connection state |
| Firewall logging | Flow and alert delivery through configured Floci destinations |
| Route 53 private DNS | VPC-scoped resolution derived from hosted-zone associations |

The table is a research inventory, not a claim that every row belongs in the first implementation.

## Investigation workstreams

### 1. Platform capability matrix

- Characterize rootful and rootless Podman on Linux and through Podman Machine on macOS.
- Record required kernel features, capabilities, socket access, images, and minimum versions.
- Determine Docker compatibility and explicitly identify unsupported combinations.
- Measure startup time, steady-state memory, disk use, and cleanup behavior.
- Test operation when Floci itself runs inside a container.

### 2. Topology prototype

- Create two isolated subnets and attach deterministic test workloads.
- Compile an EC2 route into a real forwarding path.
- Prove that no unintended host or peer-network path bypasses the route.
- Change and delete the route while traffic is active.
- Restart Floci and reconstruct the topology without changing AWS resource IDs.

### 3. Network Firewall prototype

- Compile a stateless rule group into `nftables`.
- load an AWS-compatible Suricata rule string into Suricata;
- compose both through a firewall policy and endpoint;
- demonstrate allow, drop, reject, and alert cases;
- update rules using AWS update-token semantics; and
- prove that a route targeting the endpoint cannot bypass inspection.

### 4. Security group and network ACL semantics

- Demonstrate stateful return traffic for security groups.
- Demonstrate stateless, ordered, subnet-boundary behavior for network ACLs.
- Test IPv4, IPv6, protocol, port-range, and cross-reference cases selected for support.
- Determine how rule changes affect established connections.
- Compare observable results with documented AWS behavior.

### 5. DNS and service endpoints

- Resolve a Route 53 private-zone record only from its associated VPC.
- Forward an unrelated public query through an explicit resolver path.
- Model a gateway or interface endpoint for one Floci service.
- Verify endpoint-specific DNS and routing without leaking across accounts or VPCs.

### 6. Logging and diagnostics

- Deliver firewall flow and alert records to Floci CloudWatch Logs.
- Characterize ordering, timestamps, batching, retry, and destination failure.
- Provide an operator snapshot mapping AWS resources to backend objects.
- Capture packet and policy evidence sufficient to explain an unexpected verdict.
- Ensure secrets, payload content, and unrelated host traffic are not logged by default.

### 7. Lifecycle, isolation, and failure recovery

- Test concurrent create, update, and delete operations.
- Kill Floci and the backend during each reconciliation stage.
- Reconcile missing, stale, and partially created backend objects.
- Prove account, region, VPC, and subnet isolation with overlapping CIDRs.
- Detect and clean only positively identified Floci-owned orphans.
- Exercise disk pressure, backend unavailability, corrupt policy, and image-pull failure.

### 8. Performance envelope

- Measure reconciliation latency independently from packet throughput.
- Measure connection setup, steady-state throughput, CPU, memory, and packet loss.
- Test increasing workload, subnet, route, security-group rule, and firewall-rule counts.
- Establish explicit supported limits rather than copying AWS quotas that the backend cannot meet.
- Verify that enabling the data plane does not materially slow control-plane-only users.

## Prototype phases

### Phase 0: black-box platform characterization

Build disposable Linux-network experiments outside Floci. Record exact commands, privileges,
kernel behavior, Podman/Docker differences, cleanup requirements, and failure modes.

### Phase 1: backend seam and dry-run compiler

Define `NetworkDataPlaneBackend` and a desired-state representation. Compile existing Floci
resources into a deterministic plan without changing the host. Snapshot-test that plan and prove
that account and region boundaries are preserved.

### Phase 2: minimum routed topology

Materialize two subnets, two workloads, and one EC2 route. Support create, update, delete, restart,
and orphan detection. Do not add Network Firewall until routing cannot be bypassed.

### Phase 3: firewall vertical slice

Add one endpoint, stateless policy, stateful Suricata policy, and CloudWatch Logs output. Drive the
entire flow through AWS SDK or CloudFormation calls and verify traffic externally from workloads.

### Phase 4: policy boundaries

Add selected security-group and network-ACL semantics, private DNS, and one VPC endpoint. Run
cross-account, cross-region, overlapping-CIDR, restart, and failure-recovery tests.

### Phase 5: product decision

Compare the measured result with the acceptance criteria and choose a deployment model and support
tier. If adopted, create implementation epics per AWS capability rather than expanding the
prototype directly into an unbounded network rewrite.

## Acceptance criteria for the investigation

The investigation is complete only when all of the following are demonstrated or explicitly shown
to be infeasible:

1. AWS SDK or CloudFormation calls create a routed two-subnet topology without a custom public API.
2. An EC2 route-table update changes the path of real workload traffic.
3. A Network Firewall endpoint produces deterministic allow, drop, reject, and alert outcomes.
4. Stateless and stateful policy mechanisms remain distinct and match their selected AWS cases.
5. Flow and alert logs reach a configured Floci destination with traceable AWS resource IDs.
6. Floci reconstructs backend state after restart from persistent AWS desired state.
7. Two accounts and regions with overlapping CIDRs cannot observe or affect each other's traffic.
8. Failed or partial reconciliation produces accurate lifecycle status and can recover idempotently.
9. Cleanup removes only backend objects bearing verified Floci ownership metadata.
10. Rootless, privileged, macOS Podman Machine, Linux, and unsupported-host boundaries are recorded.
11. Resource use and throughput are measured against a declared local support envelope.
12. The final decision records whether to adopt, limit, or reject the data plane and why.

## Decision gates

Adoption requires all of these gates:

- **Compatibility:** selected AWS control-plane operations cause the documented local effects.
- **Isolation:** account, region, VPC, and subnet boundaries survive adversarial tests.
- **Recoverability:** reconciliation is idempotent across restart and partial failure.
- **Operability:** users can diagnose topology, policy, and verdicts without inspecting Java internals.
- **Portability:** supported host/runtime combinations and privilege requirements are explicit.
- **Safety:** Floci cannot accidentally alter or delete unrelated host networking objects.
- **Value:** the behavioral fidelity gained justifies the runtime, privilege, and maintenance cost.

Failure of the safety or isolation gate is a rejection criterion, not a deferred production-hardening
task.

## Non-goals

- Reproducing AWS's internal network architecture, scale, availability, or latency.
- Claiming equivalence to proprietary AWS threat intelligence or managed rule groups.
- Inspecting arbitrary host traffic outside explicitly attached Floci workloads.
- Making privileged networking mandatory for users who need only AWS control-plane emulation.
- Implementing every EC2 or Network Firewall operation in the prototype.
- Building packet forwarding or inspection directly in Java.
- Adding non-AWS public endpoints for topology management.

## Expected investigation artifacts

- architecture decision record with the selected deployment model;
- platform and privilege capability matrix;
- versioned desired-state and backend-interface proposal;
- reproducible topology and traffic fixtures;
- AWS SDK and CloudFormation vertical-slice tests;
- compatibility findings for each selected AWS behavior;
- performance and resource measurements;
- threat model and cleanup-safety review;
- documented limitations and support envelope; and
- sequenced implementation epics if the result is adoption.

## Relationship to service parity work

This epic does not replace the Network Firewall, EC2, Route 53, CloudWatch Logs, S3, Firehose, or
CloudFormation API parity epics. Those services define the AWS-compatible desired state. This epic
investigates whether Floci can materialize a useful subset of that state into a real local packet
path.

An operation may be fully control-plane compatible before its data-plane effect is implemented.
Documentation and tests must distinguish those levels so Floci never reports a false-green
capability.

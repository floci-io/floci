# Route 53 Resolver

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListFirewallDomainLists` | Lists all DNS Firewall domain lists, including the four AWS-managed lists computed per region. |
| `GetFirewallDomainList` | Returns a single DNS Firewall domain list by id, whether AWS-managed or custom. |
| `CreateFirewallDomainList` | Creates a custom DNS Firewall domain list with a random-suffix `rslvr-fdl-` id. |
| `DeleteFirewallDomainList` | Deletes a custom DNS Firewall domain list; AWS-managed lists cannot be deleted. |
| `CreateResolverEndpoint` | Creates an inbound (`rslvr-in-`) or outbound (`rslvr-out-`) resolver endpoint, returned immediately as `OPERATIONAL`. |
| `DeleteResolverEndpoint` | Deletes a resolver endpoint and returns its final description. |
| `GetResolverEndpoint` | Returns a resolver endpoint by id. |
| `ListResolverEndpoints` | Lists all resolver endpoints. |
| `ListResolverEndpointIpAddresses` | Lists a resolver endpoint's IP addresses from those it was created with, each `ATTACHED`; an address left for AWS to choose has no `Ip`. Not paginated. |
| `UpdateResolverEndpoint` | Updates a resolver endpoint's `Name` and `ResolverEndpointType`; `IpAddresses` changes are not modelled. |
| `CreateResolverRule` | Creates a resolver rule, returned immediately as `COMPLETE`; `ResolverEndpointId` is not validated against an existing endpoint. |
| `DeleteResolverRule` | Deletes a resolver rule and returns its final description. |
| `GetResolverRule` | Returns a resolver rule by id. |
| `ListResolverRules` | Lists all resolver rules. |
| `UpdateResolverRule` | Updates a resolver rule's mutable configuration. |
| `AssociateResolverRule` | Associates a resolver rule with a VPC, returned immediately as `COMPLETE`. |
| `DisassociateResolverRule` | Removes the association between a resolver rule and a VPC. |
| `GetResolverRuleAssociation` | Returns a resolver rule association by id. |
| `ListResolverRuleAssociations` | Lists all resolver rule associations. |
<!-- floci:actions:end -->

## Design notes

The four AWS-managed DNS Firewall domain lists (`AWSManagedDomainsAggregateThreatList`
and friends) are computed on every call from a fixed name list, with ids derived
deterministically from region+name (SHA-256 based) rather than stored — LZA's
`Custom::ResolverManagedDomainList` Lambda resolves a managed list's Id by Name and
needs it present and stable without any create call. This logic predates the rest of
this service and is untouched.

Everything else (custom firewall domain lists, resolver endpoints, resolver rules,
rule associations) is backed by real per-service storage, added this session. Ids use
the project's standard random-suffix convention (`rslvr-fdl-...`, `rslvr-in-...` /
`rslvr-out-...`, `rslvr-rr-...`, `rslvr-rrassoc-...`), distinct from the deterministic
managed-list ids. Resolver endpoint ids are direction-aware as in AWS: `rslvr-in-` for
`INBOUND` (and `INBOUND_DELEGATION`), `rslvr-out-` for `OUTBOUND`. Any other `Direction`
is rejected with `InvalidParameterException`.

Parameter rejections use the error code the operation actually models, which differs by
family: the resolver endpoint, rule and association operations model the singular
`InvalidParameterException`, while the DNS Firewall operations model `ValidationException`
and do not list `InvalidParameterException` at all.

`CreateFirewallDomainList`, `CreateResolverEndpoint` and `CreateResolverRule` are
idempotent on `CreatorRequestId`, as they are in real AWS: replaying a token returns the
resource it originally created instead of allocating a second one. The check runs after
the request's own validation, so a replayed token never excuses a malformed body. A
request without a `CreatorRequestId` opts out and always allocates a new resource.
Idempotency is scoped per account and per region, matching this regional service: the same
token replayed in another region creates that region's own resource rather than handing
back the first region's.

A token replayed with *different* parameters is a conflict, not a retry:
`CreateResolverEndpoint` and `CreateResolverRule` compare the retry against the stored
resource and raise `ResourceExistsException`, which both operations model.
`CreateFirewallDomainList` is the deliberate exception — it models no conflict error at
all, so a mismatched retry still returns the original list rather than an invented error
code. See `issues/route53resolver-firewall-domain-list-retry-conflict.md`.

For `CreateResolverEndpoint` the comparison covers the actual `IpAddresses` entries,
not just how many there are, so a retry that keeps the count but changes a subnet or
address is a conflict. The member is `IpAddresses`, as AWS names it; its list shape is
`IpAddressesRequest` and each element is an `IpAddressRequest`, which is the name earlier
builds of this service mistook for the wire name.
That spelling is still accepted so existing callers keep working, but it is not the
documented one, and a retry that switches between the two is not a conflict because the
comparison is on contents. Order is not significant. The modelled `ResolverEndpoint` shape has
`IpAddressCount` and no IP list, so the addresses are recorded in a side store rather than
on the resource and never reach the response. If that record is ever missing for a stored
endpoint, the retry is reported as a conflict rather than compared on count alone — with
nothing to compare against, sameness cannot be established, and a loud error is preferable
to a success that may not match the request.

## Resolver Rules and DNS Resolution

Resolver rules steer the DNS resolution Floci performs for containers wired to its embedded DNS
server, which is how cluster containers resolve (see
[Route 53](route53.md#private-hosted-zone-dns-resolution)). What each rule type does:

- **`FORWARD`** sends a matching query to the rule's `TargetIps` over UDP, each target on its own
  `Port` (default 53), in a random order with the next target tried when one does not answer, as in
  AWS. Only IPv4 targets are used, and a rule left with no usable target is ignored.
- **`SYSTEM`** carries no targets and hands the query back to Floci's own resolution, which is how
  AWS has it carve a subdomain out of a broader forwarding rule: a `FORWARD` rule for
  `corp.internal` plus a `SYSTEM` rule for `acme.corp.internal` forwards everything under
  `corp.internal` except that subtree.
- **`RECURSIVE`** means the same thing here. In AWS it is the autodefined `Internet Resolver` rule
  that resolves anything no other rule covers, which is what Floci resolving the name itself is.
- **`DELEGATE`** is stored and returned by the API but does not affect resolution.

**Matching** follows AWS: a rule matches a name that equals its `DomainName` or is a subdomain of it,
on label boundaries, and `.` matches everything. Where several rules match, the one with the most
labels in its domain name wins.

**Association, account and region** all have to line up. A rule does nothing in AWS until it is
associated with a VPC, and Route 53 Resolver is regional, so a rule created in one region governs
nothing in another. Floci reads all three from the query's source address: a cluster container's
address maps to the account that owns the cluster, the cluster's region, and its
`resourcesVpcConfig.vpcId`. Only that account's associations are consulted, and only rules from the
querying region among them. `AssociateResolverRule` stores whatever `VPCId` the caller names without
proving ownership of it, so scoping the lookup to the querying account's own partition is what stops
one account redirecting another account's cluster DNS by naming its VPC. The stores carry no region
in their keys, so a rule's region is read back from the ARN Floci minted for it. An address no
service claims, or a cluster with no resolvable VPC id, belongs to no VPC, so no rule applies and
resolution is unchanged; Floci logs a warning when it cannot determine a running cluster's container
addresses, because rules then silently do not reach it.

**Precedence over private hosted zones** matches AWS: when a private hosted zone and a resolver rule
both match a name, the rule wins and the query is forwarded instead of answered from the zone's
records. A name whose only match is the zone, or whose matching rule is not associated with the
querying VPC, still resolves from the zone.

**Names Floci owns are never forwarded.** The emulator's own suffixes (`localhost.floci.io`,
`localhost.localstack.cloud`, `floci.hostname`, `floci.dns.extra-suffixes`) and `ip-*.ec2.internal`
addresses resolve locally whatever the rules say, the way AWS autodefines system rules for its own
internal domains so a rule for `.` cannot break the platform.

**A rule whose targets are all unreachable fails that query with SERVFAIL** rather than falling back
to the upstream resolvers, which would answer with the wrong address for a name the rule placed
elsewhere. Every other name resolves as usual. This needs no switch of its own: it applies wherever
cluster DNS does, governed by `floci.services.eks.embedded-dns`.

## Limitations

- **All create/update operations complete synchronously.** Real AWS transitions a
  resolver endpoint through `CREATING`, a resolver rule through its own provisioning
  states, before reaching a terminal status. This emulator returns the terminal state
  (`OPERATIONAL` for endpoints, `COMPLETE` for rules and associations) immediately —
  matching the "validate-and-echo" convention used elsewhere in this codebase (see
  `ServiceCatalogService.copyProduct`, `CS-021`).
- **`UpdateResolverEndpoint` only applies `Name` and `ResolverEndpointType`.** Real AWS
  additionally allows updating `IpAddresses` (adding/removing resolver IPs); that is not
  modelled.
- **`CreateResolverRule`/`UpdateResolverRule` do not validate `ResolverEndpointId`
  against an existing endpoint.** Any non-blank string is accepted.
- **No region-scoping is enforced on the API surface.** The four
  stores go through `StorageFactory`, so every key is prefixed with the calling
  credential's account id and custom resources *are* isolated per account: one account
  cannot read, update or delete another's endpoints, rules, associations or domain
  lists. Neither region nor VPC is part of the key, though, so a custom resource created
  in one region is visible from every other, and `VPCId` on an association does not filter
  any API response. Both do decide which rules steer DNS resolution, as above: that path reads the
  region back from the rule's ARN and the account from the store partition. The AWS-managed
  domain lists are the deliberate exception: they
  are derived per region from the name list rather than stored, so they are region-scoped
  and visible to every caller, as they are in AWS.
- **Inbound endpoints stay metadata.** An inbound endpoint exists so something outside the VPC can
  query into it; Floci stores its direction and address count but binds no listener on its
  addresses, so nothing resolves through one.
- **A `FORWARD` rule's `ResolverEndpointId` is not used when forwarding.** AWS sends the query from
  the outbound endpoint's addresses inside the VPC; Floci forwards it from itself, since there is no
  separate VPC network path to send it over.
- **Cross-account rule sharing is not modelled.** AWS lets an account associate a rule shared with
  it through RAM; Floci reports every rule as `NOT_SHARED` and models no rule policy, so a rule and
  its associations always live in one account and resolution only ever consults that account's own.
- **DNS Firewall rule groups, query logging and DNSSEC validation do not affect resolution.**

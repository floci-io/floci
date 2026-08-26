# ec2: ten dispatched operations undocumented on upstream/main

**Found**: 2026-08-25, during the wave-2 lessons audit fix pass on `feature/ec2` (w2-fix-ec2 agent)
**Severity**: 3 (docs drift only — no behaviour defect; every operation below works)
**Status**: OPEN — inherited from `upstream/main`, deliberately left out of `feature/ec2` on the
lead's scoping ruling. Candidate for a standalone upstream docs PR.

## What was found

While documenting `feature/ec2`'s own IPAM additions, a programmatic reconciliation of
`Ec2QueryHandler`'s action switch against the tables in `docs/services/ec2.md` showed 39 dispatched
operations with no documentation row. Diffing the switch between `upstream/main` and `feature/ec2`
splits those cleanly:

- **29 are this branch's own** (18 IPAM, 6 EBS-encryption defaults, `ExportTransitGatewayRoutes`,
  `GetSecurityGroupsForVpc`, `ModifyVpcEndpoint`) and are now documented on the branch, in
  `12212e6c7` and the commit that follows it.
- **10 predate the branch** and are listed below. `upstream/main` dispatches all ten and documents
  none of them.

## The inherited gap

| Operation | Area |
|---|---|
| `AttachVolume` | Volumes |
| `DetachVolume` | Volumes |
| `DescribeSnapshots` | Volumes / snapshots |
| `CreateFlowLogs` | Flow logs |
| `DeleteFlowLogs` | Flow logs |
| `DescribeFlowLogs` | Flow logs |
| `RequestSpotInstances` | Spot |
| `DescribeSpotInstanceRequests` | Spot |
| `CancelSpotInstanceRequests` | Spot |
| `DescribeVpnGateways` | VPN gateways |

Reproduce with:

```
git show upstream/main:src/main/java/io/github/hectorvent/floci/services/ec2/Ec2QueryHandler.java
```

and compare its `case "..." ->` labels against the `| Action |` rows in `docs/services/ec2.md`.

## Why it was left alone

`feature/ec2` is a bug-fix branch for the wave-2 audit findings plus docs for the operations it
introduces. Documenting upstream's pre-existing gap would put ten unrelated rows in this branch's
diff and invite the reviewer to ask why a fix branch is writing docs for Spot and flow logs. The
work is worth doing, just not here.

## Note for whoever picks this up

`docs/services/ec2.md` is hand-maintained, not generated. `tools/docs/regen_action_docs.py` only
rewrites marker-delimited tables for services registered in `tools/docs/services.yaml`, and
`Ec2QueryHandler.java` sits on that file's `deferred_handlers` allowlist — which is why
`make docs-check` passes today despite the gap. Adding rows by hand is correct; registering EC2 on
the generator would convert all ~130 existing rows to the generated format and is a separate
decision.

The EC2 row in `docs/services/index.md` carries `78`, which matches neither the ~156 actions
dispatched nor the rows documented. It appears to be a curated figure rather than either total, so
it was left untouched rather than guessed at.

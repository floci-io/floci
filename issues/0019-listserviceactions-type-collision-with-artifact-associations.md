# ListServiceActions fix (0018) collided with a pre-existing Type discriminator

**Severity**: 3 — latent bug in an already-`accepted` op, caught and fixed before
anyone hit it in practice.

## What

Issue 0018 added `ServiceCatalogService.listServiceActions()`, scanning
`associationStore` for `Type == "SERVICE_ACTION"`. That discriminator value was
already in use by `associateServiceActionWithProvisioningArtifact` and
`batchAssociateServiceActionWithProvisioningArtifact` (`ServiceCatalogService.java`
lines 593, 633) for a *different* kind of record — the association between a service
action and a provisioning artifact, which has `ProductId`/`ProvisioningArtifactId`/
`ServiceActionId` fields but no `Id` field. A bare `Type` match would pull those
association rows into `ListServiceActions` output as phantom entries with an empty
`Id`, once any `AssociateServiceActionWithProvisioningArtifact` call had been made.

## How found

Investigating the `CreateProvisioningArtifact`/etc. op family (a different task),
noticed `associateServiceActionWithProvisioningArtifact` reused the same `Type`
string. Added a regression test to the already-existing
`ServiceCatalogServiceActionConsumerTest`
(`listServiceActions_ignoresProvisioningArtifactAssociationRows`) and verified it
red without the fix, green with it.

## Fix

`ServiceCatalogService.listServiceActions()` now filters on `Type == "SERVICE_ACTION"
&& node.has("Id")` — matching the guard `updateServiceAction`'s pre-existing scan
already used for the same reason.

## Status

Fixed same session, before `ListServiceActions` had been relied on by anything else.
Regression test added and falsifiability-verified (reverted the fix, confirmed red;
restored, confirmed green).

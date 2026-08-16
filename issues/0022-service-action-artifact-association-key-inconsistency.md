# Service-action/artifact association: 3 incompatible storage keys, disassociate never deleted

**Severity**: 4 — associations created one way (e.g. batch) were invisible to and
unremovable by the other paths (e.g. single-op); disassociate silently didn't work
at all. Caught before any of the four ops shipped as `accepted`.

## What

Four operations manage the same conceptual (product, provisioning artifact, service
action) relationship, but computed three different `associationStore` keys for it:

- `associateServiceActionWithProvisioningArtifact` (single):
  `associationId("service_action", productId, artifactId + "|" + serviceActionId)`
  — the correct, AWS-triple-scoped key.
- `batchAssociateServiceActionWithProvisioningArtifact`:
  `associationId("service_action", artifactId, serviceActionId)` — missing
  `productId` entirely.
- `batchDisassociateServiceActionFromProvisioningArtifact`: a hand-rolled
  `"serviceaction|" + productId + "|" + artifactId + "|" + serviceActionId` — note
  `serviceaction` with no underscore, distinct from every other key in this file
  which uses `service_action`.

None of the three matched each other. An association created via one path could not
be found (for query) or removed (for disassociate) via either of the other two.

Separately, `disassociateServiceActionFromProvisioningArtifact` (the single op)
validated the product and artifact existed but **never called
`associationStore.delete(...)` at all** — a complete no-op beyond validation, on top
of the key mismatch.

## How found

Writing wire tests for this op family (part of this session's servicecatalog
full-parity batch). Deliberately wrote two tests that cross the single/batch
boundary (associate via batch, disassociate via the single op, and the reverse) —
these are exactly what caught the key mismatch; same-path round trips would not
have.

## Fix

- `disassociateServiceActionFromProvisioningArtifact`: now looks up the association
  by the correct key and calls `associationStore.delete(id)`.
- `batchAssociateServiceActionWithProvisioningArtifact`: key now includes
  `productId`, matching the single op's formula.
- `batchDisassociateServiceActionFromProvisioningArtifact`: now uses
  `associationId("service_action", productId, artifactId + "|" + serviceActionId)`
  instead of the hand-rolled, misspelled key.

All three write/delete paths now use the identical key formula.

## Status

Fixed same session. Wire tests added
(`ServiceCatalogServiceActionArtifactAssociationConsumerTest`), including two tests
that specifically cross the single/batch boundary to prove interoperability,
falsifiability-verified at method granularity, docs updated, journaled `accepted`.

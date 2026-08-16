# ServiceAction storage key mismatch broke Delete/Update; List was a hardcoded stub

**Severity**: 4 — Delete/Update were unusable for any freshly-created service action;
caught before acceptance, not shipped.

## What

`ServiceCatalogService.createServiceAction` stored its record under key
`"service_action|" + id`. `deleteServiceAction` and `updateServiceAction` both looked
up / re-stored under the raw `id` instead:

- `deleteServiceAction(id)` called `require(associationStore, id, ...)`, an exact-key
  lookup — this always threw `ResourceNotFoundException` for an action that had just
  been created, because no entry existed at that key.
- `updateServiceAction(id, request)` located the record by scanning for a matching
  `Id` *field* (works regardless of key), but then wrote it back with
  `associationStore.put(id, action)` — under the raw-id key, not the original
  `"service_action|"`-prefixed one. This created a duplicate: the stale original
  stayed at the old key, a second copy appeared at the new one. Subsequent updates
  would non-deterministically hit whichever copy the scan visited first, and the
  stale original was never reachable by `delete`.
- Separately, `listServiceActions()` was a hardcoded stub that always returned an
  empty array, regardless of what existed in `associationStore` — inconsistent with
  Create/Update/Delete all being fully storage-backed.

## How found

Writing wire tests for `CreateServiceAction`/`UpdateServiceAction`/
`DeleteServiceAction`/`ListServiceActions` (all four landed `needs-tests` in this
session's servicecatalog full-parity batch). A round-trip test — create, then delete,
then confirm gone via list — surfaced the key mismatch immediately; a
`ListServiceActions` returning empty made the round-trip impossible to write at all
until the stub was fixed.

## Fix

- `ServiceCatalogService.java`: `createServiceAction` now stores under raw `id`,
  matching what `deleteServiceAction`/`updateServiceAction` already assumed.
- `ServiceCatalogService.listServiceActions()` added: scans `associationStore` for
  `Type == "SERVICE_ACTION"` and returns them.
- `ServiceCatalogJsonHandler.listServiceActions()` now builds real summaries
  (`Id`, `Name`, `Description`, `DefinitionType`) from the service layer instead of
  an empty array.

## Status

Fixed same session. Wire tests added (`ServiceCatalogServiceActionConsumerTest`,
including a dedicated no-duplicates-after-update regression test), falsifiability-
verified at method granularity, docs updated, journaled `accepted`.

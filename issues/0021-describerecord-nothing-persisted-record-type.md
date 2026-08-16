# DescribeRecord always 404'd — nothing ever persisted a RECORD row

**Severity**: 4 — the operation was completely non-functional for every valid
`RecordId` any other operation had ever returned; partially fixed (import path)
before shipping as `accepted`.

## What

`ServiceCatalogService.describeRecord(id)` scans `associationStore` for
`Type == "RECORD"` matching the given id. Nothing in the codebase ever wrote a row
with that `Type` — every record-producing operation (`ImportAsProvisionedProduct`,
`ExecuteProvisionedProductServiceAction`, and presumably `ProvisionProduct`/
`TerminateProvisionedProduct`) generated a fresh random `RecordId` string in the
*handler* layer purely for the immediate response and discarded it. `DescribeRecord`
therefore returned `ResourceNotFoundException` for every record id any caller could
possibly have, including ones returned moments earlier by a successful call.

## How found

Writing a wire test for `DescribeRecord` as part of this session's servicecatalog
full-parity batch. The natural round-trip test (call an op that returns a `RecordId`,
then describe it) failed immediately.

## Fix (scoped)

`ServiceCatalogService.importAsProvisionedProduct` now generates its own `RecordId`,
persists a `Type: "RECORD"` row in `associationStore` with the record's fields, and
returns it in the result. `ServiceCatalogJsonHandler.importAsProvisionedProduct`
now reads that `RecordId` back instead of generating its own throwaway UUID, so the
id returned to the caller is the same one `DescribeRecord` can find.

**Not fixed in this pass**: `ExecuteProvisionedProductServiceAction`'s handler
(`ServiceCatalogJsonHandler.java` ~line 506) still generates and discards its own
`RecordId` the same way import used to. `ProvisionProduct`/
`TerminateProvisionedProduct` were not investigated for the same pattern. Scoped
down to keep this fix contained to the op family under test; the same fix pattern
(generate the id in the service layer, persist a `RECORD` row, have the handler read
it back) applies whenever those operations are tested.

## Status

Fixed for the `ImportAsProvisionedProduct` → `DescribeRecord` path. Wire test added
(`ServiceCatalogRecordAndResourceQueryConsumerTest`), falsifiability-verified at
method granularity.

**Update**: `ExecuteProvisionedProductServiceAction` fixed the same way when that op
was tested next (same session) — service now persists a `RECORD` row, handler reads
its id back instead of a discarded UUID. Round-trip verified via
`ServiceCatalogExecuteServiceActionConsumerTest
.executeProvisionedProductServiceAction_returnsDescribableRecord`, which calls
`DescribeRecord` on the returned id. `ProvisionProduct`/`TerminateProvisionedProduct`
were not investigated — still a documented gap if either generates a RecordId the
same discard-after-response way.

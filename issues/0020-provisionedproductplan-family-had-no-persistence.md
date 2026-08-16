# Provisioned product plan family had no persistence at all

**Severity**: 4 — the entire operation family (5 ops) was non-functional for any
plan created through the API; caught before any of the five shipped as `accepted`.

## What

`ServiceCatalogService.createProvisionedProductPlan` built and returned a plan
`ObjectNode` without ever storing it anywhere. As a result:

- `describeProvisionedProductPlan` read from `provisionedProductStore` (the store for
  actual provisioned products, not plans) — always 404'd for a freshly-created plan.
- `deleteProvisionedProductPlan` did no lookup or deletion at all — validated
  `PlanId` was non-blank and returned success unconditionally, silently no-op.
- `listProvisionedProductPlans` scanned `provisionedProductStore` filtering
  `provisionProductId.equals(product.get("Id"))` — conceptually wrong (this would
  return an actual *provisioned product* record disguised as a plan if one happened
  to share the id, never a real plan).
- `executeProvisionedProductPlan` did not validate the plan existed before "executing"
  it.

Separately, the three handler methods for this family (`create`, `describe`, `list`)
each expected different field names for the same conceptual plan object —
`PlanId`/`PlanName`/`ProvisionProductId` in one, `Id`/`Name`/`ProductId` in another —
so no single object shape could satisfy all three without touching handler code.

## How found

Writing wire tests for all five ops in this family (part of this session's
servicecatalog full-parity batch). A round-trip test (create → describe) was the
first thing attempted and immediately 404'd.

## Fix

Added persistence via `associationStore` with a new `Type: "PROVISIONED_PRODUCT_PLAN"`
discriminator, per this project's established convention (`CORE_API` block: reuse
the generic association store with a new `Type` rather than adding a dedicated
store). The stored plan object carries both field-name variants
(`Id`/`PlanId`, `Name`/`PlanName`, `ProductId`/`ProvisionProductId`) so every
existing (unmodified) handler method's response-building code works unchanged.
`describeProvisionedProductPlan`/`deleteProvisionedProductPlan` now read from
`associationStore`; `listProvisionedProductPlans` now scans it filtered by `Type`
and (optionally) `ProvisionProductId`; `executeProvisionedProductPlan` now validates
the plan exists before returning its `SUCCEEDED` record.

## Status

Fixed same session. Wire tests added
(`ServiceCatalogProvisionedProductPlanConsumerTest`, 8 tests covering the full
create→describe→execute→delete round trip plus not-found and list-scoping paths),
falsifiability-verified at method granularity, limitations documented, journaled
`accepted`.

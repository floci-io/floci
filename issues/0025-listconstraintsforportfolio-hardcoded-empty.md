# ListConstraintsForPortfolio was a hardcoded-empty stub

**Severity**: 3 — real gap, `CreateConstraint` (already `accepted`) fully persists
data this query needed and never used. Caught before shipping as `accepted`.

## What

`ServiceCatalogService.listConstraintsForPortfolio(portfolioId, productId)`
validated the portfolio existed, then unconditionally returned `List.of()`. Every
constraint created via `CreateConstraint` — already `accepted` from earlier this
session, and stored with exactly the `PortfolioId`/`ProductId` fields this query
would need to filter on — was invisible to this operation. Same shape as issue 0018
(`ListServiceActions`).

## Fix

Now scans `associationStore` for rows with a matching `PortfolioId` and a
`ConstraintId` field (to exclude other association types sharing the store, per
the guard pattern established in issue 0019), optionally further filtered by
`ProductId` when the caller supplies one.

## Status

Fixed same session. Wire tests added
(`ServiceCatalogPortfolioQueryConsumerTest`), including one that specifically
verifies `ProductId` scoping excludes constraints on other products,
falsifiability-verified at method granularity, docs updated, journaled `accepted`.

This is the **9th and final bug found** in this session's servicecatalog
full-parity batch acceptance-gate pass. All 56 batch ops are now resolved: 55
`accepted`, 1 `blocked` (genuine escalation, no store exists for
`DescribeServiceAction` and core-API rules forbid a single op inventing one).

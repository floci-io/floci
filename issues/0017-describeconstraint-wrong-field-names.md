# DescribeConstraint returned empty ConstraintId/Type

**Severity**: 3 — real defect in this session's own batch output, caught before
acceptance, not shipped.

## What

`ServiceCatalogJsonHandler.describeConstraint` (line 399/404 before fix) read the
stored constraint object's fields as `Id` and `ConstraintType`. `ServiceCatalogService
.createConstraint` stores the object with fields `ConstraintId` and `Type`. Every
`DescribeConstraint` call therefore returned `ConstraintDetail.ConstraintId` and
`ConstraintDetail.Type` as empty strings, regardless of the real values.

## How found

Writing a value-level wire test (`ServiceCatalogConstraintConsumerTest
.describeConstraint_returnsConstraintIdAndType`) for this session's servicecatalog
full-parity batch — `DescribeConstraint` was one of the 55 ops the batch landed as
`needs-tests`. A type/presence-only assertion would not have caught this; asserting
the actual id and type values did.

## Fix

`ServiceCatalogJsonHandler.java:399,404` — changed `constraint.path("Id")` to
`constraint.path("ConstraintId")` and `constraint.path("ConstraintType")` to
`constraint.path("Type")`. `createConstraintResponse` and `updateConstraint`'s
handlers already used the correct field names; only `describeConstraint` had the
mismatch.

## Status

Fixed same session. Wire test added (`ServiceCatalogConstraintConsumerTest`),
falsifiability-verified at method granularity, docs updated
(`docs/services/service-catalog.md`), journaled `accepted`.

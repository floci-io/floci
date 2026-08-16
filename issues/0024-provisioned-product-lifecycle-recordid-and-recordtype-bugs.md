# UpdateProvisionedProduct(Properties)/TerminateProvisionedProduct: RecordId discarded, wrong RecordType

**Severity**: 3 — three more instances of the RecordId-discard pattern (issues
0021/0023), plus a wrong hardcoded `RecordType` and a status conflation in
`TerminateProvisionedProduct`. All caught before shipping as `accepted`.

## What

Same shape as issues 0021 and 0023: `UpdateProvisionedProduct`,
`UpdateProvisionedProductProperties` and `TerminateProvisionedProduct`'s handlers
each generated a throwaway random `RecordId` and discarded it, so `DescribeRecord`
could never find any of their records.

Separately, `TerminateProvisionedProduct`'s handler hardcoded `RecordType` to
`"PROVISION"` (not a value that operation should ever report) and set the record's
`Status` field to `"TERMINATED"` — conflating the *provisioned product's* status
(which is legitimately `TERMINATED`) with the *record's* execution-outcome status
(which should be `SUCCEEDED`, matching every other record this session produces).

## Fix

All three service methods now generate their own `RecordId`, persist a `RECORD` row
in `associationStore`, and return it; the handlers read it back instead of
generating their own. `TerminateProvisionedProduct`'s `RecordType` corrected to
`TERMINATE_PROVISIONED_PRODUCT`, `Status` corrected to `SUCCEEDED`.

`UpdateProvisionedProduct` itself still does not apply any request fields to the
provisioned product (documented as a limitation, not fixed — matches this
codebase's validate-and-echo convention for state transitions that aren't modelled).

## Status

Fixed same session. Wire tests added
(`ServiceCatalogProvisionedProductLifecycleConsumerTest`), each including a
`DescribeRecord` round-trip to prove the RecordId fix works, falsifiability-verified
at method granularity, docs updated, journaled `accepted`.

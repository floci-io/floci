# DescribeCopyProductStatus always 404'd — CopyProduct never persisted its token

**Severity**: 3 — same bug shape as issues 0020/0021, applied to an already-accepted
op (`CopyProduct`, accepted earlier this session before the full-parity batch).
Caught before `DescribeCopyProductStatus` itself shipped as `accepted`.

## What

`ServiceCatalogService.describeCopyProductStatus(token)` looks up
`associationStore` by the given token. `copyProduct` generated a fresh
`id("copy")` token and returned it directly without ever persisting anything under
it — every `DescribeCopyProductStatus` call 404'd, including for a token returned
moments earlier by a successful `CopyProduct` call. This is the fourth instance of
the same pattern this session (issues 0020, 0021, and this one) — an op returns an
id in its response without persisting anything the id can look up later.

## Fix

`copyProduct` now stores a `{Status: "SUCCEEDED", TargetProductId: <new product id>}`
row under the token in `associationStore` before returning it.
`describeCopyProductStatus`'s existing lookup logic was already correct — it just
had nothing to find.

## Status

Fixed same session. Wire test added (`ServiceCatalogDescribeQueryConsumerTest
.describeCopyProductStatus_returnsSucceededWithTargetProductId`),
falsifiability-verified, docs updated (limitation about `DescribeCopyProductStatus`
"not implemented" removed — it now works, just always reports terminal status
synchronously).

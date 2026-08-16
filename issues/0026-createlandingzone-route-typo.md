# CreateLandingZone was wire-unreachable — route path had an extra hyphen

**Severity**: 4 — implemented operation, wire-unreachable at the real AWS path;
its own existing test posted to the same wrong path so it never caught this.

## What

`ControlTowerController.createLandingZone` was annotated `@Path("/create-landing-zone")`.
Every sibling route in the same controller uses the pattern `/<verb>-landingzone`
(no hyphen inside "landingzone"): `/list-landingzones`, `/get-landingzone`,
`/update-landingzone`, `/delete-landingzone`, `/reset-landingzone`. The real AWS
Control Tower wire route for `CreateLandingZone` is `POST /create-landingzone` —
confirmed against `tools/packets/gate.py`'s botocore-derived routing table, which
reported `MISSING` for this operation before the fix and `SUPPORTED` (`via: jaxrs`,
route `POST /create-landingzone`) after.

The existing wire-level test
(`ControlTowerControllerIntegrationTest.createLandingZoneReturnsArnAndOperationIdentifier`)
posted to the same wrong path (`/create-landing-zone`), so it passed regardless of
whether the real AWS route worked — exactly the "green suite proves nothing"
pattern (pitfall 9 in this project's packet-pipeline docs).

## How found

Re-checking the classify-only sweep's stale "17 oversized-file ops" figure against
the current, more accurate `gate.py --report`. Investigated why `gate.py` still
flagged `controltower/CreateLandingZone` as `MISSING` despite the controller,
service method, and a test all already existing — the routing annotation was wrong.

## Fix

`@Path("/create-landing-zone")` → `@Path("/create-landingzone")` on the controller
method. Updated the existing integration test to post to the corrected path.

## Status

Fixed. `gate.py --task-id aws-api/controltower/create-landing-zone` now reports
`SUPPORTED`. Full `ControlTower*Test` suite: 39/39 green.

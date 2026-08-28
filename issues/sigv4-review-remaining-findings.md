# SigV4 / presigned-URL review — remaining findings

Companion to branch `fix/sigv4-auth-bypass` (PR fixing the SigV4 signature-forgery
auth-bypass). This ledgers everything from the original review pass that is
**not** fixed in that PR, plus two findings from the original brief that turned
out, on investigation, not to be real bugs.

Source review: `.temp/wt-l10-lambdaowner/review-2657.md` (41 findings across 6 files).

## Fixed in fix/sigv4-auth-bypass

- `SigV4Validator.java:104` (elasticache) — unregistered access key fell back to
  itself as the signing secret (self-signable auth bypass). Fixed: fail closed
  when `findSecretKey` returns empty.
- `RdsSigV4Validator.java:104` — identical bug, identical fix.
- `SigV4Validator.java:80` (elasticache) — `expectedUsername != null && user != null && ...`
  let a token that omits `User` bypass the username binding. Fixed: drop the
  `user != null` conjunct.

## Investigated, NOT bugs — do not re-fix

- **`RdsSigV4Validator.java:80`** (originally flagged as the same missing-User
  bypass as the ElastiCache file). Not exploitable here: `dbUser` is in the
  required-parameters check at line 74-75, so `dbUser` can never be null by the
  time line 80 runs. The `clientUsername == null` branch is the documented
  "may be null to skip" contract, and the only live caller
  (`PostgresProtocolHandler.java:84`) always passes a real username. Confirmed
  by a RED test that could not be made to fail — the exploit doesn't exist.

- **`PreSignedUrlFilter.java:30` — hardcoded "test"/"test" credential accepted
  under auth enforcement.** Originally flagged HIGH. This is a deliberate,
  already-tested convenience credential, not a presigned-URL-specific hole:
  `S3Service.isKnownAccessKey` (S3Service.java, private method backing
  `authorizeSignedRequest`/`authorizeS3Read`/`authorizeObjectWrite`) has the
  identical `LEGACY_ACCESS_KEY_ID.equals(accessKeyId)` fallback, and
  `S3AuthEnforcementIntegrationTest.java` has two tests — line 210
  (`signedRequestWithLocalAccessKeyCanReadPrivateObject`, header-signed) and
  line 222 (`presignedRequestWithLocalAccessKeyCanReadPrivateObject`, presigned)
  — that assert this credential works identically on *both* paths under
  enforced auth. I removed the fallback from `PreSignedUrlFilter.resolveSecretKey`
  as a RED test, confirmed it broke both of the above tests plus two others in
  that suite, and reverted. If this convenience credential is ever revoked,
  it has to happen symmetrically in `S3Service.isKnownAccessKey` too — that's
  an architectural decision (removes a documented local-dev affordance for the
  entire S3 emulator, not a 3-line patch), out of scope for a signature-forgery
  fix PR.

- **`PreSignedUrlFilter.java:128` — signature verified but no bucket/key
  authorization check.** Originally flagged HIGH. Checked: `S3Service` has no
  bucket-owner/account field anywhere, and `authorizeS3Read`/`authorizeObjectWrite`
  return early ("allow") for *any* signed request with a known access key,
  regardless of path — this is the same idiom used for ordinary header-signed
  requests via `S3RequestAuthorizationParser` + `S3Controller` (see
  `S3Controller.java:720,797,802,...` calling `authorizeObjectRead`/
  `authorizePutObject`). Multi-account bucket ownership enforcement doesn't
  exist anywhere in this codebase's S3 implementation; this isn't a gap
  `PreSignedUrlFilter` introduced. Real per-account S3 authorization would be a
  separate, much larger feature.

  **Related, unresolved, worth a second look:** `S3RequestAuthorizationParser.parse`
  returns `RequestAuthorization(true, accessKeyId)` for a presigned request
  based only on the *presence* of `X-Amz-Algorithm` in the query string — it
  does not itself verify the signature. Actual signature verification happens
  separately in `PreSignedUrlFilter`. These two are independent checks that
  currently line up because the filter runs on every request with
  `X-Amz-Algorithm` set. If any route ever reaches `S3Controller`'s
  `authorizeObjectRead`/etc. without `PreSignedUrlFilter` having run first,
  that route would treat `signed=true` (and thus "authorized") for a payload
  whose signature was never checked. I did not find such a route, but I also
  didn't exhaustively verify filter registration/ordering across every
  `@Provider`. Worth a targeted audit.

## Deferred — real bug, not fixed here (plumbing too large for this PR)

- **`RdsSigV4Validator.java:52` — signed host/port never verified against the
  actual DB endpoint; region/service not pinned (cross-resource token
  replay).** Real: `validate()` builds its canonical request purely from the
  token's own `host:port`/region/service and never compares any of it to the
  endpoint this particular proxy fronts. A token signed for any other RDS
  instance in the same account validates here.

  Plumbing needed to fix properly (mapped, not implemented):
  - `RdsSigV4Validator.validate(...)` needs an `expectedHost`, `expectedPort`,
    `expectedRegion` (and pin `service.equals("rds-db")`).
  - `RdsAuthProxy` constructor needs to carry those through to the
    `PostgresProtocolHandler`/`MySqlProtocolHandler` call sites
    (`PostgresProtocolHandler.java:84`, and the MySQL equivalent).
  - `RdsProxyManager.startProxy(...)` already receives `advertisedHost` (used
    today only for `tlsCertificates.ensureHost(advertisedHost)`) and
    `proxyPort` — both would need to be threaded into `RdsAuthProxy` instead of
    being dropped.
  - Region isn't threaded down to `RdsProxyManager` at all today; it's
    available at the `RdsService` call sites (`instanceRegion` computed just
    above each `proxyManager.startProxy(...)` call) but not passed.
  - Seven call sites in `RdsService.java` construct/start proxies:
    lines 628, 1123, 1319, 1931, 3803, 3887, 4008. All seven would need the new
    parameters.
  - **Caveat that needs its own investigation before touching this:**
    `RdsService.proxyEndpoint(int proxyPort)` calls
    `currentContainerNetworkResolver.resolvePublishedPort(proxyPort)` — the
    port a real client signs against (the published/advertised port) is not
    guaranteed to equal `proxyPort`, the port the proxy binds to internally.
    Comparing the wrong one would turn a security fix into an outage for every
    RDS IAM-auth user. This needs to be nailed down before writing the
    validator-side check, not guessed at in the same PR as the signature fix.

- **`ContainerLauncher.java` — lost in-flight volume reference race between
  `ensureCodeVolume` and `releaseCodeVolumeReference`.** Not a SigV4 bug — the
  7th HIGH finding from the same review pass, unrelated feature area.
  `ensureCodeVolume` does
  `volumesInFlight.computeIfAbsent(volName, ...).incrementAndGet()` — the map
  lookup and the increment are two separate steps. A concurrent
  `releaseCodeVolumeReference` for the same volume can run its
  `computeIfPresent` (decrement to 0, remove the mapping) in between: the first
  thread's `incrementAndGet()` then mutates an `AtomicInteger` that is no
  longer in the map. The new launch's in-flight reservation is silently lost,
  so `cleanupSupersededVolumes` sees no reservation and can delete the volume
  while that launch is still pre-`create()` — Docker auto-creates an empty
  volume and the container gets an empty `/var/task`.
  - Fix: make the increment atomic with map membership —
    `volumesInFlight.compute(volName, (k, c) -> { if (c == null) c = new AtomicInteger(); c.incrementAndGet(); return c; });`
    (compute's function runs under the bin lock, serialized against
    `computeIfPresent`). Not fixed here — different subsystem, deserves its
    own PR and its own tests.
  - **Collision note:** PR #2657 (`fix/lambda-placeholder-owner-account`)
    touches `ContainerLauncher.java` extensively (credential-triad
    all-or-nothing logic) but does *not* touch `ensureCodeVolume`/
    `releaseCodeVolumeReference`/`volumesInFlight` — no overlap, safe to fix
    independently whenever picked up.

## Resolved collision: PR #2657 had reintroduced the false "safe" comment

PR #2657 (`fix/lambda-placeholder-owner-account`) merged into `upstream/main`
as `be892864d` and added this comment directly above the vulnerable
`iamService.findSecretKey(accessKeyId).orElse(accessKeyId)` line in **both**
`SigV4Validator.java` and `RdsSigV4Validator.java` (not just the ElastiCache
file, as first flagged here):

> "Deliberately no fallback for a bare 12-digit account ID here: trusting an
> unregistered numeric access key paired with the well-known "test" secret
> would let any client forge an IAM-auth token for an arbitrary account and
> authenticate as any matching cache user. An unregistered key falls back to
> itself, unchanged (never a valid secret), so it always fails the signature
> check below."

This is the exact false safety claim `fix/sigv4-auth-bypass` proves wrong with
a RED test (self-signing with `secret == accessKeyId` produces a matching
signature). Rebasing `fix/sigv4-auth-bypass` onto `upstream/main` after #2657
merged produced real conflicts on both files (this comment vs. the fail-closed
fix); both were resolved by keeping the fail-closed fix and dropping the
misleading comment entirely. No follow-up needed here — the comment is gone
from both files as of this branch's rebase.

## Medium/low findings not touched by this PR (from review-2657.md)

By file:line, one line each — none of these are exploitable auth bypasses,
just correctness/robustness/availability issues:

**SigV4Validator.java (elasticache)**
- `:87` — no upper bound on `X-Amz-Expires`, no future-dated-token rejection (medium)
- `:102` — credential-scope date/region/service accepted without cross-validation (medium)
- `:160` — catch-all exception handler logs only at debug, masking real operational faults (low)

**RdsSigV4Validator.java**
- `:86` — no not-yet-valid check, unbounded `X-Amz-Expires` (medium)
- `:96` — credential-scope date vs `X-Amz-Date` not cross-checked; malformed scope rejected with no log (medium)
- `:114` — canonical query string sorted by param name only, not name-then-value (low, availability false-negative)
- general — `URLDecoder.decode` treats literal `+` as space, can cause spurious mismatches (low)

**PreSignedUrlFilter.java (s3)**
- `:109` — `X-Amz-Credential` is double URL-decoded (JAX-RS already decodes it) (medium)
- `:132` — path parsing in the custom-signature branch assumes a leading slash that `UriInfo.getPath()` doesn't have; can select the wrong bucket/key (medium)
- `:100` — malformed `X-Amz-Date` throws unhandled from `isExpired()`, producing a 500 instead of 400/403 (medium)
- `:150` — credential scope date/region/service accepted verbatim, not cross-checked against `X-Amz-Date`/expected service/region (medium)
- `:154` — `signedHeaders.split(";")` NPE risk; no requirement that `host` is among signed headers (low)
- `:215` — all verification exceptions swallowed at debug level (low)
- `:138` — fail-open (no signature check at all) if both `isAuthEnforced()` and `shouldValidateSignatures()` are false (low, config-dependent)

**LaunchedContainerAwsEnv.java** (unrelated feature area, same review pass)
- `:152` — inconsistent credential triple when host env is partially set (medium)
- `:148` — host AWS secrets forwarded into container env, visible via `docker inspect` (medium)
- `:146` — warning-once gate can silently stop warning on subsequent real-credential forwards (low)
- `:168` — `URI.create(flociEndpoint).getHost()` can NPE-adjacent produce `FLOCI_HOSTNAME=null` (low)
- `:160` — `SessionCreds` fields not null-checked before interpolation into env (low)

**ContainerLauncher.java** (beyond the HIGH race above, unrelated to SigV4)
- one uncaught exception permanently kills the volume-cleanup scheduler (medium)
- small-code `/var/task` copy uses the non-strict tar path; can start container with partial/empty code (medium)
- `copyFileToContainer` never joins its writer thread or surfaces tar failures (medium)
- `createTarFromDir` silently drops symlinked directories, mangles symlinks (medium)
- `launch()` cleanup only triggers on `RuntimeException`; an `Error` leaks the runtime-api port and container (low)
- credential all-or-nothing filter misses legacy `AWS_SECURITY_TOKEN` (low)
- `expectExtensions` counts extensions that failed to exec-launch, forcing a full 5s stall every cold start (low)
- Lambda version hardcoded to `$LATEST` in env and log-stream name despite versioned functions (low)

**KubernetesPodLauncher.java** (unrelated feature area, same review pass)
- runtime API port released even when server stop fails/times out (medium)
- `launch()` cleanup only triggers on `RuntimeException`; Errors leak the port and pod (medium)
- interrupted stop leaves pod running and port leaked while handle reports STOPPED (medium)
- unauthenticated plain-HTTP code/layer download URLs are guessable (medium)
- `awaitRunning` swallows `InterruptedException` without restoring the interrupt flag (low)
- `ensureCaConfigMap` check-then-act race causes redundant concurrent applies (low)

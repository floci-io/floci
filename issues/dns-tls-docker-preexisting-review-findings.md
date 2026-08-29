# [see-something] security/robustness: pre-existing gaps found reviewing DNS/TLS/Docker files touched by fix/dns-docker-tls-reliability-hardening

Found via the mandatory local code-review pass (`review-local.ts`, isolated per-file review) required before opening
fix/dns-docker-tls-reliability-hardening. All items below are in code that predates this branch's 5 commits
(verified with `git log -S` against the specific method/field each finding names) — the branch itself only added a
DNS spoof suffix, TLS spoof SANs for virtual-hosted S3 hostnames, and a brand-new `DockerRetry` helper (whose own
findings were fixed inline in that PR, not ledgered here). Filing rather than fixing inline to avoid growing a
reliability-hardening PR into an unrelated security-hardening one.

## TlsConfigSource.java (severity 4 — HIGH)
`generateSelfSignedCert` (pre-existing, `git log -S"private void generateSelfSignedCert"` -> 5befe8b6) writes the
private key PEM via `Files.writeString(keyFile, ...)` with no POSIX permission control, so under a default umask
the key is created world-readable. This key backs a cert distributed as a trust anchor with broad AWS SANs, so a
local attacker who reads it can MITM all TLS traffic from any client that installed the anchor.
- Fix: create the key file with `PosixFilePermissions.asFileAttribute(rw-------)` before writing (or
  `Files.setPosixFilePermissions` immediately after), and restrict the `tls/` directory to 0700.
- Related mediums in the same file, same pre-existing method/class, not separately filed: no atomicity across
  cert/key/metadata writes (crash mid-write can pair a new cert with an old key); `isSelfSigned` never verifies the
  persisted key matches the cert's public key; the AWS wildcard SANs on a distributed trust-anchor cert have no
  nameConstraints limiting blast radius; `validateFileExists` for user-provided cert/key is TOCTOU-only (no PEM
  parse/expiry/match check); `FLOCI_VERSION` is persisted in metadata but never compared, so generator-side fixes
  don't trigger regeneration for existing installs.

## EmbeddedDnsServer.java (severity 4 — HIGH)
`readName` (pre-existing, predates this branch's suffix-only diff) recurses into itself for every DNS
compression pointer with no depth/loop guard — a crafted query with a cyclic pointer drives unbounded recursion to
`StackOverflowError`, which is an `Error` (not `Exception`) so the `catch (Exception)` in `handleQuery` does not
catch it; it propagates into the Vert.x event-loop thread. A single spoofed UDP packet can DoS the DNS server (and
every spawned container that depends on it for resolution).
- Fix: track visited pointer offsets (or a max-jump count) across the whole parse; reject rather than recurse on
  reference to an offset not strictly earlier than the current one.
- Related mediums in the same pre-existing forwarding path (`forwardToUpstreams`, `git log -S"forwardToUpstreams"`
  -> 2d306414, predates this branch): forwarded responses are accepted without verifying source address/port or
  TXID/question match against the query (classic cache-poisoning exposure); one blocking Vert.js worker thread is
  used per forwarded query for up to 1.5s × upstream count, so a burst can exhaust the default worker pool;
  queries with no upstreams configured (or where all upstreams fail) are silently dropped instead of answered with
  SERVFAIL, so clients block for their full resolver timeout instead of failing fast.
- Also note (low, directly relevant to this branch's new `spoofAwsEndpoints` flag, disclosed in the PR body's
  scope notes rather than filed as a defect): when DNS spoofing is enabled, containers still get public fallback
  resolvers by default, so if the embedded forwarder is briefly unreachable, traffic meant to be captured can
  escape to real AWS. This is a known, disclosed interaction, not a bug in the new code.

## ContainerLifecycleManager.java (severity 3 — MEDIUM, several findings, pre-existing code the branch's
12-line DockerRetry integration happens to sit inside)
- `stopAndRemove`: the log-stream `Closeable` is only closed when stop or remove succeeds; if the daemon errors on
  both, the log-follow transport leaks for the life of the process.
- `startCreated`: a `connectToNetworkCmd` failure after the container is already running is logged at WARN and
  swallowed — the container keeps running on the default bridge with no error signal to callers that depend on
  network placement (DNS injection, isolation).
- Native-mode `resolveEndpoint` can NPE / throw `NumberFormatException` on unpublished ports (the sibling
  `readPublishedHostPort` guards both cases; this path doesn't), aborting `startCreated` after the container is
  already running and force-removing an otherwise-healthy container.
- `ensureSharedVolume` swallows `initSharedVolumeRoot` failure, logs at WARN, and proceeds — the workload container
  then starts against a volume root still root:root 0755, surfacing as an unrelated-looking EACCES at runtime
  instead of a clear startup error.
- `buildHostConfig`'s dynamically allocated host ports are never released back to `PortAllocator` on a subsequent
  create/start failure, so repeated failures under daemon overload can exhaust the port pool.
- `findByName` swallows `listContainersCmd` errors (logged at DEBUG) and returns `Optional.empty()`, making a
  transient daemon error indistinguishable from "container does not exist" for adopt-or-create callers.
- `adopt` has no recovery path if inspect/start fails on the stopped container it's adopting; unlike
  `createAndStart`, there's no cleanup/fallback to recreate, so callers can get wedged re-adopting an unstartable
  container.

None of these block fix/dns-docker-tls-reliability-hardening — they predate its 5 commits and are unrelated to
DNS/TLS/Docker-retry/RDS-test-deflake reliability work. Worth a dedicated hardening pass, likely split by file
given the different blast radii (TLS key exposure and DNS cache-poisoning/DoS are security-severity; the
ContainerLifecycleManager items are operational-robustness severity).

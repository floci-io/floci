# [see-something] robustness: pre-existing ContainerLifecycleManager gaps

Found via the mandatory local code-review pass (`review-local.ts`, isolated per-file review) over the Docker
retry/conflict work. Every finding below was **re-verified against the current code on this branch**, not carried
over from the branch it was first noticed on — that check mattered: one finding from the original pass has since
been fixed and is recorded as such rather than re-filed.

None are introduced by this PR, which only adds create-conflict adoption and two `DockerRetry` argument/locale
fixes. Filing rather than fixing inline to keep this PR scoped.

## Already fixed — not re-filed
`ensureSharedVolume` / `initSharedVolumeRoot` previously swallowed an init failure, logged at WARN, and proceeded
against a volume root still root:root 0755. It now throws so the caller leaves the volume unmemoised and retries on
the next launch. Recorded here only so a future reviewer doesn't resurrect the stale finding.

## ContainerLifecycleManager.java (severity 3 — MEDIUM, verified present)

- **`stopAndRemove` leaks the log stream on double failure.** The `Closeable` is closed only when
  `stoppedOrMissing || removedOrMissing`. If the daemon errors on *both* stop and remove, neither flag is set and
  the log-follow transport leaks for the life of the process — precisely the case where the daemon is unhealthy and
  leaks compound.

- **`startCreated` swallows a network-attach failure.** A `connectToNetworkCmd` failure after the container is
  already running is logged at WARN and swallowed, so the container keeps running on the default bridge with no
  error signal to callers that depend on network placement (DNS injection, isolation) — the container looks healthy
  while being on the wrong network.

- **`findByName` cannot distinguish "daemon error" from "not found".** It catches every exception, logs at DEBUG,
  and returns `Optional.empty()`. This is worth more than its severity suggests now: this PR's conflict-adoption
  path calls `findByName` to decide whether to adopt, so a transient daemon error during that lookup degrades to
  "no such container" and the adoption correctly-but-unhelpfully declines, surfacing the original 409. Adoption
  stays safe (it fails closed, never adopting the wrong container), but the diagnostic is misleading.

- **Host ports are never returned to `PortAllocator`.** `buildHostConfig` allocates dynamic host ports and no path
  releases them on a later create/start failure (there is no release call anywhere in the class), so repeated
  failures under daemon overload can exhaust the pool.

Worth a dedicated hardening pass over this class. Each is independently actionable and none blocks this PR.

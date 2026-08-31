# [see-something] robustness: pre-existing ContainerLifecycleManager gaps

Found via the mandatory local code-review pass (`review-local.ts`, isolated per-file review) over the Docker
retry/conflict work. Every finding below was **re-verified against the current code on this branch**, not carried
over from the branch it was first noticed on — that check mattered: one finding from the original pass has since
been fixed and is recorded as such rather than re-filed.

None are introduced by this PR, which only adds create-conflict adoption and two `DockerRetry` argument/locale
fixes. Filing rather than fixing inline to keep this PR scoped.

## ContainerLifecycleManager.java (severity 3 — MEDIUM, verified present)

- **`ensureSharedVolume` still fails open for the current launch.** `initSharedVolumeRoot` does now throw on a
  non-zero init status, but `ensureSharedVolume` wraps it in `computeIfAbsent` and catches the `RuntimeException`,
  logs at WARN, and returns `null`. Leaving the volume unmemoised does mean the *next* launch retries — but the
  launch that requested ownership init proceeds and mounts a volume still root:root 0755, so a non-root workload
  fails later with an opaque EACCES instead of a clear init failure. Fixing only the inner method looks like a fix
  and isn't one; the fail-open lives at the caller.

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

- **Unguarded parse in the native-mode `resolveEndpoint` path.** It calls
  `Integer.parseInt(binding[0].getHostPortSpec())` with no null or `NumberFormatException` guard, unlike the sibling
  `readPublishedHostPort` which guards both. Podman and some daemon versions emit null/non-numeric specs, and the
  throw escapes `startCreated` — turning a container that started fine into a launch failure, and via
  `createAndStart`'s catch, into a removal of that healthy container.

- **`stopAndRemoveStrict` closes the log stream before stopping.** `stopAndRemove` deliberately closes it *after*
  stop/remove so the terminal callback can drain the final tail; the strict variant closes first, so the final tail
  is lost on every strict-cleanup path.

- **`initSharedVolumeRoot` never closes its `WaitContainerResultCallback`.** It is `Closeable`; on the 60s timeout
  path the wait connection stays open until GC, a slow leak across repeated init failures.

- **Shared-volume init is memoised on volume name alone.** Two callers requesting the same volume with different
  `ownerUid`/`ownerGid`/`rootPermissions` silently get the first caller's ownership, with no warning.

## DockerRetry.java (severity 3 — MEDIUM, pre-existing)

- **Cause-chain cycle detection only handles self-loops.** Both loops in `isTransientIo` walk `getCause()` and break
  only on `c.getCause() == c`. A cycle of length >= 2 (A->B->A, constructible via `initCause`) spins forever, hanging
  the calling thread inside what is supposed to be a fast classification. A visited-set or a bounded depth counter
  fixes it. Pre-existing; this PR only touched the message-matching and argument-validation lines in that method.

Worth a dedicated hardening pass over this class. Each is independently actionable and none blocks this PR.

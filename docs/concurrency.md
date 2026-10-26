# Local concurrency primitives

These internal primitives coordinate work owned by one Floci process. They do not
store authoritative service state or replace resource ownership and readiness checks.

## Bounded keyed work

`KeyedWorkQueue<K>` treats a key as a request to read current authoritative state.
It runs at most the configured number of virtual-thread workers, retains at most
the configured number of distinct keys, and permits only one active worker per key.
Repeated notifications coalesce. A notification during a pass schedules another
pass; dirty keys return at the tail so one busy resource does not monopolize work.

Submission never runs the action on the caller's thread. A duplicate can be accepted
when the queue is full; a new key is rejected. Callers must retain authoritative
state and a fair periodic recovery path for rejected or externally missed changes.
Worker failures release admission and are logged; they do not cause immediate
error-retry loops. A snapshot exposes retained keys, active workers, completed
passes, coalescing, rejection and failure counts.

Close rejects new work, discards queued notifications and interrupts admitted work.
The owning service still implements its resource-drain and cancellation contract.
Callbacks must establish account/region context explicitly and validate resource
identity before committing effects. A completed Docker start or HTTP connection
is not proof of service readiness.

## Versioned state notifications

`VersionedSignal` closes the gap between reading state and waiting for a change:

1. Read the signal version.
2. Read the authoritative predicate under its normal ownership rules.
3. If the predicate is false, wait only while that version is unchanged.
4. Recheck the predicate after notification, timeout or a spurious wakeup.

Publish a signal after the state mutation succeeds. The signal monitor is short-lived;
never wait while holding the authoritative-state lock. Waiting uses a monotonic
deadline and propagates interruption. Resource closure must also signal waiters.
The signal does not own an executor or retain a list of listeners.

## Distributed ownership

These are local scheduling tools. Cross-process coordination would additionally
need explicit ownership, fencing, durable publication/replay, failure recovery and
idempotent effects. Adding an external watch alone does not supply those contracts.

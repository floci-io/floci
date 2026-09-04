# floci-app-sync

Standalone AppSync GraphQL engine sidecar for [Floci](https://github.com/floci-io/floci),
built to resolve [floci-io/floci#2917](https://github.com/floci-io/floci/issues/2917):
graphql-java (~3.8MB) was baked into Floci's own native image unconditionally, even for
builds that never touch AppSync.

Rather than gating the dependency with `@IfBuildProperty` or hand-rewriting a GraphQL
engine, this follows the pattern already established for Athena/[floci-duck](https://github.com/floci-io/floci-duck):
move the heavy engine into its own sidecar service, entirely outside Floci's own JVM and
native image, that Floci talks to over plain HTTP.

## Status: POC, verified working end-to-end

This lives inside the `floci` repo for now (branch `poc/2917-appsync-graphql-sidecar`) so the
whole change — this service plus Floci's side of the wiring — can be reviewed as one PR. It
will move to its own repo (`floci-io/floci-app-sync`) once the approach is agreed, the same
way `floci-duck` is its own repo today.

**Verified**, not just written: `mvn package`/`mvn test` pass here, the Docker image builds,
and Floci's full `appsync` test package (510 tests) passes against it — including the two
integration suites (`AppSyncExecutionIntegrationTest`, `AppSyncAuthIntegrationTest`) that
exercise the real containerized sidecar over actual HTTP, not mocks. See **Testing** below to
reproduce that yourself.

**What's in this increment:**
- Schema compilation (`POST /schemas/{apiId}`) — SDL parsing, the 17 AWS custom scalars,
  unknown-directive validation, field-level `@aws_auth`/IAM-policy wrapping.
- Query execution (`POST /schemas/{apiId}/execute`) — ported from Floci's `QueryExecutor`/
  `AppSyncErrorFormatter` unchanged.
- Schema eviction (`DELETE /schemas/{apiId}`).
- IAM field-level authorization, ported (`io.github.hectorvent.flociappsync.iam.IamPolicyEvaluator`,
  full Phase 1-4 policy evaluation, trimmed of the `SimulatePrincipalPolicy`/`SimulateCustomPolicy`
  convenience methods that AppSync doesn't need). This is a deliberate near-term duplication
  with Floci's own copy — the plan is to extract both into a shared core library once this
  proves out, not to keep two copies indefinitely.
- Floci's side of the wiring: `FlociAppSyncManager`/`FlociAppSyncClient` (mirroring
  `FlociDuckManager`/`FlociDuckClient`), lazy container start, `floci.services.appsync.engine.url`
  escape hatch, and `AppSyncService`/`AppSyncExecutionController`/`SchemaCreationWorker`
  rewired to call this service. graphql-java is fully removed from Floci's own `pom.xml`.

**What's explicitly NOT in this increment (and not a regression — it doesn't exist in
Floci's old in-process engine either):** resolver → data-source dispatch. Floci's own
`docs/services/appsync.md` lists this as "Not Implemented" (Phase 8/9): no `DataFetcher` is
wired to a stored `Resolver`/`DataSource` anywhere yet, so query fields resolve to graphql-java's
default (usually `null`). When that gets built, it belongs here — dispatching to Floci's own
DynamoDB/Lambda/HTTP/etc. endpoints exactly as real AppSync calls real AWS services — fed by
resolver-wiring metadata that Floci gathers fresh from its own storage and bundles into each
`/execute` call, the same way Athena bundles live Glue table metadata into each call to
floci-duck (not a persisted replica in this service, and not a per-field callback into Floci).

## Wire contract

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/schemas/{apiId}` | Compile SDL, register the schema. Body: `{"sdl": "..."}`. 200 on success; 400 with `{message, extendedData}` (same shape as Floci's `AwsException` code-errors) on a bad schema. |
| `DELETE` | `/schemas/{apiId}` | Evict a compiled schema. |
| `POST` | `/schemas/{apiId}/execute` | Run a query. Body: `{query, variables?, operationName?, authContext}`. 200 with `{data, errors}`; 502 `GraphQLSchemaException` if no schema is registered; the `AppSyncTransportException` status/errorType/message otherwise. |
| `GET` | `/q/health` | Liveness/readiness (via `quarkus-smallrye-health`) for Floci's lazy-start poll. |

`authContext` mirrors `AppSyncAuthContext` (`io.github.hectorvent.flociappsync.graphql.auth`) —
Floci does content-type validation, body parsing, API lookup, and top-level request
authentication (API key/IAM/Cognito/OIDC/Lambda authorizer) itself, then sends the resolved
identity/authType/deniedFields/callerContext here for field-level enforcement only.

## Testing

There are three layers, each cheaper and narrower than the next. Run them in order — no
need to reach for the last one just to check a small change.

### 1. This service's own unit tests

```bash
cd floci-app-sync
mvn test
```

Runs `AppSyncEngineResourceTest` (schema compile → execute round trip, no-schema 502,
invalid-SDL 400) against an in-memory Quarkus instance. No Docker involved.

### 2. Build the image

```bash
cd floci-app-sync
mvn package
docker build -t floci/floci-app-sync:latest .
```

This is the tag `FlociAppSyncManager` looks for on the Floci side. Floci's
`ImageCacheService` checks for the image locally *before* attempting a pull, so building it
under this exact tag is enough — no registry or publish step needed for local testing.

### 3. Floci's integration suite against the real sidecar

With the image built (step 2) and Docker running, from the repo root:

```bash
mvn test -Dtest='io.github.hectorvent.floci.services.appsync.**'
```

This is the real end-to-end check: it starts the actual `floci-app-sync` container (via
`FlociAppSyncManager`, the same lazy-start path a live Floci instance uses), drives it
through the full `StartSchemaCreation` → `POST /v1/apis/{apiId}/graphql` flow over real HTTP,
and stops/removes the container on shutdown. `AppSyncExecutionIntegrationTest` and
`AppSyncAuthIntegrationTest` are the ones that actually touch the container; the rest of the
package (`AppSyncServiceTest`, `AppSyncExecutionControllerTest`, etc.) are unit tests with
`FlociAppSyncClient` mocked.

If you'd rather point at a sidecar you're running yourself (e.g. via `mvn quarkus:dev` in
`floci-app-sync/`, for faster iteration than a full image rebuild per change), set
`floci.services.appsync.engine.url` (or `FLOCI_SERVICES_APPSYNC_ENGINE_URL`) to its address —
Floci skips container management entirely when that's set.

### Manual smoke test

Useful for poking at the wire contract directly without Floci in the loop:

```bash
docker run --rm -p 3010:3010 floci/floci-app-sync:latest &

curl -s -X POST localhost:3010/schemas/demo \
  -H 'Content-Type: application/json' \
  -d '{"sdl":"type Query { hello: String }"}'

curl -s -X POST localhost:3010/schemas/demo/execute \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ hello }"}'
# {"data":{"hello":null}} — expected: no resolver dispatch yet (Phase 8/9, see above)

curl -s localhost:3010/q/health
```

# Design: Amazon Bedrock AgentCore emulation

Status: proposed. Tracked by the AgentCore epic and phase issues on this fork.

## Goal

Emulate Amazon Bedrock AgentCore well enough that AWS SDK/CLI clients can create,
read, update, delete, list, and invoke agent runtimes against Floci on port 4566.
No real agent execution: the control plane maintains a stateful registry, the data
plane returns a canned response.

Designed clean-room from the AWS public API reference only.

Two AWS services are involved:

| Service | API version | SigV4 signing name | Protocol | Role |
|---|---|---|---|---|
| `bedrock-agentcore-control` | 2023-06-05 | `bedrock-agentcore` | REST JSON (restJson1) | stateful CRUD registry |
| `bedrock-agentcore` | 2024-02-28 | `bedrock-agentcore` | REST JSON (binary payload) | `InvokeAgentRuntime` canned-response stub |

## Scope (MVP)

- Phase 1 — Agent Runtime CRUD (`bedrock-agentcore-control`)
- Phase 2 — Agent Runtime Endpoints
- Phase 3 — `InvokeAgentRuntime` data-plane stub (`bedrock-agentcore`)
- Phase 4 — Tagging + Workload Identity
- Phase 5 (optional) — Gateway + Memory primitives

Out of scope for the MVP: real inference, streaming invoke semantics, gateway
rules, policy engine, registry, payment, browser, code interpreter, dataset,
evaluator, harness, token vault, credential providers, IAM enforcement. All are
reachable via the upgrade path below.

## Floci integration conventions

Follows `AGENTS.md` + `CONTRIBUTING.md`. Verified against the current codebase.

1. **Packages** — `services/bedrockagentcorecontrol/` and `services/bedrockagentcore/`:
   `*Controller.java` (thin JAX-RS), `*Service.java` (`@ApplicationScoped`), `model/` POJOs.
2. **Controller** — class `@Path("/")`, `@Produces/@Consumes(APPLICATION_JSON)`; full path
   per method. Inject `Service`, `RegionResolver`, `ObjectMapper`. Take the raw body as a
   `String` and parse with `ObjectMapper.readTree`. First line: `regionResolver.resolveRegion(headers)`.
3. **Service registration** — one `descriptor(...)` line in `core/common/ResolvedServiceCatalog.java`
   (+ import the controller). Template: the `bedrockruntime`/`pipes` entries. For a REST-JSON service:
   `defaultProtocol`/`supportedProtocols` = `ServiceProtocol.REST_JSON`; `targetPrefixes = Set.of()`;
   `credentialScopes = Set.of("bedrock-agentcore")` (SigV4 signing name — verify against the SDK model);
   `resourceClasses = Set.of(<Controller>.class)` (required for enable/disable);
   `storageKey = "bedrockagentcore"` (control plane) / `null` (stateless data plane).
4. **Config** — `config/EmulatorConfig.java`: add a `ServicesConfig` accessor + nested interface
   (`@WithDefault("true") boolean enabled();`). Env: `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CONTROL_ENABLED`
   and `FLOCI_SERVICES_BEDROCK_AGENT_CORE_ENABLED`.
5. **Storage** — `StorageFactory.create("bedrockagentcore", "<file>.json", new TypeReference<Map<String,V>>(){})`.
   Account partitioning is automatic (`AccountAwareStorageBackend`). Two-constructor DI (public `@Inject`
   builds the store; package-private takes the store for unit tests). Keys: `"<entity>:<region>:<id>"`.
   Not-found → `store.get(k).orElseThrow(() -> new AwsException("ResourceNotFoundException", msg, 404))`.
6. **ARNs** — only via `regionResolver.buildArn("bedrock-agentcore", region, resource)`; account via
   `regionResolver.getAccountId()`.
7. **Errors** — throw `AwsException(code, message, status)`. The global `AwsExceptionMapper` builds the
   JSON body but does NOT set `X-Amzn-Errortype`, which SDK v2 restJson1 needs. Follow the
   `services/rdsdata/RdsDataController` / `services/batch/BatchController` pattern: return
   `Response.status(s).header("X-Amzn-Errortype", code).entity(new AwsErrorResponse(code, message)).build()`.
   Codes: `ValidationException` (400), `ResourceNotFoundException` (404), `ConflictException` (409).
8. **Reflection** — `@io.quarkus.runtime.annotations.RegisterForReflection` on every model/DTO.
9. **Pagination** — copy the `services/acm/AcmService` cursor pattern (sort by ARN, Base64(JSON)
   `nextToken` carrying the last ARN, `skip`/`limit`; bad token → `ValidationException`).
10. **clientToken** — accept-and-ignore (scheduler pattern); key uniqueness yields `ConflictException`.
11. **Tests** — unit `*ServiceTest.java`; integration `*IntegrationTest.java` as `@QuarkusTest` +
    RestAssured hitting routes directly (NOT an SDK client — that goes in `compatibility-tests/`).
    `@TestMethodOrder(OrderAnnotation.class)` for lifecycles. No `Authorization` header → region
    `us-east-1`, account `000000000000`.
12. **Docs** — `docs/services/bedrock-agentcore.md`, register in `tools/docs/services.yaml`,
    run `make docs-sync` (CI runs `make docs-check`). Add to `mkdocs.yml` nav.

### Routing
No existing service claims `/runtimes` (verified). Control-plane and data-plane controllers both
live under `/runtimes` on distinct method paths/verbs — safe under Floci's path-based JAX-RS dispatch.

## Pinned wire contracts

All verified against the AWS API reference. **Note verbs and trailing slashes.**

### Agent Runtime (control plane)

| Operation | Method + Path | Success | Notes |
|---|---|---|---|
| CreateAgentRuntime | `PUT /runtimes/` | 202 | required: `agentRuntimeArtifact`, `agentRuntimeName` (`[a-zA-Z][a-zA-Z0-9_]{0,47}`), `networkConfiguration`, `roleArn` |
| GetAgentRuntime | `GET /runtimes/{agentRuntimeId}/?version={v}` | 200 | full runtime shape |
| ListAgentRuntimes | `POST /runtimes/?maxResults={n}&nextToken={t}` | 200 | **POST**, no body; `agentRuntimes[]` + `nextToken` |
| UpdateAgentRuntime | `PUT /runtimes/{agentRuntimeId}/` | 202 | required: `agentRuntimeArtifact`, `networkConfiguration`, `roleArn`; bumps version |
| ListAgentRuntimeVersions | `POST /runtimes/{agentRuntimeId}/versions/?maxResults=&nextToken=` | 200 | **POST**, no body |
| DeleteAgentRuntime | `DELETE /runtimes/{agentRuntimeId}/?clientToken={t}` | 202 | body `{agentRuntimeId, status: DELETING}` |

Identity: `agentRuntimeId` = `<name>-<10 [a-zA-Z0-9]>` (`[a-zA-Z][a-zA-Z0-9_]{0,99}-[a-zA-Z0-9]{10}`).
ARN embeds a fresh UUID + version: `arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>`.
`agentRuntimeVersion` is a string int starting `"1"`, incremented on Update. `status` = `READY`.

### Agent Runtime Endpoint (control plane)

| Operation | Method + Path | Success |
|---|---|---|
| CreateAgentRuntimeEndpoint | `PUT /runtimes/{agentRuntimeId}/runtime-endpoints/` | 202 |
| GetAgentRuntimeEndpoint | `GET /runtimes/{agentRuntimeId}/runtime-endpoints/{endpointName}/` | 200 |
| UpdateAgentRuntimeEndpoint | `PUT /runtimes/{agentRuntimeId}/runtime-endpoints/{endpointName}/` | 202 |
| DeleteAgentRuntimeEndpoint | `DELETE /runtimes/{agentRuntimeId}/runtime-endpoints/{endpointName}/?clientToken={t}` | 202 |
| ListAgentRuntimeEndpoints | `POST /runtimes/{agentRuntimeId}/runtime-endpoints/?maxResults=&nextToken=` | 200 |

Endpoint ARN `arn:aws:bedrock-agentcore:<region>:<account>:agentEndpoint/<uuid>` (no version).
Create body: `name` (required), `agentRuntimeVersion`/`description`/`clientToken`/`tags` (optional).
Fields: `liveVersion` (serving) vs `targetVersion` (requested). A `DEFAULT` endpoint is auto-created
on runtime create so an unqualified invoke resolves.

### InvokeAgentRuntime (data plane)

`POST /runtimes/{agentRuntimeArn}/invocations?accountId={id}&qualifier={q}` → 200.
Payload is opaque binary (≤100 MB) — take `byte[]`, do not parse (mirror `BedrockRuntimeController.invokeModel`,
`@Consumes(WILDCARD)`). Echo `X-Amzn-Bedrock-AgentCore-Runtime-Session-Id`; return canned JSON,
default `{"output":"yes"}`. The ARN in the path is URL-encoded — use `@Path("/runtimes/{agentRuntimeArn:.+}/invocations")`
and test with an SDK-encoded ARN. Streaming is out of scope; the stub returns a single non-streaming 200.

### Tagging (control plane) — matches Floci's shared `/tags/` route

| Operation | Method + Path | Success |
|---|---|---|
| TagResource | `POST /tags/{resourceArn}` | 204 |
| UntagResource | `DELETE /tags/{resourceArn}?tagKeys={k1}&tagKeys={k2}` | 204 |
| ListTagsForResource | `GET /tags/{resourceArn}` | 200 |

Implement a `TagHandler` (not a controller): `serviceKey()="bedrock-agentcore"`, `tagsBodyKey()="tags"`,
`tagsBodyIsList()=false`, `tagKeysQueryName()="tagKeys"`. Body `{"tags":{"k":"v"}}`. Template:
`services/scheduler/SchedulerTagHandler.java`.

### Workload Identity (control plane) — RPC-in-path

`POST /identities/<Operation>` with all input in the body:
`CreateWorkloadIdentity` (201), `GetWorkloadIdentity` (200), `UpdateWorkloadIdentity` (200),
`DeleteWorkloadIdentity` (204), `ListWorkloadIdentities` (200, `maxResults` 1–20). Required body: `name`
(`[A-Za-z0-9_.-]+`, 3–255). `workloadIdentityArn` has no published regex (≤1024) — synthesize
`arn:aws:bedrock-agentcore:<region>:<account>:workload-identity-directory/default/workload-identity/<name>-<suffix>`.
`CreateAgentRuntime` auto-creates one and sets the runtime's `workloadIdentityArn`.

### Gateway + Gateway Target (control plane)

Standard REST, trailing slashes; identifier is `gatewayId` (not ARN):
`POST /gateways/` (202), `GET|PUT|DELETE /gateways/{gatewayIdentifier}/`, `GET /gateways/?maxResults=&nextToken=`.
Targets nested: `POST /gateways/{gatewayIdentifier}/targets/`, `GET|PUT|DELETE /gateways/{gatewayIdentifier}/targets/{targetId}/`,
`GET /gateways/{gatewayIdentifier}/targets/?...`. Gateway ARN
`arn:aws...:bedrock-agentcore:<region>:<account>:gateway/([0-9a-z][-]?){1,48}-[a-z0-9]{10}`; `gatewayId`
`([0-9a-z][-]?){1,100}-[0-9a-z]{10}`; `targetId` `[0-9a-zA-Z]{10}` (targets have no own ARN — return the
parent `gatewayArn`). Mutations return 202. `protocolType`: gateway `MCP`, target `MCP|HTTP`.

### Memory (control plane) — action-suffix REST

`POST /memories/create` (202, required `name` + `eventExpiryDuration` int 3–365),
`GET /memories/{memoryId}/details?view={view}` (200), `PUT /memories/{memoryId}/update` (202),
`DELETE /memories/{memoryId}/delete?clientToken={t}` (202), `POST /memories/?maxResults=&nextToken=` (200 list).
`memoryId` `[a-zA-Z][a-zA-Z0-9-_]{0,99}-[a-zA-Z0-9]{10}`; ARN unpublished — synthesize
`arn:aws:bedrock-agentcore:<region>:<account>:memory/<memoryId>`. status `CREATING|ACTIVE|FAILED|DELETING|UPDATING`
→ return `ACTIVE`. `clientToken` is a body field on Create/Update but a query param on Delete.

## Upgrade path — extending beyond the MVP

`bedrock-agentcore-control` has ~140 operations; the MVP covers the runtime-centric subset. The rest
add incrementally without rework, because every operation reduces to the same Floci recipe.

### Three wire-protocol styles coexist in this one service
Classify each new operation before writing the controller:

| Style | Shape | Examples | JAX-RS handling |
|---|---|---|---|
| A. Standard REST | resource in path, verb = HTTP method | Runtimes, Endpoints, Gateways, Targets | `@GET/@PUT/@POST/@DELETE` on the path. Several *list* ops are `POST`, not `GET`. |
| B. RPC-in-path | operation name is a path segment; input in body | Workload Identity (`/identities/<Op>`), Credential Providers, Token Vault | one `@POST` per `/identities/<Op>` |
| C. Action-suffix REST | resource path + literal action suffix | Memory (`/memories/create`, `/{id}/details`, `/update`, `/delete`) | REST verb + exact suffix in `@Path` |

Always confirm the exact `Method + Path` on each operation's `API_<Op>.html` page — never assume plain REST.

### Repeatable recipe (every future op)
1. Fetch the API page; record method, path (trailing slashes), success code, required fields, response
   shape, ID/ARN patterns.
2. Add the handler method to the matching controller (or a new `services/<family>/` package), classified A/B/C.
3. New resource → add a `StorageFactory` store, a `@RegisterForReflection` model, and (if taggable)
   extend the `bedrock-agentcore` `TagHandler`. A new *operation* on an existing service needs no new
   `descriptor(...)` — only a new *service* does.
4. Add unit + RestAssured integration tests; run `make docs-sync`.

### Full operation catalog & status
"Planned" = MVP issue set; "Future" = same recipe, not yet scheduled.

| Resource family | Operations | Style | Status |
|---|---|---|---|
| Agent Runtime | Create/Get/Update/Delete/List + ListVersions | A | Planned |
| Agent Runtime Endpoint | Create/Get/Update/Delete/List | A | Planned |
| InvokeAgentRuntime (data plane) | Invoke | A (binary) | Planned |
| Tagging | Tag/Untag/ListTagsForResource | A (`/tags/{arn}`) | Planned |
| Workload Identity | Create/Get/Update/Delete/List | B | Planned |
| Gateway + Gateway Target | Create/Get/Update/Delete/List (×2) | A | Planned |
| Memory | Create/Get/Update/Delete/List | C | Planned |
| Gateway Rule | Create/Get/Update/Delete/List | A | Future |
| Credential Providers (ApiKey/OAuth2/Payment) | Create/Get/Update/Delete/List | B | Future |
| Browser / Browser Profile / Code Interpreter | Create/Get/Delete/List | A | Future |
| Policy / Policy Engine / Policy Generation | Create/Get/Update/Delete/List + Start/Summary | mixed | Future |
| Registry / Registry Record | Create/Get/Update/Delete/List + Submit | mixed | Future |
| Dataset / Dataset Version / Examples | Create/Get/Update/Delete/List + Add/Delete examples | mixed | Future |
| Evaluator / Online Evaluation Config | Create/Get/Update/Delete/List | A | Future |
| Harness / Harness Endpoint | Create/Get/Update/Delete/List | A | Future |
| Configuration Bundle | Create/Get/Update/Delete/List (+ versions) | A | Future |
| Payment Manager / Connector | Create/Get/Update/Delete/List | mixed | Future |
| Token Vault / Resource Policy | Get/Put/Delete/SetCMK | B | Future |

A future contributor picks any "Future" row, applies the recipe, and files a follow-up referencing the epic.

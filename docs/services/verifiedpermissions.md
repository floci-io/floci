# Amazon Verified Permissions

**Protocol:** JSON 1.0 (`X-Amz-Target: VerifiedPermissions.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `verifiedpermissions`

Floci implements the Amazon Verified Permissions control plane and authorization APIs with
persistent policy stores, schemas, policies, policy templates, policy store aliases, identity
sources, tags, and idempotency state. Authorization decisions are evaluated with the official
Cedar Java engine rather than a service-specific policy shortcut.

Policy stores support `OFF` and `STRICT` validation. In `STRICT` mode, Floci parses the stored
Cedar JSON schema and validates new or updated static policies and policy templates before
storing them. Static and template-linked policies participate in `IsAuthorized` and batch
authorization decisions, including Cedar entity hierarchies, attributes, tags, context values,
and the standard Cedar extension values accepted by the AVP API model.

Policy store aliases resolve anywhere AVP accepts a policy store ID. Soft-deleted aliases remain
reserved in `PendingDeletion` state for 24 hours and stop resolving to the policy store. Hard
delete removes the alias immediately. Create operations that accept `clientToken` persist an
8-hour idempotency record and reject token reuse with different request parameters.

Identity sources support Amazon Cognito user pools and generic OpenID Connect issuers.
`IsAuthorizedWithToken` and `BatchIsAuthorizedWithToken` validate JWT signatures through the
issuer's OIDC discovery and JWKS endpoints, check token lifetime and configured audiences or
client IDs, and project token claims into Cedar principal entities or `context.token` according
to the AVP token type.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreatePolicyStore` | Creates a policy store with validation, deletion protection, tags, encryption settings, and idempotency |
| `GetPolicyStore` | Returns policy store validation, encryption, deletion-protection, and optional tag state |
| `UpdatePolicyStore` | Updates validation mode, deletion protection, and description |
| `DeletePolicyStore` | Deletes an unprotected policy store and its child resources; aliases are rejected as identifiers |
| `ListPolicyStores` | Lists policy stores with pagination |
| `PutSchema` | Creates, replaces, or removes the Cedar JSON schema |
| `GetSchema` | Returns the current Cedar JSON schema and namespace metadata |
| `CreatePolicyTemplate` | Creates a Cedar policy template with persisted idempotency state |
| `GetPolicyTemplate` | Returns a policy template and supports `name/` aliases |
| `UpdatePolicyTemplate` | Updates a Cedar policy template and name or description metadata |
| `DeletePolicyTemplate` | Deletes an unused policy template |
| `ListPolicyTemplates` | Lists policy templates with pagination |
| `CreatePolicy` | Creates static or template-linked Cedar policies with persisted idempotency state |
| `GetPolicy` | Returns static or template-linked policy details and supports `name/` aliases |
| `UpdatePolicy` | Updates the mutable fields of a static policy while preserving effect, principal, and resource scope |
| `DeletePolicy` | Deletes a policy |
| `ListPolicies` | Lists policies with policy type, template, principal, and resource filtering and pagination |
| `CreateIdentitySource` | Creates a Cognito or OIDC identity source with persisted idempotency state |
| `GetIdentitySource` | Returns an identity source and its normalized Cognito or OIDC configuration |
| `UpdateIdentitySource` | Replaces an identity source configuration without changing the provider family |
| `DeleteIdentitySource` | Deletes an identity source |
| `ListIdentitySources` | Lists identity sources with principal-entity-type filtering and pagination |
| `BatchGetPolicy` | Gets up to 100 policies and reports per-item not-found errors |
| `IsAuthorized` | Evaluates Cedar policies against supplied principal, action, resource, entities, and context |
| `BatchIsAuthorized` | Evaluates up to 30 Cedar authorization requests that share a principal or resource |
| `IsAuthorizedWithToken` | Validates an identity or access token and evaluates the resulting Cedar request |
| `BatchIsAuthorizedWithToken` | Evaluates up to 30 authorization requests using an identity or access token |
| `CreatePolicyStoreAlias` | Creates an active alias for a policy store |
| `GetPolicyStoreAlias` | Returns alias target and lifecycle state |
| `ListPolicyStoreAliases` | Lists aliases with policy-store filtering and pagination |
| `DeletePolicyStoreAlias` | Soft-deletes an alias for 24 hours or hard-deletes it immediately |
| `TagResource` | Adds or replaces policy store tags |
| `UntagResource` | Removes policy store tags |
| `ListTagsForResource` | Returns policy store tags |
<!-- floci:actions:end -->

## Cedar evaluation

Cedar parsing, schema validation, and authorization run in a lazily started Cedar 4 sidecar. The
main Floci JVM and native images do not include Cedar Java or its multi-platform native runtime.
This follows the same isolation pattern as `floci-duck`: Floci starts the sidecar on the first
Cedar-dependent AVP operation, probes its health endpoint, and reuses it until shutdown. A
pre-configured sidecar URL can be supplied when Docker Compose or another supervisor owns the
sidecar lifecycle.

The sidecar is required by operations that parse or validate Cedar, including `PutSchema`,
identity-source entity-type validation, policy and policy-template create/update paths, and all
`IsAuthorized*` and `BatchIsAuthorized*` evaluation paths. Read-only control-plane operations that
do not parse or evaluate Cedar do not start the sidecar. If Docker is unavailable and
`FLOCI_SERVICES_VERIFIEDPERMISSIONS_CEDAR_URL` is not configured, a sidecar-dependent operation
returns the modeled `InternalServerException` response with HTTP 500. Configure an externally
managed sidecar URL when running Floci without access to Docker.

The sidecar is stateless. Each authorization request includes the relevant policies, templates,
entities, and context, so policy-store isolation does not depend on mutable shared sidecar state.
It uses Cedar Java 4.10.0 to preserve the `CEDAR_4` behavior advertised by AVP. A matching
`forbid` policy overrides matching `permit` policies through Cedar's normal decision semantics.

`entities.cedarJson` is passed directly to Cedar. `entities.entityList` is translated to Cedar
JSON and supports booleans, longs, strings, entity references, IP addresses, decimals,
datetimes, durations, sets, records, entity parents, and entity tags. When duplicate entity
identifiers are supplied in an entity list, the last definition wins.

## Identity sources and tokens

Cognito identity sources derive the OIDC issuer from the user pool ARN, enforce same-Region user
pools, optional app client IDs, group entity mapping, and AVP's `<user-pool-id>|<subject>`
principal identifier convention. OIDC identity sources support identity-token-only or
access-token-only selection, accepted client IDs or audiences, principal ID claims, entity ID
prefixes, and group claims.

Token authorization uses the issuer discovery document and JWKS endpoint and currently verifies
RS256 signatures. The token must contain a valid `exp`, must not be before `nbf`, and must match
the configured token type and audience or client ID. Identity-token claims become principal
attributes. Access-token claims are exposed under `context.token`.

## Persistence and isolation

Policy stores and all child resources use Floci's configured storage backend. Keys include the
resolved AWS Region and are wrapped by the normal account-aware storage layer, preserving
account and Region isolation. `/_floci/reset` clears policy stores, policies, templates,
aliases, identity sources, and idempotency records with other resettable service state.

## Limitations

- Token verification currently supports RS256 JWT signatures. Other OIDC signing algorithms are
  rejected rather than accepted without verification.
- Identity source creation validates the configured issuer shape and AVP relationships, but does
  not contact the issuer until a token authorization request requires OIDC discovery or JWKS.
- The emulator applies state changes synchronously. AWS may expose short eventual-consistency
  windows after some AVP mutations.
- KMS-backed policy stores resolve and persist the configured KMS key ARN and encryption context,
  but local policy bytes are not encrypted at rest by Floci's storage backend.

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_VERIFIEDPERMISSIONS_ENABLED` | `true` | Enables Amazon Verified Permissions |
| `FLOCI_SERVICES_VERIFIEDPERMISSIONS_CEDAR_URL` | unset | Uses an externally managed Cedar sidecar URL and skips container management |
| `FLOCI_SERVICES_VERIFIEDPERMISSIONS_CEDAR_IMAGE` | `floci/floci:latest-cedar` | Image used for the lazily managed Cedar 4 sidecar |

## Examples

```bash
aws --endpoint-url http://localhost:4566 verifiedpermissions create-policy-store \
  --validation-settings mode=OFF

aws --endpoint-url http://localhost:4566 verifiedpermissions list-policy-stores
```

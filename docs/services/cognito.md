# Cognito

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSCognitoIdentityProviderService.*`)
**Endpoint:** `POST http://localhost:4566/`

Floci serves pool-specific discovery and JWKS endpoints, plus a relaxed OAuth token endpoint, so local clients can mint and validate Cognito-like access tokens against RS256 signing keys.

`CreateUserPool` supports overiding several values using user-pool tags **only** at creation time:
* `floci:override-id`, to pin the resulting `UserPool.Id`. 
* `floci:override-cognito-client-id`
  * set to `use-name` to use the client name as client ID.
  * set to `append-to-name:-somestring` to append a string to the client name to be used as client ID.
  * set to `prepend-to-name:somestring-` to prepend a string to the client name to be used as client ID.
* `floci:override-cognito-client-secret`, to set the secret for all clients created in this userpool.  

Floci strips reserved `floci:*` tags from stored and returned `UserPoolTags` on both create and update paths, so the tag namespace acts as an input-only control channel and is never persisted as user-visible metadata.

Standalone `TagResource` rejects reserved `floci:*` keys. `ListTagsForResource` and `UntagResource` operate on the persisted user-pool tag map.

## Supported Actions

### User Pools

| Action | Description |
|--------|-------------|
| CreateUserPool | - |
| DescribeUserPool | - |
| ListUserPools | - |
| UpdateUserPool | - |
| DeleteUserPool | - |

### User Pool Tags

| Action | Description |
|--------|-------------|
| TagResource | - |
| UntagResource | - |
| ListTagsForResource | - |

### User Pool Clients

| Action | Description |
|--------|-------------|
| CreateUserPoolClient | - |
| DescribeUserPoolClient | - |
| ListUserPoolClients | - |
| DeleteUserPoolClient | - |

### Resource Servers

| Action | Description |
|--------|-------------|
| CreateResourceServer | - |
| DescribeResourceServer | - |
| ListResourceServers | - |
| DeleteResourceServer | - |

### Admin User Management

| Action | Description |
|--------|-------------|
| AdminCreateUser | - |
| AdminGetUser | - |
| AdminDeleteUser | - |
| AdminSetUserPassword | - |
| AdminUpdateUserAttributes | - |

### User Operations

| Action | Description |
|--------|-------------|
| SignUp | - |
| ConfirmSignUp | - |
| GetUser | - |
| UpdateUserAttributes | - |
| ChangePassword | - |
| ForgotPassword | - |
| ConfirmForgotPassword | - |

### Authentication

| Action | Description |
|--------|-------------|
| InitiateAuth | - |
| AdminInitiateAuth | - |
| RespondToAuthChallenge | - |

### User Listing

| Action | Description |
|--------|-------------|
| ListUsers | - |

### Groups

| Action | Description |
|--------|-------------|
| CreateGroup | - |
| GetGroup | - |
| UpdateGroup | - |
| ListGroups | - |
| ListUsersInGroup | - |
| DeleteGroup | - |
| AdminAddUserToGroup | - |
| AdminRemoveUserFromGroup | - |
| AdminListGroupsForUser | - |

## Well-Known And OAuth Endpoints

| Endpoint                                             | Description                                                      |
|------------------------------------------------------|------------------------------------------------------------------|
| `GET /{userPoolId}/.well-known/openid-configuration` | OpenID discovery document                                        |
| `GET /{userPoolId}/.well-known/jwks.json`            | JSON Web Key Set for JWT validation                              |
| `POST /cognito-idp/oauth2/token`                     | Relaxed OAuth token endpoint for `grant_type=client_credentials` |

`POST /cognito-idp/oauth2/token` is intentionally emulator-friendly rather than full Cognito parity:

- It requires an existing `client_id`.
- It accepts `client_id` and `client_secret` from the form body or Basic auth.
- It requires a confidential app client created with `GenerateSecret=true`.
- It requires `AllowedOAuthFlowsUserPoolClient=true` and `AllowedOAuthFlows=["client_credentials"]`.
- It doesn't require a Cognito domain.
- It returns only `access_token`, `token_type`, and `expires_in`.
- It validates requested OAuth scopes against the app client's `AllowedOAuthScopes` and the pool's registered resource-server scopes.
- It advertises the prefixed token endpoint in `/{userPoolId}/.well-known/openid-configuration`.

## Sign-in Identifiers (`UsernameAttributes`)

Floci follows real Cognito semantics for pools created with `UsernameAttributes` (e.g.
`--username-attributes email`):

- `AdminCreateUser`/`SignUp` accept the email (or phone number) as the sign-in value, but the
  **canonical `Username` is an auto-generated, immutable UUID equal to `sub`**. The supplied email is
  stored as a mutable alias attribute.
- `ListUsers`, `AdminGetUser`, tokens (`sub`/`username`/`cognito:username`) and Lambda trigger
  `event.userName` all report the **UUID**, never the email. The email appears in the `email`
  attribute, in the **ID token's** `email` claim (AWS omits user attributes such as `email` from the
  **access** token), and in `event.request.userAttributes.email`.
- Sign-in resolves by the **current** email alias **or** the UUID. `SECRET_HASH` is validated against
  the exact `USERNAME` value sent: `Base64(HMAC-SHA256(USERNAME + clientId, clientSecret))`.
- In `CUSTOM_AUTH` / SRP flows, `ChallengeParameters.USERNAME` echoes the **UUID** (as real AWS does);
  the `RespondToAuthChallenge` `SECRET_HASH` is validated against the `USERNAME` sent that round.
- `AdminUpdateUserAttributes` can change the email; afterwards sign-in works with the new email and
  fails with the old one, while `Username`/`sub` stay fixed. A duplicate email is rejected with
  `UsernameExistsException` (on create) or `AliasExistsException` (on update).

Pools **without** `UsernameAttributes` (classic pools, and pools using `AliasAttributes`) keep the
literal `Username` you supply, unchanged.

## Configuration

| Variable                         | Default | Description                   |
|----------------------------------|---------|-------------------------------|
| `FLOCI_SERVICES_COGNITO_ENABLED` | `true`  | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a user pool
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name MyApp \
  --query UserPool.Id --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Create an app client
CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-name my-client \
  --generate-secret \
  --allowed-o-auth-flows-user-pool-client \
  --allowed-o-auth-flows client_credentials \
  --allowed-o-auth-scopes notes/read notes/write \
  --query UserPoolClient.ClientId --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Retrieve the generated client secret
CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-id $CLIENT_ID \
  --query UserPoolClient.ClientSecret --output text \
  --endpoint-url $AWS_ENDPOINT_URL)

# Register a resource server and scopes
aws cognito-idp create-resource-server \
  --user-pool-id $POOL_ID \
  --identifier notes \
  --name "Notes API" \
  --scopes ScopeName=read,ScopeDescription="Read notes" ScopeName=write,ScopeDescription="Write notes" \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a user
aws cognito-idp admin-create-user \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --temporary-password Temp1234! \
  --endpoint-url $AWS_ENDPOINT_URL

# Set a permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --password Perm1234! \
  --permanent \
  --endpoint-url $AWS_ENDPOINT_URL

# Authenticate
aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id $CLIENT_ID \
  --auth-parameters USERNAME=alice@example.com,PASSWORD=Perm1234! \
  --endpoint-url $AWS_ENDPOINT_URL

# Create a group
aws cognito-idp create-group \
  --user-pool-id $POOL_ID \
  --group-name admin \
  --description "Admin group" \
  --endpoint-url $AWS_ENDPOINT_URL

# Add user to group
aws cognito-idp admin-add-user-to-group \
  --user-pool-id $POOL_ID \
  --group-name admin \
  --username alice@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# List groups for user
aws cognito-idp admin-list-groups-for-user \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --endpoint-url $AWS_ENDPOINT_URL

# Fetch the pool discovery document
curl -s "$AWS_ENDPOINT_URL/$POOL_ID/.well-known/openid-configuration"

# Get a machine access token from the OAuth endpoint
curl -s \
  -X POST "$AWS_ENDPOINT_URL/cognito-idp/oauth2/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "$CLIENT_ID:$CLIENT_SECRET" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=notes/read notes/write"
```

## JWT Validation

Tokens issued by Floci can be validated using the discovery and JWKS endpoints:

```
http://localhost:4566/$POOL_ID/.well-known/openid-configuration
```

```
http://localhost:4566/$POOL_ID/.well-known/jwks.json
```

Tokens include the `cognito:groups` claim as a JSON array when the authenticated user belongs to one or more groups.

Tokens issued by Cognito auth flows and the OAuth token endpoint use the emulator base URL plus the pool id:

```
http://localhost:4566/$POOL_ID
```

This keeps the issuer, discovery document, JWKS URL, and token endpoint internally consistent for local JWT validation while supporting LocalStack-style confidential clients and resource-server-backed scopes.

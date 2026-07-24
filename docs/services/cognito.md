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
| CreateUserPool | Creates a local user pool, applying supported `floci:*` creation-time overrides from tags. |
| DescribeUserPool | Returns the stored user pool configuration. |
| ListUserPools | Lists local user pools visible in the request region. |
| UpdateUserPool | Updates mutable user pool settings and persisted user-pool tags. |
| DeleteUserPool | Deletes a local user pool and its related state. |

### User Pool Tags

| Action | Description |
|--------|-------------|
| TagResource | Adds user-visible tags to a user pool and rejects reserved `floci:*` tag keys. |
| UntagResource | Removes tags from a user pool's persisted tag map. |
| ListTagsForResource | Returns the persisted user-pool tags. |

### User Pool Clients

| Action | Description |
|--------|-------------|
| CreateUserPoolClient | Creates an app client for a user pool, including optional generated secret handling. |
| DescribeUserPoolClient | Returns the stored app client configuration. |
| ListUserPoolClients | Lists app clients for a user pool. |
| DeleteUserPoolClient | Deletes an app client from a user pool. |

### Resource Servers

| Action | Description |
|--------|-------------|
| CreateResourceServer | Registers a resource server and scopes for a user pool. |
| DescribeResourceServer | Returns a registered resource server. |
| ListResourceServers | Lists resource servers for a user pool. |
| DeleteResourceServer | Deletes a resource server from a user pool. |

### Admin User Management

| Action | Description |
|--------|-------------|
| AdminCreateUser | Creates or resends setup for a user in a user pool. |
| AdminGetUser | Returns a user's stored attributes and status. |
| AdminDeleteUser | Deletes a user from a user pool. |
| AdminSetUserPassword | Sets a user's password and permanent-password status. |
| AdminUpdateUserAttributes | Updates attributes for a user in a user pool. |

### User Operations

| Action | Description |
|--------|-------------|
| SignUp | Creates a self-service user for an app client. |
| ConfirmSignUp | Confirms a pending self-service signup. |
| GetUser | Returns attributes for the authenticated access-token user. |
| GetUserAttributeVerificationCode | Issues a verification code for the authenticated user's email or phone_number attribute. |
| UpdateUserAttributes | Updates attributes for the authenticated access-token user. |
| ChangePassword | Changes the authenticated user's password. |
| ForgotPassword | Starts the local forgot-password flow for a user. |
| ConfirmForgotPassword | Completes the forgot-password flow by setting a replacement password. |

### Authentication

| Action | Description |
|--------|-------------|
| InitiateAuth | Authenticates app-client users through supported user-password and SRP-style flows. |
| AdminInitiateAuth | Starts an admin authentication flow for a user pool user. |
| RespondToAuthChallenge | Responds to supported Cognito auth challenges. |

### User Listing

| Action | Description |
|--------|-------------|
| ListUsers | Lists users stored in a user pool. |

### Groups

| Action | Description |
|--------|-------------|
| CreateGroup | Creates a group in a user pool. |
| GetGroup | Returns a user-pool group. |
| UpdateGroup | Updates a user-pool group's stored settings. |
| ListGroups | Lists groups in a user pool. |
| ListUsersInGroup | Lists users assigned to a group. |
| DeleteGroup | Deletes a group from a user pool. |
| AdminAddUserToGroup | Adds a user to a group. |
| AdminRemoveUserFromGroup | Removes a user from a group. |
| AdminListGroupsForUser | Lists the groups assigned to a user. |

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

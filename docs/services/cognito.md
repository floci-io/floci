# Cognito

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSCognitoIdentityProviderService.*`)
**Endpoint:** `POST http://localhost:4566/`

Floci serves pool-specific discovery and JWKS endpoints so local clients can validate Cognito-like JWTs against RS256 signing keys.

## Supported Actions

| Category | Actions |
|---|---|
| **User Pools** | CreateUserPool, DescribeUserPool, ListUserPools, DeleteUserPool |
| **User Pool Clients** | CreateUserPoolClient, DescribeUserPoolClient, ListUserPoolClients, DeleteUserPoolClient |
| **Admin User Management** | AdminCreateUser, AdminGetUser, AdminDeleteUser, AdminSetUserPassword, AdminUpdateUserAttributes |
| **User Operations** | SignUp, ConfirmSignUp, GetUser, UpdateUserAttributes, ChangePassword, ForgotPassword, ConfirmForgotPassword |
| **Authentication** | InitiateAuth, AdminInitiateAuth, RespondToAuthChallenge |
| **User Listing** | ListUsers |

## Well-Known Endpoints

| Endpoint | Description |
|---|---|
| `GET /{userPoolId}/.well-known/openid-configuration` | OpenID discovery document |
| `GET /{userPoolId}/.well-known/jwks.json` | JSON Web Key Set for JWT validation |

## Examples

```bash
export AWS_ENDPOINT=http://localhost:4566

# Create a user pool
POOL_ID=$(aws cognito-idp create-user-pool \
  --pool-name MyApp \
  --query UserPool.Id --output text \
  --endpoint-url $AWS_ENDPOINT)

# Create an app client
CLIENT_ID=$(aws cognito-idp create-user-pool-client \
  --user-pool-id $POOL_ID \
  --client-name my-client \
  --explicit-auth-flows ALLOW_USER_PASSWORD_AUTH ALLOW_REFRESH_TOKEN_AUTH \
  --query UserPoolClient.ClientId --output text \
  --endpoint-url $AWS_ENDPOINT)

# Create a user
aws cognito-idp admin-create-user \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --temporary-password Temp1234! \
  --endpoint-url $AWS_ENDPOINT

# Set a permanent password
aws cognito-idp admin-set-user-password \
  --user-pool-id $POOL_ID \
  --username alice@example.com \
  --password Perm1234! \
  --permanent \
  --endpoint-url $AWS_ENDPOINT

# Authenticate
aws cognito-idp initiate-auth \
  --auth-flow USER_PASSWORD_AUTH \
  --client-id $CLIENT_ID \
  --auth-parameters USERNAME=alice@example.com,PASSWORD=Perm1234! \
  --endpoint-url $AWS_ENDPOINT

# Fetch the pool discovery document
curl -s "$AWS_ENDPOINT/$POOL_ID/.well-known/openid-configuration"
```

## JWT Validation

Tokens issued by Floci can be validated using the discovery and JWKS endpoints:

```
http://localhost:4566/$POOL_ID/.well-known/openid-configuration
```

```
http://localhost:4566/$POOL_ID/.well-known/jwks.json
```

Tokens issued by Cognito auth flows keep the emulator issuer format:

```
https://cognito-idp.local/$POOL_ID
```

This allows libraries like `jsonwebtoken`, `jose`, or Spring Security to validate tokens against Floci using the emulator's published issuer, discovery, and JWKS endpoints.

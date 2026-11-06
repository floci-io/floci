/**
 * Cognito USER_SRP_AUTH round-trip via amazon-cognito-identity-js.
 *
 * The existing CognitoSrpTest (Java) only validates that the PASSWORD_VERIFIER
 * challenge is *returned*. This test goes further and exercises the full
 * round-trip — the client computes the PASSWORD_CLAIM_SIGNATURE from SRP_B
 * + SALT + SECRET_BLOCK and sends RespondToAuthChallenge — using the
 * canonical JS SRP impl (`amazon-cognito-identity-js`, which is what AWS
 * Amplify v6 wraps internally).
 *
 * Validates that Floci's server-side SRP-6a math (k, U, S, HKDF, HMAC byte
 * layouts) matches AWS's "Caldera" SRP spec well enough for a real SDK
 * client to authenticate end-to-end.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CognitoIdentityProviderClient,
  CreateUserPoolCommand,
  CreateUserPoolClientCommand,
  AdminCreateUserCommand,
  AdminSetUserPasswordCommand,
  DeleteUserPoolCommand,
  MessageActionType,
  ExplicitAuthFlowsType,
} from '@aws-sdk/client-cognito-identity-provider';
import {
  CognitoUserPool,
  CognitoUser,
  AuthenticationDetails,
} from 'amazon-cognito-identity-js';
import { makeClient, ENDPOINT } from './setup';

describe('Cognito USER_SRP_AUTH end-to-end (amazon-cognito-identity-js)', () => {
  let cognito: CognitoIdentityProviderClient;
  let poolId: string;
  let clientId: string;
  const username = `srp-roundtrip-${Date.now()}`;
  const password = 'SrpRoundTrip1!';

  beforeAll(async () => {
    cognito = makeClient(CognitoIdentityProviderClient);

    const pool = await cognito.send(new CreateUserPoolCommand({ PoolName: 'srp-roundtrip-pool' }));
    poolId = pool.UserPool!.Id!;

    const client = await cognito.send(
      new CreateUserPoolClientCommand({
        UserPoolId: poolId,
        ClientName: 'srp-roundtrip-client',
        ExplicitAuthFlows: [ExplicitAuthFlowsType.ALLOW_USER_SRP_AUTH],
      }),
    );
    clientId = client.UserPoolClient!.ClientId!;

    await cognito.send(
      new AdminCreateUserCommand({
        UserPoolId: poolId,
        Username: username,
        MessageAction: MessageActionType.SUPPRESS,
      }),
    );
    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: username,
        Password: password,
        Permanent: true,
      }),
    );
  });

  afterAll(async () => {
    if (poolId) {
      try { await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId })); } catch {}
    }
    cognito.destroy();
  });

  it('authenticates via SRP and returns valid tokens', async () => {
    const userPool = new CognitoUserPool({
      UserPoolId: poolId,
      ClientId: clientId,
      endpoint: ENDPOINT,
    });
    const cognitoUser = new CognitoUser({ Username: username, Pool: userPool });
    const authDetails = new AuthenticationDetails({ Username: username, Password: password });

    const session = await new Promise<{
      idToken: string;
      accessToken: string;
      refreshToken: string;
    }>((resolve, reject) => {
      cognitoUser.authenticateUser(authDetails, {
        onSuccess: (s) =>
          resolve({
            idToken: s.getIdToken().getJwtToken(),
            accessToken: s.getAccessToken().getJwtToken(),
            refreshToken: s.getRefreshToken().getToken(),
          }),
        onFailure: (err) => reject(err),
      });
    });

    // JWT tokens have three base64 segments separated by dots.
    expect(session.idToken.split('.')).toHaveLength(3);
    expect(session.accessToken.split('.')).toHaveLength(3);
    expect(session.refreshToken.length).toBeGreaterThan(0);
  });
});

/**
 * Cognito IDP compatibility tests for bug fixes:
 *   #218 — RS256 JWT signing + JWKS signature verification
 *   #220 — AdminGetUser accepts sub UUID and email alias as Username
 *   #228 — AccessToken contains client_id claim
 *   #229 — InitiateAuth rejects auth when no password hash is set
 *   #233 — ListUsers respects Filter parameter
 *   #234 — GetTokensFromRefreshToken returns new tokens (JS SDK v3 only)
 *   #235 — AdminSetUserPassword(Permanent=false) changes the password
 */

import { createPublicKey, createVerify } from 'node:crypto';
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CognitoIdentityProviderClient,
  CreateUserPoolCommand,
  CreateUserPoolClientCommand,
  AdminCreateUserCommand,
  AdminSetUserPasswordCommand,
  AdminGetUserCommand,
  InitiateAuthCommand,
  ListUsersCommand,
  GetTokensFromRefreshTokenCommand,
  DescribeUserPoolCommand,
  DeleteUserPoolCommand,
  MessageActionType,
  ExplicitAuthFlowsType,
  AuthFlowType,
  ChallengeNameType,
} from '@aws-sdk/client-cognito-identity-provider';
import { makeClient, uniqueName, ENDPOINT } from './setup';

// ── JWT helpers ──────────────────────────────────────────────────────────────

function decodeJwtPart(token: string, index: number): any {
  const part = token.split('.')[index];
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
}

async function fetchJwk(poolId: string, kid: string): Promise<any> {
  const resp = await fetch(`${ENDPOINT}/${poolId}/.well-known/jwks.json`);
  const json = await resp.json();
  return json.keys?.find((k: any) => k.kid === kid) ?? null;
}

function verifyRs256(token: string, jwk: any): boolean {
  const [headerB64, payloadB64, sigB64] = token.split('.');
  const key = createPublicKey({ key: jwk, format: 'jwk' });
  const verifier = createVerify('RSA-SHA256');
  verifier.update(`${headerB64}.${payloadB64}`);
  return verifier.verify(key, Buffer.from(sigB64, 'base64url'));
}

// ─────────────────────────────────────────────────────────────────────────────

describe('Cognito features (#218 #220 #228 #229 #233 #234 #235)', () => {
  let cognito: CognitoIdentityProviderClient;
  let poolId: string;
  let clientId: string;
  const username = `compat-${uniqueName()}@example.com`;
  const password = 'CompatPass1!';
  let userSub: string;

  beforeAll(() => {
    cognito = makeClient(CognitoIdentityProviderClient);
  });

  afterAll(async () => {
    try {
      if (poolId) await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId }));
    } catch { /* ignore */ }
  });

  // ── Setup ──────────────────────────────────────────────────────────────────

  it('creates pool', async () => {
    const resp = await cognito.send(
      new CreateUserPoolCommand({ PoolName: `compat-pool-${uniqueName()}` })
    );
    poolId = resp.UserPool!.Id!;
    expect(poolId).toBeTruthy();
  });

  it('creates client', async () => {
    const resp = await cognito.send(
      new CreateUserPoolClientCommand({
        UserPoolId: poolId,
        ClientName: `compat-client-${uniqueName()}`,
        ExplicitAuthFlows: [
          ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
          ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH,
        ],
      })
    );
    clientId = resp.UserPoolClient!.ClientId!;
    expect(clientId).toBeTruthy();
  });

  it('creates user with permanent password', async () => {
    await cognito.send(
      new AdminCreateUserCommand({
        UserPoolId: poolId,
        Username: username,
        UserAttributes: [
          { Name: 'email', Value: username },
          { Name: 'email_verified', Value: 'true' },
        ],
        MessageAction: MessageActionType.SUPPRESS,
      })
    );
    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: username,
        Password: password,
        Permanent: true,
      })
    );

    const user = await cognito.send(
      new AdminGetUserCommand({ UserPoolId: poolId, Username: username })
    );
    userSub = user.UserAttributes?.find((a) => a.Name === 'sub')?.Value ?? '';
    expect(userSub).toBeTruthy();
  });

  // ── Issue #229 — InitiateAuth rejects when no password hash set ────────────

  it('#229: rejects auth when user has no password set', async () => {
    const noHashUser = `no-hash-${uniqueName()}@example.com`;
    await cognito.send(
      new AdminCreateUserCommand({
        UserPoolId: poolId,
        Username: noHashUser,
        UserAttributes: [{ Name: 'email', Value: noHashUser }],
        MessageAction: MessageActionType.SUPPRESS,
      })
    );

    await expect(
      cognito.send(
        new InitiateAuthCommand({
          ClientId: clientId,
          AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
          AuthParameters: { USERNAME: noHashUser, PASSWORD: 'anything' },
        })
      )
    ).rejects.toThrow();
  });

  it('#229: rejects wrong password', async () => {
    await expect(
      cognito.send(
        new InitiateAuthCommand({
          ClientId: clientId,
          AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
          AuthParameters: { USERNAME: username, PASSWORD: 'WrongPass1!' },
        })
      )
    ).rejects.toThrow();
  });

  // ── Issue #235 — AdminSetUserPassword(Permanent=false) changes the password ─

  it('#235: Permanent=false changes password and sets FORCE_CHANGE_PASSWORD status', async () => {
    const tempPass = 'TempPass1!';

    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: username,
        Password: tempPass,
        Permanent: false,
      })
    );

    // Old password is now rejected
    await expect(
      cognito.send(
        new InitiateAuthCommand({
          ClientId: clientId,
          AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
          AuthParameters: { USERNAME: username, PASSWORD: password },
        })
      )
    ).rejects.toThrow();

    // New temp password triggers NEW_PASSWORD_REQUIRED, not tokens
    const challengeResp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: tempPass },
      })
    );
    expect(challengeResp.ChallengeName).toBe(ChallengeNameType.NEW_PASSWORD_REQUIRED);

    // Restore permanent password for subsequent tests
    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: username,
        Password: password,
        Permanent: true,
      })
    );
  });

  // ── Issue #228 — AccessToken contains client_id claim ─────────────────────

  it('#228: AccessToken contains client_id claim matching the ClientId', async () => {
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const payload = decodeJwtPart(resp.AuthenticationResult!.AccessToken!, 1);
    expect(payload.client_id).toBe(clientId);
  });

  it('#228: IdToken does not contain client_id claim', async () => {
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const payload = decodeJwtPart(resp.AuthenticationResult!.IdToken!, 1);
    expect(payload.client_id).toBeUndefined();
  });

  // ── Issue #218 — RS256 JWT signing + JWKS verification ────────────────────

  it('#218: AccessToken header declares alg=RS256 with a kid', async () => {
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const header = decodeJwtPart(resp.AuthenticationResult!.AccessToken!, 0);
    expect(header.alg).toBe('RS256');
    expect(header.kid).toBeTruthy();
  });

  it('#218: AccessToken RS256 signature verifies against JWKS public key', async () => {
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const token = resp.AuthenticationResult!.AccessToken!;
    const kid = decodeJwtPart(token, 0).kid;
    const jwk = await fetchJwk(poolId, kid);
    expect(jwk).toBeTruthy();
    expect(verifyRs256(token, jwk)).toBe(true);
  });

  // ── Issue #220 — AdminGetUser accepts sub UUID and email alias ────────────

  it('#220: AdminGetUser resolves by sub UUID', async () => {
    expect(userSub).toBeTruthy();
    const resp = await cognito.send(
      new AdminGetUserCommand({ UserPoolId: poolId, Username: userSub })
    );
    expect(resp.Username).toBe(username);
  });

  it('#220: AdminGetUser resolves by email alias', async () => {
    const resp = await cognito.send(
      new AdminGetUserCommand({ UserPoolId: poolId, Username: username })
    );
    expect(resp.Username).toBe(username);
  });

  it('#220: AdminSetUserPassword works with sub UUID as Username', async () => {
    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: userSub,
        Password: password,
        Permanent: true,
      })
    );
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    expect(resp.AuthenticationResult?.AccessToken).toBeTruthy();
  });

  // ── Issue #233 — ListUsers respects Filter parameter ─────────────────────

  it('#233: ListUsers without filter returns the user', async () => {
    const resp = await cognito.send(new ListUsersCommand({ UserPoolId: poolId }));
    expect(resp.Users?.some((u) => u.Username === username)).toBe(true);
  });

  it('#233: ListUsers with email exact-match filter returns only matching user', async () => {
    const resp = await cognito.send(
      new ListUsersCommand({
        UserPoolId: poolId,
        Filter: `email = "${username}"`,
      })
    );
    expect(resp.Users).toHaveLength(1);
    expect(resp.Users![0].Username).toBe(username);
  });

  it('#233: ListUsers with email starts-with filter returns matching users', async () => {
    const resp = await cognito.send(
      new ListUsersCommand({
        UserPoolId: poolId,
        Filter: `email ^= "compat-"`,
      })
    );
    expect(resp.Users?.some((u) => u.Username === username)).toBe(true);
  });

  it('#233: ListUsers with sub exact-match filter returns only that user', async () => {
    expect(userSub).toBeTruthy();
    const resp = await cognito.send(
      new ListUsersCommand({
        UserPoolId: poolId,
        Filter: `sub = "${userSub}"`,
      })
    );
    expect(resp.Users).toHaveLength(1);
    expect(resp.Users![0].Username).toBe(username);
  });

  it('#233: ListUsers with non-matching filter returns empty list', async () => {
    const resp = await cognito.send(
      new ListUsersCommand({
        UserPoolId: poolId,
        Filter: `email = "nobody@nowhere.invalid"`,
      })
    );
    expect(resp.Users).toHaveLength(0);
  });

  // ── Issue #234 — GetTokensFromRefreshToken ────────────────────────────────

  it('#234: InitiateAuth returns a structured refresh token', async () => {
    const resp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    expect(resp.AuthenticationResult?.RefreshToken).toBeTruthy();
  });

  it('#234: GetTokensFromRefreshToken returns new AccessToken and IdToken', async () => {
    const loginResp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const refreshToken = loginResp.AuthenticationResult!.RefreshToken!;

    const refreshResp = await cognito.send(
      new GetTokensFromRefreshTokenCommand({
        ClientId: clientId,
        RefreshToken: refreshToken,
      })
    );

    expect(refreshResp.AuthenticationResult?.AccessToken).toBeTruthy();
    expect(refreshResp.AuthenticationResult?.IdToken).toBeTruthy();
    // Per AWS spec, GetTokensFromRefreshToken does not return a new RefreshToken
    expect(refreshResp.AuthenticationResult?.RefreshToken).toBeFalsy();
  });

  it('#234: GetTokensFromRefreshToken returns token with correct client_id claim', async () => {
    const loginResp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const refreshToken = loginResp.AuthenticationResult!.RefreshToken!;

    const refreshResp = await cognito.send(
      new GetTokensFromRefreshTokenCommand({
        ClientId: clientId,
        RefreshToken: refreshToken,
      })
    );

    const payload = decodeJwtPart(refreshResp.AuthenticationResult!.AccessToken!, 1);
    expect(payload.client_id).toBe(clientId);
  });

  it('#234: GetTokensFromRefreshToken rejects invalid refresh token', async () => {
    await expect(
      cognito.send(
        new GetTokensFromRefreshTokenCommand({
          ClientId: clientId,
          RefreshToken: 'not-a-valid-token',
        })
      )
    ).rejects.toThrow();
  });

  it('DescribeUserPool returns all 20 standard SchemaAttributes', async () => {
    const resp = await cognito.send(new DescribeUserPoolCommand({ UserPoolId: poolId }));
    const schema = resp.UserPool?.SchemaAttributes ?? [];
    expect(schema).toHaveLength(20);
    const names = schema.map((a) => a.Name);
    const expected = [
      'sub', 'name', 'given_name', 'family_name', 'middle_name', 'nickname',
      'preferred_username', 'profile', 'picture', 'website', 'email',
      'email_verified', 'gender', 'birthdate', 'zoneinfo', 'locale',
      'phone_number', 'phone_number_verified', 'address', 'updated_at',
    ];
    for (const attr of expected) {
      expect(names).toContain(attr);
    }
    const sub = schema.find((a) => a.Name === 'sub');
    expect(sub?.Required).toBe(true);
    expect(sub?.Mutable).toBe(false);
  });

  it('#234: REFRESH_TOKEN_AUTH flow also works with structured token', async () => {
    const loginResp = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.USER_PASSWORD_AUTH,
        AuthParameters: { USERNAME: username, PASSWORD: password },
      })
    );
    const refreshToken = loginResp.AuthenticationResult!.RefreshToken!;

    const refreshed = await cognito.send(
      new InitiateAuthCommand({
        ClientId: clientId,
        AuthFlow: AuthFlowType.REFRESH_TOKEN_AUTH,
        AuthParameters: { REFRESH_TOKEN: refreshToken },
      })
    );

    expect(refreshed.AuthenticationResult?.AccessToken).toBeTruthy();
    expect(refreshed.AuthenticationResult?.IdToken).toBeTruthy();
  });
});

/**
 * Cognito Identity Provider integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CognitoIdentityProviderClient,
  CreateUserPoolCommand,
  CreateUserPoolClientCommand,
  AdminCreateUserCommand,
  AdminSetUserPasswordCommand,
  InitiateAuthCommand,
  AdminGetUserCommand,
  ListUsersCommand,
  DeleteUserPoolCommand,
  SignUpCommand,
  ConfirmSignUpCommand,
  CreateGroupCommand,
  GetGroupCommand,
  ListGroupsCommand,
  DeleteGroupCommand,
  AdminAddUserToGroupCommand,
  AdminRemoveUserFromGroupCommand,
  AdminListGroupsForUserCommand,
} from '@aws-sdk/client-cognito-identity-provider';
import { makeClient, uniqueName, ENDPOINT } from './setup';

describe('Cognito', () => {
  let cognito: CognitoIdentityProviderClient;
  let poolId: string;
  let clientId: string;

  beforeAll(() => {
    cognito = makeClient(CognitoIdentityProviderClient);
  });

  afterAll(async () => {
    try {
      if (poolId) {
        await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId }));
      }
    } catch {
      // ignore
    }
  });

  it('should create user pool', async () => {
    const response = await cognito.send(
      new CreateUserPoolCommand({ PoolName: `test-pool-${uniqueName()}` })
    );
    poolId = response.UserPool!.Id!;
    expect(poolId).toBeTruthy();
  });

  it('should create user pool client', async () => {
    const response = await cognito.send(
      new CreateUserPoolClientCommand({
        UserPoolId: poolId,
        ClientName: `test-client-${uniqueName()}`,
      })
    );
    clientId = response.UserPoolClient!.ClientId!;
    expect(clientId).toBeTruthy();
  });

  it('should admin create user', async () => {
    const response = await cognito.send(
      new AdminCreateUserCommand({
        UserPoolId: poolId,
        Username: 'testuser',
        TemporaryPassword: 'Temp123!',
        UserAttributes: [{ Name: 'email', Value: 'testuser@example.com' }],
      })
    );
    expect(response.User?.Username).toBe('testuser');
  });

  it('should admin set user password', async () => {
    await cognito.send(
      new AdminSetUserPasswordCommand({
        UserPoolId: poolId,
        Username: 'testuser',
        Password: 'Perm456!',
        Permanent: true,
      })
    );
  });

  it('should admin get user', async () => {
    const response = await cognito.send(
      new AdminGetUserCommand({ UserPoolId: poolId, Username: 'testuser' })
    );
    expect(response.UserStatus).toBe('CONFIRMED');
  });

  it('should list users', async () => {
    const response = await cognito.send(new ListUsersCommand({ UserPoolId: poolId }));
    expect(response.Users?.some((u) => u.Username === 'testuser')).toBe(true);
  });

  it('should initiate auth USER_PASSWORD_AUTH', async () => {
    const response = await cognito.send(
      new InitiateAuthCommand({
        AuthFlow: 'USER_PASSWORD_AUTH',
        AuthParameters: { USERNAME: 'testuser', PASSWORD: 'Perm456!' },
        ClientId: clientId,
      })
    );
    expect(response.AuthenticationResult?.AccessToken).toBeTruthy();
    expect(response.AuthenticationResult?.IdToken).toBeTruthy();
  });

  it('should sign up and confirm user', async () => {
    await cognito.send(
      new SignUpCommand({
        ClientId: clientId,
        Username: 'testuser2',
        Password: 'Pass789!',
        UserAttributes: [{ Name: 'email', Value: 'testuser2@example.com' }],
      })
    );
    await cognito.send(
      new ConfirmSignUpCommand({
        ClientId: clientId,
        Username: 'testuser2',
        ConfirmationCode: '123456',
      })
    );
  });

  it('should create group', async () => {
    const response = await cognito.send(
      new CreateGroupCommand({
        UserPoolId: poolId,
        GroupName: 'test-group',
        Description: 'Test group',
        Precedence: 1,
      })
    );
    expect(response.Group?.GroupName).toBe('test-group');
  });

  it('should get group', async () => {
    const response = await cognito.send(
      new GetGroupCommand({ UserPoolId: poolId, GroupName: 'test-group' })
    );
    expect(response.Group?.GroupName).toBe('test-group');
    expect(response.Group?.Description).toBe('Test group');
    expect(response.Group?.Precedence).toBe(1);
  });

  it('should fail to create duplicate group', async () => {
    await expect(
      cognito.send(
        new CreateGroupCommand({ UserPoolId: poolId, GroupName: 'test-group' })
      )
    ).rejects.toThrow();
  });

  it('should list groups', async () => {
    const response = await cognito.send(new ListGroupsCommand({ UserPoolId: poolId }));
    expect(response.Groups?.some((g) => g.GroupName === 'test-group')).toBe(true);
  });

  it('should add user to group', async () => {
    await cognito.send(
      new AdminAddUserToGroupCommand({
        UserPoolId: poolId,
        GroupName: 'test-group',
        Username: 'testuser',
      })
    );
  });

  it('should list groups for user', async () => {
    const response = await cognito.send(
      new AdminListGroupsForUserCommand({ UserPoolId: poolId, Username: 'testuser' })
    );
    expect(response.Groups?.some((g) => g.GroupName === 'test-group')).toBe(true);
  });

  it('should return groups in JWT', async () => {
    const response = await cognito.send(
      new InitiateAuthCommand({
        AuthFlow: 'USER_PASSWORD_AUTH',
        AuthParameters: { USERNAME: 'testuser', PASSWORD: 'Perm456!' },
        ClientId: clientId,
      })
    );
    const token = response.AuthenticationResult?.AccessToken!;
    const parts = token.split('.');
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'));
    expect(Array.isArray(payload['cognito:groups'])).toBe(true);
    expect(payload['cognito:groups']?.includes('test-group')).toBe(true);
  });

  it('should remove user from group', async () => {
    await cognito.send(
      new AdminRemoveUserFromGroupCommand({
        UserPoolId: poolId,
        GroupName: 'test-group',
        Username: 'testuser',
      })
    );
  });

  it('should verify user has no groups', async () => {
    const response = await cognito.send(
      new AdminListGroupsForUserCommand({ UserPoolId: poolId, Username: 'testuser' })
    );
    expect(response.Groups?.length).toBe(0);
  });

  it('should delete group', async () => {
    await cognito.send(
      new DeleteGroupCommand({ UserPoolId: poolId, GroupName: 'test-group' })
    );
  });

  it('should fail to get deleted group', async () => {
    await expect(
      cognito.send(new GetGroupCommand({ UserPoolId: poolId, GroupName: 'test-group' }))
    ).rejects.toThrow();
  });

  it('should access JWKS endpoint', async () => {
    const resp = await fetch(`${ENDPOINT}/${poolId}/.well-known/jwks.json`);
    expect(resp.ok).toBe(true);
    const json = await resp.json();
    expect(Array.isArray(json.keys)).toBe(true);
    expect(json.keys.length).toBeGreaterThan(0);
    expect(json.keys[0].kty).toBe('RSA');
    expect(json.keys[0].alg).toBe('RS256');
  });

  it('should delete user pool', async () => {
    await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId }));
    poolId = '';
  });
});

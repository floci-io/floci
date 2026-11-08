/**
 * STS integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { STSClient, GetCallerIdentityCommand, AssumeRoleCommand } from '@aws-sdk/client-sts';
import { IAMClient, CreateRoleCommand, DeleteRoleCommand } from '@aws-sdk/client-iam';
import { makeClient, ACCOUNT } from './setup';

describe('STS', () => {
  let sts: STSClient;
  let iam: IAMClient;
  const roleName = 'test-role';

  beforeAll(async () => {
    sts = makeClient(STSClient);
    iam = makeClient(IAMClient);
    const trustPolicy = JSON.stringify({
      Version: '2012-10-17',
      Statement: [
        {
          Effect: 'Allow',
          Principal: { AWS: '*' },
          Action: 'sts:AssumeRole',
        },
      ],
    });
    try {
      await iam.send(
        new CreateRoleCommand({
          RoleName: roleName,
          AssumeRolePolicyDocument: trustPolicy,
        })
      );
    } catch (err: unknown) {
      if ((err as { name?: string })?.name !== 'EntityAlreadyExistsException') {
        throw err;
      }
    }
  });

  afterAll(async () => {
    try {
      await iam.send(new DeleteRoleCommand({ RoleName: roleName }));
    } catch (err: unknown) {
      if ((err as { name?: string })?.name !== 'NoSuchEntityException') {
        throw err;
      }
    }
  });

  it('should get caller identity', async () => {
    const response = await sts.send(new GetCallerIdentityCommand({}));
    expect(response.Account).toBeTruthy();
    expect(response.UserId).toBeTruthy();
  });

  it('should assume role', async () => {
    const response = await sts.send(
      new AssumeRoleCommand({
        RoleArn: `arn:aws:iam::${ACCOUNT}:role/${roleName}`,
        RoleSessionName: 'test-session',
      })
    );
    expect(response.Credentials?.AccessKeyId).toBeTruthy();
  });

  it('should deny assume role for nonexistent role', async () => {
    await expect(
      sts.send(
        new AssumeRoleCommand({
          RoleArn: `arn:aws:iam::${ACCOUNT}:role/definitely-nonexistent-role`,
          RoleSessionName: 'test-session',
        })
      )
    ).rejects.toThrow();
  });
});

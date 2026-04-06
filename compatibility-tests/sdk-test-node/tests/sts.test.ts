/**
 * STS integration tests.
 */

import { describe, it, expect, beforeAll } from 'vitest';
import { STSClient, GetCallerIdentityCommand, AssumeRoleCommand } from '@aws-sdk/client-sts';
import { makeClient, ACCOUNT } from './setup';

describe('STS', () => {
  let sts: STSClient;

  beforeAll(() => {
    sts = makeClient(STSClient);
  });

  it('should get caller identity', async () => {
    const response = await sts.send(new GetCallerIdentityCommand({}));
    expect(response.Account).toBeTruthy();
    expect(response.UserId).toBeTruthy();
  });

  it('should assume role', async () => {
    const response = await sts.send(
      new AssumeRoleCommand({
        RoleArn: `arn:aws:iam::${ACCOUNT}:role/test-role`,
        RoleSessionName: 'test-session',
      })
    );
    expect(response.Credentials?.AccessKeyId).toBeTruthy();
  });
});

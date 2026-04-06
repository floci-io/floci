/**
 * IAM integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  IAMClient,
  CreateRoleCommand,
  GetRoleCommand,
  DeleteRoleCommand,
  ListRolesCommand,
  CreatePolicyCommand,
  DeletePolicyCommand,
  AttachRolePolicyCommand,
  DetachRolePolicyCommand,
} from '@aws-sdk/client-iam';
import { makeClient, uniqueName, ENDPOINT } from './setup';

describe('IAM', () => {
  let iam: IAMClient;
  let roleName: string;
  let policyArn: string;

  beforeAll(() => {
    iam = makeClient(IAMClient, { endpoint: ENDPOINT });
    roleName = `test-role-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      if (policyArn) {
        await iam.send(new DetachRolePolicyCommand({ RoleName: roleName, PolicyArn: policyArn }));
      }
    } catch {
      // ignore
    }
    try {
      if (policyArn) {
        await iam.send(new DeletePolicyCommand({ PolicyArn: policyArn }));
      }
    } catch {
      // ignore
    }
    try {
      await iam.send(new DeleteRoleCommand({ RoleName: roleName }));
    } catch {
      // ignore
    }
  });

  it('should create role', async () => {
    const policyDoc = JSON.stringify({
      Version: '2012-10-17',
      Statement: [
        {
          Effect: 'Allow',
          Principal: { Service: 'lambda.amazonaws.com' },
          Action: 'sts:AssumeRole',
        },
      ],
    });

    const response = await iam.send(
      new CreateRoleCommand({ RoleName: roleName, AssumeRolePolicyDocument: policyDoc })
    );
    expect(response.Role?.Arn).toBeTruthy();
  });

  it('should get role', async () => {
    const response = await iam.send(new GetRoleCommand({ RoleName: roleName }));
    expect(response.Role?.RoleName).toBe(roleName);
  });

  it('should list roles', async () => {
    const response = await iam.send(new ListRolesCommand({}));
    expect(response.Roles?.some((r) => r.RoleName === roleName)).toBe(true);
  });

  it('should create policy', async () => {
    const response = await iam.send(
      new CreatePolicyCommand({
        PolicyName: `test-policy-${uniqueName()}`,
        PolicyDocument: JSON.stringify({
          Version: '2012-10-17',
          Statement: [{ Effect: 'Allow', Action: 's3:GetObject', Resource: '*' }],
        }),
      })
    );
    policyArn = response.Policy!.Arn!;
    expect(policyArn).toBeTruthy();
  });

  it('should attach role policy', async () => {
    await iam.send(
      new AttachRolePolicyCommand({ RoleName: roleName, PolicyArn: policyArn })
    );
  });

  it('should detach role policy', async () => {
    await iam.send(
      new DetachRolePolicyCommand({ RoleName: roleName, PolicyArn: policyArn })
    );
  });

  it('should delete policy', async () => {
    await iam.send(new DeletePolicyCommand({ PolicyArn: policyArn }));
    policyArn = '';
  });

  it('should delete role', async () => {
    await iam.send(new DeleteRoleCommand({ RoleName: roleName }));
  });
});

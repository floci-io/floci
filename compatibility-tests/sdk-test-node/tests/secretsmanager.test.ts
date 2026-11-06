/**
 * Secrets Manager integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  SecretsManagerClient,
  CreateSecretCommand,
  GetSecretValueCommand,
  UpdateSecretCommand,
  DeleteSecretCommand,
  ListSecretsCommand,
} from '@aws-sdk/client-secrets-manager';
import { makeClient, uniqueName } from './setup';

describe('Secrets Manager', () => {
  let sm: SecretsManagerClient;
  let secretName: string;

  beforeAll(() => {
    sm = makeClient(SecretsManagerClient);
    secretName = `test/secret/${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await sm.send(
        new DeleteSecretCommand({ SecretId: secretName, ForceDeleteWithoutRecovery: true })
      );
    } catch {
      // ignore
    }
  });

  it('should create secret', async () => {
    const response = await sm.send(
      new CreateSecretCommand({ Name: secretName, SecretString: '{"key":"value"}' })
    );
    expect(response.ARN).toBeTruthy();
  });

  it('should get secret value', async () => {
    const response = await sm.send(new GetSecretValueCommand({ SecretId: secretName }));
    expect(response.SecretString).toBe('{"key":"value"}');
  });

  it('should update secret', async () => {
    await sm.send(
      new UpdateSecretCommand({ SecretId: secretName, SecretString: '{"key":"updated"}' })
    );

    const response = await sm.send(new GetSecretValueCommand({ SecretId: secretName }));
    expect(response.SecretString).toBe('{"key":"updated"}');
  });

  it('should list secrets', async () => {
    const response = await sm.send(new ListSecretsCommand({}));
    expect(response.SecretList?.some((s) => s.Name === secretName)).toBe(true);
  });

  it('should delete secret', async () => {
    await sm.send(
      new DeleteSecretCommand({ SecretId: secretName, ForceDeleteWithoutRecovery: true })
    );
    secretName = '';
  });

  it('should fail to get deleted secret', async () => {
    await expect(
      sm.send(new GetSecretValueCommand({ SecretId: `test/secret/${uniqueName()}` }))
    ).rejects.toThrow();
  });
});

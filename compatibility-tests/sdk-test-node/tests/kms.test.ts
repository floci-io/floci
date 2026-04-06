/**
 * KMS integration tests.
 */

import { describe, it, expect, beforeAll } from 'vitest';
import {
  KMSClient,
  CreateKeyCommand,
  ListKeysCommand,
  DescribeKeyCommand,
  EncryptCommand,
  DecryptCommand,
  GenerateDataKeyCommand,
} from '@aws-sdk/client-kms';
import { makeClient, uniqueName } from './setup';

describe('KMS', () => {
  let kms: KMSClient;
  let keyId: string;
  let ciphertext: Uint8Array;

  beforeAll(() => {
    kms = makeClient(KMSClient);
  });

  it('should create key', async () => {
    const response = await kms.send(
      new CreateKeyCommand({ Description: `test-key-${uniqueName()}` })
    );
    keyId = response.KeyMetadata!.KeyId!;
    expect(keyId).toBeTruthy();
  });

  it('should describe key', async () => {
    const response = await kms.send(new DescribeKeyCommand({ KeyId: keyId }));
    expect(response.KeyMetadata?.Enabled).toBe(true);
  });

  it('should list keys', async () => {
    const response = await kms.send(new ListKeysCommand({}));
    expect(response.Keys?.some((k) => k.KeyId === keyId)).toBe(true);
  });

  it('should encrypt data', async () => {
    const response = await kms.send(
      new EncryptCommand({
        KeyId: keyId,
        Plaintext: Buffer.from('hello from test'),
      })
    );
    ciphertext = response.CiphertextBlob!;
    expect(ciphertext).toBeTruthy();
  });

  it('should decrypt data', async () => {
    const response = await kms.send(new DecryptCommand({ CiphertextBlob: ciphertext }));
    const plaintext = Buffer.from(response.Plaintext!).toString();
    expect(plaintext).toBe('hello from test');
  });

  it('should generate data key', async () => {
    const response = await kms.send(
      new GenerateDataKeyCommand({ KeyId: keyId, KeySpec: 'AES_256' })
    );
    expect(response.Plaintext).toBeTruthy();
    expect(response.CiphertextBlob).toBeTruthy();
  });
});

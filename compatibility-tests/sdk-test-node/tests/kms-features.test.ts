/**
 * Compatibility tests for KMS fixes:
 *   #269 — CreateKey applies Tags at creation time
 *   #258 — GetKeyPolicy returns the stored policy
 *   #259 — PutKeyPolicy updates the key policy
 */

import { describe, it, expect, afterEach } from 'vitest';
import {
  KMSClient,
  CreateKeyCommand,
  GetKeyPolicyCommand,
  PutKeyPolicyCommand,
  ListResourceTagsCommand,
  ScheduleKeyDeletionCommand,
} from '@aws-sdk/client-kms';
import { makeClient } from './setup';

const kms = makeClient(KMSClient);
const createdKeyIds: string[] = [];

async function createKey(opts: Parameters<typeof CreateKeyCommand>[0] = {}) {
  const resp = await kms.send(new CreateKeyCommand(opts));
  const keyId = resp.KeyMetadata!.KeyId!;
  createdKeyIds.push(keyId);
  return resp;
}

afterEach(async () => {
  // Schedule deletion for all keys created in the test
  while (createdKeyIds.length > 0) {
    const keyId = createdKeyIds.pop()!;
    try {
      await kms.send(new ScheduleKeyDeletionCommand({ KeyId: keyId, PendingWindowInDays: 7 }));
    } catch { /* ignore */ }
  }
});

// ── Issue #269 — CreateKey applies Tags ───────────────────────────────────────

describe('KMS features (#258 #259 #269)', () => {

  it('#269: CreateKey with Tags stores tags immediately', async () => {
    const resp = await createKey({
      Description: 'tagged-key',
      Tags: [
        { TagKey: 'env', TagValue: 'prod' },
        { TagKey: 'team', TagValue: 'platform' },
      ],
    });
    const keyId = resp.KeyMetadata!.KeyId!;

    const tags = await kms.send(new ListResourceTagsCommand({ KeyId: keyId }));
    const tagMap = Object.fromEntries(tags.Tags!.map(t => [t.TagKey!, t.TagValue!]));

    expect(tagMap['env']).toBe('prod');
    expect(tagMap['team']).toBe('platform');
  });

  it('#269: CreateKey without Tags has empty tag list', async () => {
    const resp = await createKey({ Description: 'no-tags-key' });
    const keyId = resp.KeyMetadata!.KeyId!;

    const tags = await kms.send(new ListResourceTagsCommand({ KeyId: keyId }));
    expect(tags.Tags).toHaveLength(0);
  });

  // ── Issue #258 — GetKeyPolicy ───────────────────────────────────────────────

  it('#258: CreateKey without Policy returns a non-empty default policy', async () => {
    const resp = await createKey({ Description: 'default-policy-key' });
    const keyId = resp.KeyMetadata!.KeyId!;

    const policyResp = await kms.send(new GetKeyPolicyCommand({ KeyId: keyId }));
    expect(policyResp.Policy).toBeTruthy();
    expect(policyResp.PolicyName).toBe('default');
    expect(policyResp.Policy).toContain('kms:*');
  });

  it('#258: CreateKey with Policy stores and returns that policy', async () => {
    const customPolicy = JSON.stringify({
      Version: '2012-10-17',
      Statement: [{
        Sid: 'Custom',
        Effect: 'Allow',
        Principal: { AWS: 'arn:aws:iam::000000000000:root' },
        Action: 'kms:*',
        Resource: '*',
      }],
    });

    const resp = await createKey({ Description: 'custom-policy-key', Policy: customPolicy });
    const keyId = resp.KeyMetadata!.KeyId!;

    const policyResp = await kms.send(new GetKeyPolicyCommand({ KeyId: keyId }));
    expect(policyResp.Policy).toBe(customPolicy);
    expect(policyResp.PolicyName).toBe('default');
  });

  // ── Issue #259 — PutKeyPolicy ───────────────────────────────────────────────

  it('#259: PutKeyPolicy updates the key policy', async () => {
    const resp = await createKey({ Description: 'put-policy-key' });
    const keyId = resp.KeyMetadata!.KeyId!;

    const newPolicy = JSON.stringify({
      Version: '2012-10-17',
      Statement: [{
        Sid: 'Updated',
        Effect: 'Allow',
        Principal: { AWS: 'arn:aws:iam::000000000000:root' },
        Action: 'kms:Decrypt',
        Resource: '*',
      }],
    });

    await kms.send(new PutKeyPolicyCommand({ KeyId: keyId, Policy: newPolicy }));

    const policyResp = await kms.send(new GetKeyPolicyCommand({ KeyId: keyId }));
    expect(policyResp.Policy).toBe(newPolicy);
  });

  it('#259: PutKeyPolicy round-trip — get, change, verify', async () => {
    const resp = await createKey({ Description: 'round-trip-key' });
    const keyId = resp.KeyMetadata!.KeyId!;

    const initial = (await kms.send(new GetKeyPolicyCommand({ KeyId: keyId }))).Policy!;
    expect(initial).toBeTruthy();

    const updated = JSON.stringify({
      Version: '2012-10-17',
      Statement: [{
        Sid: 'RoundTrip',
        Effect: 'Allow',
        Principal: { AWS: 'arn:aws:iam::000000000000:root' },
        Action: 'kms:*',
        Resource: '*',
      }],
    });

    await kms.send(new PutKeyPolicyCommand({ KeyId: keyId, Policy: updated }));

    const after = (await kms.send(new GetKeyPolicyCommand({ KeyId: keyId }))).Policy!;
    expect(after).toBe(updated);
    expect(after).not.toBe(initial);
  });
});

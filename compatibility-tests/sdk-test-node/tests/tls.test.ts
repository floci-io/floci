/**
 * TLS/HTTPS connectivity tests for floci.
 *
 * Verifies that AWS SDK v3 clients can interact with floci over HTTPS
 * when TLS is enabled with a self-signed certificate.
 *
 * These tests run within the existing test suite. Since Floci serves HTTP
 * and HTTPS simultaneously, existing HTTP tests are unaffected. The HTTPS
 * endpoint is derived from the HTTP endpoint by swapping the scheme.
 *
 * Prerequisites:
 *   - Floci must be started with FLOCI_TLS_ENABLED=true
 *   - NODE_TLS_REJECT_UNAUTHORIZED=0 must be set in the test environment
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { STSClient, GetCallerIdentityCommand } from '@aws-sdk/client-sts';
import {
  SQSClient,
  CreateQueueCommand,
  SendMessageCommand,
  DeleteQueueCommand,
} from '@aws-sdk/client-sqs';
import {
  SSMClient,
  PutParameterCommand,
  GetParameterCommand,
  DeleteParameterCommand,
} from '@aws-sdk/client-ssm';
import {
  S3Client,
  CreateBucketCommand,
  PutObjectCommand,
  GetObjectCommand,
  DeleteObjectCommand,
  DeleteBucketCommand,
} from '@aws-sdk/client-s3';
import { ENDPOINT, uniqueName, ACCOUNT, REGION, CREDS } from './setup';

// Derive the HTTPS endpoint from the HTTP endpoint by swapping the scheme.
// When TLS is enabled, Floci serves HTTPS on port 4566 (same port as HTTP without TLS).
const HTTPS_ENDPOINT = ENDPOINT.replace(/^http:\/\//, 'https://');

const TLS_CLIENT_CONFIG = {
  endpoint: HTTPS_ENDPOINT,
  region: REGION,
  credentials: CREDS,
  forcePathStyle: true,
};

function makeTlsClient<T>(
  ClientClass: new (config: typeof TLS_CLIENT_CONFIG) => T,
): T {
  return new ClientClass(TLS_CLIENT_CONFIG);
}

describe('TLS — STS over HTTPS', () => {
  let sts: STSClient;

  beforeAll(() => {
    sts = makeTlsClient(STSClient);
  });

  it('should call GetCallerIdentity over HTTPS', async () => {
    const response = await sts.send(new GetCallerIdentityCommand({}));
    expect(response.Account).toBe(ACCOUNT);
    expect(response.Arn).toBeTruthy();
  });
});

describe('TLS — SQS over HTTPS', () => {
  let sqs: SQSClient;
  let queueUrl: string;

  beforeAll(() => {
    sqs = makeTlsClient(SQSClient);
  });

  afterAll(async () => {
    if (queueUrl) {
      try {
        await sqs.send(new DeleteQueueCommand({ QueueUrl: queueUrl }));
      } catch {
        // ignore
      }
    }
  });

  it('should create a queue and get HTTPS queue URL', async () => {
    const name = uniqueName('tls-sqs');
    const response = await sqs.send(new CreateQueueCommand({ QueueName: name }));
    queueUrl = response.QueueUrl!;
    expect(queueUrl).toMatch(/^https:\/\//);
    expect(queueUrl).toContain(name);
  });

  it('should send a message over HTTPS', async () => {
    const response = await sqs.send(
      new SendMessageCommand({
        QueueUrl: queueUrl,
        MessageBody: 'hello-tls',
      })
    );
    expect(response.MessageId).toBeTruthy();
  });
});

describe('TLS — SSM over HTTPS', () => {
  let ssm: SSMClient;
  const paramName = `/${uniqueName('tls-param')}`;

  beforeAll(() => {
    ssm = makeTlsClient(SSMClient);
  });

  afterAll(async () => {
    try {
      await ssm.send(new DeleteParameterCommand({ Name: paramName }));
    } catch {
      // ignore
    }
  });

  it('should put and get a parameter over HTTPS', async () => {
    await ssm.send(
      new PutParameterCommand({
        Name: paramName,
        Value: 'tls-value',
        Type: 'String',
      })
    );

    const response = await ssm.send(
      new GetParameterCommand({ Name: paramName })
    );
    expect(response.Parameter?.Value).toBe('tls-value');
  });
});

describe('TLS — S3 over HTTPS', () => {
  let s3: S3Client;
  const bucketName = uniqueName('tls-s3');
  const key = 'test-object.txt';

  beforeAll(async () => {
    s3 = makeTlsClient(S3Client);
    await s3.send(new CreateBucketCommand({ Bucket: bucketName }));
  });

  afterAll(async () => {
    try {
      await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: key }));
      await s3.send(new DeleteBucketCommand({ Bucket: bucketName }));
    } catch {
      // ignore
    }
  });

  it('should put and get an object over HTTPS', async () => {
    const body = 'hello from TLS';
    await s3.send(
      new PutObjectCommand({
        Bucket: bucketName,
        Key: key,
        Body: body,
      })
    );

    const response = await s3.send(
      new GetObjectCommand({ Bucket: bucketName, Key: key })
    );
    const text = await response.Body!.transformToString();
    expect(text).toBe(body);
  });
});

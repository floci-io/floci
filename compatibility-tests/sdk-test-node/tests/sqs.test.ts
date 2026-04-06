/**
 * SQS integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  SQSClient,
  CreateQueueCommand,
  GetQueueUrlCommand,
  SendMessageCommand,
  ReceiveMessageCommand,
  DeleteMessageCommand,
  GetQueueAttributesCommand,
  DeleteQueueCommand,
  SendMessageBatchCommand,
  SetQueueAttributesCommand,
} from '@aws-sdk/client-sqs';
import { makeClient, uniqueName, ENDPOINT, ACCOUNT } from './setup';

describe('SQS', () => {
  let sqs: SQSClient;
  let queueName: string;
  let queueUrl: string;
  let fifoQueueName: string;

  beforeAll(() => {
    sqs = makeClient(SQSClient);
    queueName = `test-queue-${uniqueName()}`;
    fifoQueueName = `test-fifo-${uniqueName()}.fifo`;
  });

  afterAll(async () => {
    try {
      await sqs.send(new DeleteQueueCommand({ QueueUrl: queueUrl }));
    } catch {
      // ignore
    }
    try {
      await sqs.send(
        new DeleteQueueCommand({ QueueUrl: `${ENDPOINT}/${ACCOUNT}/${fifoQueueName}` })
      );
    } catch {
      // ignore
    }
  });

  it('should create a queue', async () => {
    const response = await sqs.send(new CreateQueueCommand({ QueueName: queueName }));
    queueUrl = response.QueueUrl!;
    expect(queueUrl).toBeTruthy();
  });

  it('should get queue URL', async () => {
    const response = await sqs.send(new GetQueueUrlCommand({ QueueName: queueName }));
    expect(response.QueueUrl).toBe(queueUrl);
  });

  it('should get queue attributes', async () => {
    const response = await sqs.send(
      new GetQueueAttributesCommand({ QueueUrl: queueUrl, AttributeNames: ['All'] })
    );
    expect(response.Attributes?.QueueArn).toBeTruthy();
  });

  it('should send and receive message', async () => {
    await sqs.send(
      new SendMessageCommand({ QueueUrl: queueUrl, MessageBody: 'test-message' })
    );

    const response = await sqs.send(
      new ReceiveMessageCommand({
        QueueUrl: queueUrl,
        MaxNumberOfMessages: 1,
        WaitTimeSeconds: 1,
      })
    );
    expect(response.Messages?.length).toBeGreaterThan(0);
    expect(response.Messages?.[0].Body).toBe('test-message');

    // Delete the message
    await sqs.send(
      new DeleteMessageCommand({
        QueueUrl: queueUrl,
        ReceiptHandle: response.Messages![0].ReceiptHandle!,
      })
    );
  });

  it('should send batch messages', async () => {
    await sqs.send(
      new SendMessageBatchCommand({
        QueueUrl: queueUrl,
        Entries: [
          { Id: '1', MessageBody: 'batch-msg-1' },
          { Id: '2', MessageBody: 'batch-msg-2' },
        ],
      })
    );
  });

  it('should create FIFO queue', async () => {
    const response = await sqs.send(
      new CreateQueueCommand({
        QueueName: fifoQueueName,
        Attributes: { FifoQueue: 'true', ContentBasedDeduplication: 'true' },
      })
    );
    expect(response.QueueUrl).toContain('.fifo');
  });

  it('should set queue attributes', async () => {
    await sqs.send(
      new SetQueueAttributesCommand({
        QueueUrl: queueUrl,
        Attributes: { VisibilityTimeout: '60' },
      })
    );
  });

  it('should delete queue', async () => {
    await sqs.send(new DeleteQueueCommand({ QueueUrl: queueUrl }));
    // Reset for cleanup
    queueUrl = '';
  });
});

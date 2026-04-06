/**
 * SNS integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  SNSClient,
  CreateTopicCommand,
  SubscribeCommand,
  PublishCommand,
  ListTopicsCommand,
  ListSubscriptionsByTopicCommand,
  UnsubscribeCommand,
  DeleteTopicCommand,
  GetSubscriptionAttributesCommand,
  SetSubscriptionAttributesCommand,
  PublishBatchCommand,
} from '@aws-sdk/client-sns';
import {
  SQSClient,
  CreateQueueCommand,
  GetQueueAttributesCommand,
  DeleteQueueCommand,
} from '@aws-sdk/client-sqs';
import { makeClient, uniqueName } from './setup';

describe('SNS', () => {
  let sns: SNSClient;
  let sqs: SQSClient;
  let topicArn: string;
  let subscriptionArn: string;
  let queueUrl: string;
  let queueArn: string;

  beforeAll(async () => {
    sns = makeClient(SNSClient);
    sqs = makeClient(SQSClient);

    // Create backing SQS queue for subscription
    const queueResult = await sqs.send(
      new CreateQueueCommand({ QueueName: `sns-target-${uniqueName()}` })
    );
    queueUrl = queueResult.QueueUrl!;
    const queueAttr = await sqs.send(
      new GetQueueAttributesCommand({ QueueUrl: queueUrl, AttributeNames: ['QueueArn'] })
    );
    queueArn = queueAttr.Attributes!.QueueArn!;
  });

  afterAll(async () => {
    try {
      if (subscriptionArn) {
        await sns.send(new UnsubscribeCommand({ SubscriptionArn: subscriptionArn }));
      }
    } catch {
      // ignore
    }
    try {
      if (topicArn) {
        await sns.send(new DeleteTopicCommand({ TopicArn: topicArn }));
      }
    } catch {
      // ignore
    }
    try {
      if (queueUrl) {
        await sqs.send(new DeleteQueueCommand({ QueueUrl: queueUrl }));
      }
    } catch {
      // ignore
    }
  });

  it('should create a topic', async () => {
    const response = await sns.send(
      new CreateTopicCommand({ Name: `test-topic-${uniqueName()}` })
    );
    topicArn = response.TopicArn!;
    expect(topicArn).toBeTruthy();
  });

  it('should list topics', async () => {
    const response = await sns.send(new ListTopicsCommand({}));
    expect(response.Topics?.some((t) => t.TopicArn === topicArn)).toBe(true);
  });

  it('should subscribe SQS to topic', async () => {
    const response = await sns.send(
      new SubscribeCommand({
        TopicArn: topicArn,
        Protocol: 'sqs',
        Endpoint: queueArn,
      })
    );
    subscriptionArn = response.SubscriptionArn!;
    expect(subscriptionArn).toBeTruthy();
  });

  it('should list subscriptions by topic', async () => {
    const response = await sns.send(
      new ListSubscriptionsByTopicCommand({ TopicArn: topicArn })
    );
    expect(response.Subscriptions?.length).toBeGreaterThan(0);
  });

  it('should get subscription attributes', async () => {
    const response = await sns.send(
      new GetSubscriptionAttributesCommand({ SubscriptionArn: subscriptionArn })
    );
    expect(response.Attributes?.Protocol).toBe('sqs');
    expect(response.Attributes?.TopicArn).toBe(topicArn);
  });

  it('should set subscription attributes', async () => {
    await sns.send(
      new SetSubscriptionAttributesCommand({
        SubscriptionArn: subscriptionArn,
        AttributeName: 'RawMessageDelivery',
        AttributeValue: 'true',
      })
    );
  });

  it('should publish message', async () => {
    const response = await sns.send(
      new PublishCommand({ TopicArn: topicArn, Message: 'test-sns-message' })
    );
    expect(response.MessageId).toBeTruthy();
  });

  it('should publish batch messages', async () => {
    const response = await sns.send(
      new PublishBatchCommand({
        TopicArn: topicArn,
        PublishBatchRequestEntries: [
          { Id: '1', Message: 'batch-msg-1' },
          { Id: '2', Message: 'batch-msg-2' },
        ],
      })
    );
    expect(response.Successful?.length).toBe(2);
  });

  it('should unsubscribe', async () => {
    await sns.send(new UnsubscribeCommand({ SubscriptionArn: subscriptionArn }));
    subscriptionArn = '';
  });

  it('should delete topic', async () => {
    await sns.send(new DeleteTopicCommand({ TopicArn: topicArn }));
    topicArn = '';
  });
});

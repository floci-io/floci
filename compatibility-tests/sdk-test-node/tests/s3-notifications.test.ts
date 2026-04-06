/**
 * S3 Notifications integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  S3Client,
  CreateBucketCommand,
  PutObjectCommand,
  DeleteObjectCommand,
  DeleteBucketCommand,
  PutBucketNotificationConfigurationCommand,
  GetBucketNotificationConfigurationCommand,
} from '@aws-sdk/client-s3';
import {
  SQSClient,
  CreateQueueCommand,
  GetQueueAttributesCommand,
  DeleteQueueCommand,
} from '@aws-sdk/client-sqs';
import { SNSClient, CreateTopicCommand, DeleteTopicCommand } from '@aws-sdk/client-sns';
import { makeClient, uniqueName } from './setup';

describe('S3 Notifications', () => {
  let s3: S3Client;
  let sqs: SQSClient;
  let sns: SNSClient;
  let bucketName: string;
  let queueUrl: string;
  let queueArn: string;
  let topicArn: string;

  beforeAll(async () => {
    s3 = makeClient(S3Client, { forcePathStyle: true });
    sqs = makeClient(SQSClient);
    sns = makeClient(SNSClient);

    bucketName = `notif-test-bucket-${uniqueName()}`;

    // Create SQS queue
    const queueResult = await sqs.send(
      new CreateQueueCommand({ QueueName: `notif-queue-${uniqueName()}` })
    );
    queueUrl = queueResult.QueueUrl!;
    const attrs = await sqs.send(
      new GetQueueAttributesCommand({ QueueUrl: queueUrl, AttributeNames: ['QueueArn'] })
    );
    queueArn = attrs.Attributes!.QueueArn!;

    // Create SNS topic
    const topicResult = await sns.send(
      new CreateTopicCommand({ Name: `notif-topic-${uniqueName()}` })
    );
    topicArn = topicResult.TopicArn!;

    // Create S3 bucket
    await s3.send(new CreateBucketCommand({ Bucket: bucketName }));
  });

  afterAll(async () => {
    try {
      await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: 'test-object.txt' }));
    } catch {
      // ignore
    }
    try {
      await s3.send(new DeleteBucketCommand({ Bucket: bucketName }));
    } catch {
      // ignore
    }
    try {
      await sqs.send(new DeleteQueueCommand({ QueueUrl: queueUrl }));
    } catch {
      // ignore
    }
    try {
      await sns.send(new DeleteTopicCommand({ TopicArn: topicArn }));
    } catch {
      // ignore
    }
  });

  it('should put bucket notification configuration', async () => {
    await s3.send(
      new PutBucketNotificationConfigurationCommand({
        Bucket: bucketName,
        NotificationConfiguration: {
          QueueConfigurations: [
            {
              Id: 'sqs-filtered',
              QueueArn: queueArn,
              Events: ['s3:ObjectCreated:*'],
              Filter: {
                Key: {
                  FilterRules: [
                    { Name: 'prefix', Value: 'incoming/' },
                    { Name: 'suffix', Value: '.csv' },
                  ],
                },
              },
            },
          ],
          TopicConfigurations: [
            {
              Id: 'sns-filtered',
              TopicArn: topicArn,
              Events: ['s3:ObjectRemoved:*'],
              Filter: {
                Key: {
                  FilterRules: [
                    { Name: 'prefix', Value: '' },
                    { Name: 'suffix', Value: '.txt' },
                  ],
                },
              },
            },
          ],
        },
      })
    );
  });

  it('should get bucket notification configuration', async () => {
    const response = await s3.send(
      new GetBucketNotificationConfigurationCommand({ Bucket: bucketName })
    );

    // Verify SQS configuration
    const sqsConfig = response.QueueConfigurations?.find((c) => c.Id === 'sqs-filtered');
    expect(sqsConfig).toBeTruthy();
    expect(sqsConfig?.QueueArn).toBe(queueArn);
    expect(sqsConfig?.Events).toContain('s3:ObjectCreated:*');

    const sqsRules = sqsConfig?.Filter?.Key?.FilterRules || [];
    expect(sqsRules.some((r) => r.Name === 'prefix' && r.Value === 'incoming/')).toBe(true);
    expect(sqsRules.some((r) => r.Name === 'suffix' && r.Value === '.csv')).toBe(true);

    // Verify SNS configuration
    const snsConfig = response.TopicConfigurations?.find((c) => c.Id === 'sns-filtered');
    expect(snsConfig).toBeTruthy();
    expect(snsConfig?.TopicArn).toBe(topicArn);
    expect(snsConfig?.Events).toContain('s3:ObjectRemoved:*');

    const snsRules = snsConfig?.Filter?.Key?.FilterRules || [];
    expect(snsRules.some((r) => r.Name === 'suffix' && r.Value === '.txt')).toBe(true);
  });

  it('should clear notification configuration', async () => {
    await s3.send(
      new PutBucketNotificationConfigurationCommand({
        Bucket: bucketName,
        NotificationConfiguration: {},
      })
    );

    const response = await s3.send(
      new GetBucketNotificationConfigurationCommand({ Bucket: bucketName })
    );
    expect(response.QueueConfigurations?.length || 0).toBe(0);
    expect(response.TopicConfigurations?.length || 0).toBe(0);
  });
});

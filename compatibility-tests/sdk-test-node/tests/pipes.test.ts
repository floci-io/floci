/**
 * EventBridge Pipes integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  PipesClient,
  CreatePipeCommand,
  DescribePipeCommand,
  ListPipesCommand,
  UpdatePipeCommand,
  DeletePipeCommand,
  StartPipeCommand,
  StopPipeCommand,
  RequestedPipeState,
} from '@aws-sdk/client-pipes';
import {
  SQSClient,
  CreateQueueCommand,
  DeleteQueueCommand,
  SendMessageCommand,
  ReceiveMessageCommand,
  GetQueueUrlCommand,
  GetQueueAttributesCommand,
} from '@aws-sdk/client-sqs';
import { makeClient, uniqueName, ACCOUNT, REGION, sleep } from './setup';

const ROLE_ARN = `arn:aws:iam::${ACCOUNT}:role/pipe-role`;

const sqsArn = (queueName: string) =>
  `arn:aws:sqs:${REGION}:${ACCOUNT}:${queueName}`;

const getQueueUrl = async (sqs: SQSClient, name: string): Promise<string> => {
  try {
    const r = await sqs.send(new GetQueueUrlCommand({ QueueName: name }));
    return r.QueueUrl ?? '';
  } catch {
    return '';
  }
};

describe('Pipes CRUD', () => {
  let pipes: PipesClient;
  let sqs: SQSClient;
  let pipeName: string;
  let srcQueue: string;
  let tgtQueue: string;

  beforeAll(() => {
    pipes = makeClient(PipesClient);
    sqs = makeClient(SQSClient);
  });

  afterAll(async () => {
    // Cleanup is handled per-test
  });

  it('should create a pipe in STOPPED state', async () => {
    pipeName = `node-pipe-create-${uniqueName()}`;
    srcQueue = `node-pipe-src-create-${uniqueName()}`;
    tgtQueue = `node-pipe-tgt-create-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      const response = await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );
      expect(response.CurrentState).toBe('STOPPED');
      expect(response.Arn).toContain(pipeName);
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });

  it('should describe a pipe', async () => {
    pipeName = `node-pipe-describe-${uniqueName()}`;
    srcQueue = `node-pipe-src-describe-${uniqueName()}`;
    tgtQueue = `node-pipe-tgt-describe-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      const response = await pipes.send(new DescribePipeCommand({ Name: pipeName }));
      expect(response.Name).toBe(pipeName);
      expect(response.Source).toBe(sqsArn(srcQueue));
      expect(response.Target).toBe(sqsArn(tgtQueue));
      expect(response.CurrentState).toBe('STOPPED');
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });

  it('should list pipes', async () => {
    pipeName = `node-pipe-list-${uniqueName()}`;
    srcQueue = `node-pipe-src-list-${uniqueName()}`;
    tgtQueue = `node-pipe-tgt-list-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      const response = await pipes.send(new ListPipesCommand({}));
      const names = response.Pipes?.map((p) => p.Name) ?? [];
      expect(names).toContain(pipeName);
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });

  it('should update a pipe', async () => {
    pipeName = `node-pipe-update-${uniqueName()}`;
    srcQueue = `node-pipe-src-update-${uniqueName()}`;
    tgtQueue = `node-pipe-tgt-update-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      await pipes.send(
        new UpdatePipeCommand({
          Name: pipeName,
          RoleArn: ROLE_ARN,
          Description: 'updated via SDK',
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      const response = await pipes.send(new DescribePipeCommand({ Name: pipeName }));
      expect(response.Description).toBe('updated via SDK');
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });

  it('should delete a pipe', async () => {
    pipeName = `node-pipe-delete-${uniqueName()}`;
    srcQueue = `node-pipe-src-delete-${uniqueName()}`;
    tgtQueue = `node-pipe-tgt-delete-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      await pipes.send(new DeletePipeCommand({ Name: pipeName }));

      await expect(
        pipes.send(new DescribePipeCommand({ Name: pipeName }))
      ).rejects.toThrow();
    } finally {
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });

  it('should return error for non-existent pipe', async () => {
    await expect(
      pipes.send(new DescribePipeCommand({ Name: 'nonexistent-pipe' }))
    ).rejects.toThrow();
  });
});

describe('Pipes Lifecycle', () => {
  let pipes: PipesClient;
  let sqs: SQSClient;

  beforeAll(() => {
    pipes = makeClient(PipesClient);
    sqs = makeClient(SQSClient);
  });

  it('should start and stop a pipe', async () => {
    const pipeName = `node-pipe-lifecycle-${uniqueName()}`;
    const srcQueue = `node-pipe-src-lifecycle-${uniqueName()}`;
    const tgtQueue = `node-pipe-tgt-lifecycle-${uniqueName()}`;

    await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      const startResponse = await pipes.send(new StartPipeCommand({ Name: pipeName }));
      expect(startResponse.CurrentState).toBe('RUNNING');

      const stopResponse = await pipes.send(new StopPipeCommand({ Name: pipeName }));
      expect(stopResponse.CurrentState).toBe('STOPPED');
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, srcQueue) })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: await getQueueUrl(sqs, tgtQueue) })).catch(() => {});
    }
  });
});

describe('Pipes Polling', () => {
  let pipes: PipesClient;
  let sqs: SQSClient;

  beforeAll(() => {
    pipes = makeClient(PipesClient);
    sqs = makeClient(SQSClient);
  });

  it('should forward SQS messages to SQS target', async () => {
    const pipeName = `node-pipe-fwd-${uniqueName()}`;
    const srcQueue = `node-pipe-src-fwd-${uniqueName()}`;
    const tgtQueue = `node-pipe-tgt-fwd-${uniqueName()}`;

    const srcResp = await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    const srcUrl = srcResp.QueueUrl!;
    const tgtResp = await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));
    const tgtUrl = tgtResp.QueueUrl!;

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.RUNNING,
        })
      );

      await sqs.send(
        new SendMessageCommand({ QueueUrl: srcUrl, MessageBody: 'hello from pipes' })
      );

      let found = false;
      for (let i = 0; i < 15; i++) {
        await sleep(1000);
        const r = await sqs.send(
          new ReceiveMessageCommand({
            QueueUrl: tgtUrl,
            MaxNumberOfMessages: 1,
            WaitTimeSeconds: 1,
          })
        );
        if (r.Messages?.length && r.Messages[0].Body?.includes('hello from pipes')) {
          found = true;
          break;
        }
      }

      expect(found).toBe(true);
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: srcUrl })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: tgtUrl })).catch(() => {});
    }
  });

  it('should not forward messages when pipe is stopped', async () => {
    const pipeName = `node-pipe-nofwd-${uniqueName()}`;
    const srcQueue = `node-pipe-src-nofwd-${uniqueName()}`;
    const tgtQueue = `node-pipe-tgt-nofwd-${uniqueName()}`;

    const srcResp = await sqs.send(new CreateQueueCommand({ QueueName: srcQueue }));
    const srcUrl = srcResp.QueueUrl!;
    const tgtResp = await sqs.send(new CreateQueueCommand({ QueueName: tgtQueue }));
    const tgtUrl = tgtResp.QueueUrl!;

    try {
      await pipes.send(
        new CreatePipeCommand({
          Name: pipeName,
          Source: sqsArn(srcQueue),
          Target: sqsArn(tgtQueue),
          RoleArn: ROLE_ARN,
          DesiredState: RequestedPipeState.STOPPED,
        })
      );

      await sqs.send(
        new SendMessageCommand({ QueueUrl: srcUrl, MessageBody: 'should not forward' })
      );

      await sleep(3000);

      const attrs = await sqs.send(
        new GetQueueAttributesCommand({
          QueueUrl: srcUrl,
          AttributeNames: ['ApproximateNumberOfMessages'],
        })
      );
      expect(attrs.Attributes?.ApproximateNumberOfMessages).toBe('1');

      const r = await sqs.send(
        new ReceiveMessageCommand({
          QueueUrl: tgtUrl,
          MaxNumberOfMessages: 1,
          WaitTimeSeconds: 1,
        })
      );
      expect(r.Messages ?? []).toHaveLength(0);
    } finally {
      await pipes.send(new DeletePipeCommand({ Name: pipeName })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: srcUrl })).catch(() => {});
      await sqs.send(new DeleteQueueCommand({ QueueUrl: tgtUrl })).catch(() => {});
    }
  });
});

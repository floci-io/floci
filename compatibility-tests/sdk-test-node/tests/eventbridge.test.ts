/**
 * EventBridge integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  EventBridgeClient,
  CreateEventBusCommand,
  DeleteEventBusCommand,
  DescribeEventBusCommand,
  ListEventBusesCommand,
  PutRuleCommand,
  DeleteRuleCommand,
  DescribeRuleCommand,
  ListRulesCommand,
  EnableRuleCommand,
  DisableRuleCommand,
  PutTargetsCommand,
  RemoveTargetsCommand,
  ListTargetsByRuleCommand,
  PutEventsCommand,
  ListTagsForResourceCommand,
} from '@aws-sdk/client-eventbridge';
import {
  SQSClient,
  CreateQueueCommand,
  DeleteQueueCommand,
  GetQueueAttributesCommand,
  ReceiveMessageCommand,
} from '@aws-sdk/client-sqs';
import { makeClient, uniqueName } from './setup';

describe('EventBridge', () => {
  let eb: EventBridgeClient;
  let sqs: SQSClient;
  let ruleName: string;
  let busName: string;
  let ruleArn: string;
  let sinkQueueUrl: string;
  let sinkQueueArn: string;
  let transformerQueueUrl: string;
  let transformerQueueArn: string;

  beforeAll(() => {
    eb = makeClient(EventBridgeClient);
    sqs = makeClient(SQSClient);
    ruleName = uniqueName('eb-rule');
    busName = uniqueName('eb-bus');
  });

  afterAll(async () => {
    try {
      await eb.send(new RemoveTargetsCommand({ Rule: ruleName, Ids: ['sqs-target', 'transformer-target'] }));
    } catch { /* ignore */ }
    try {
      await eb.send(new DeleteRuleCommand({ Name: ruleName }));
    } catch { /* ignore */ }
    try {
      await eb.send(new DeleteEventBusCommand({ Name: busName }));
    } catch { /* ignore */ }
    try {
      if (sinkQueueUrl) await sqs.send(new DeleteQueueCommand({ QueueUrl: sinkQueueUrl }));
    } catch { /* ignore */ }
    try {
      if (transformerQueueUrl) await sqs.send(new DeleteQueueCommand({ QueueUrl: transformerQueueUrl }));
    } catch { /* ignore */ }
  });

  // ──────────────────────────── Event Buses ────────────────────────────

  it('should create an event bus', async () => {
    const response = await eb.send(new CreateEventBusCommand({ Name: busName }));
    expect(response.EventBusArn).toContain(busName);
  });

  it('should describe an event bus', async () => {
    const response = await eb.send(new DescribeEventBusCommand({ Name: busName }));
    expect(response.Name).toBe(busName);
    expect(response.Arn).toContain(busName);
  });

  it('should list event buses including default', async () => {
    const response = await eb.send(new ListEventBusesCommand({}));
    const names = response.EventBuses!.map(b => b.Name);
    expect(names).toContain('default');
    expect(names).toContain(busName);
  });

  // ──────────────────────────── Rules ────────────────────────────

  it('should create a rule', async () => {
    const response = await eb.send(new PutRuleCommand({
      Name: ruleName,
      EventPattern: JSON.stringify({ source: ['com.myapp'] }),
      State: 'ENABLED',
      Description: 'Test rule',
    }));
    ruleArn = response.RuleArn!;
    expect(ruleArn).toContain(ruleName);
  });

  it('should describe the rule', async () => {
    const response = await eb.send(new DescribeRuleCommand({ Name: ruleName }));
    expect(response.Name).toBe(ruleName);
    expect(response.State).toBe('ENABLED');
    expect(response.EventPattern).toContain('com.myapp');
  });

  it('should list rules', async () => {
    const response = await eb.send(new ListRulesCommand({}));
    const names = response.Rules!.map(r => r.Name);
    expect(names).toContain(ruleName);
  });

  it('should disable and re-enable a rule', async () => {
    await eb.send(new DisableRuleCommand({ Name: ruleName }));
    expect((await eb.send(new DescribeRuleCommand({ Name: ruleName }))).State).toBe('DISABLED');

    await eb.send(new EnableRuleCommand({ Name: ruleName }));
    expect((await eb.send(new DescribeRuleCommand({ Name: ruleName }))).State).toBe('ENABLED');
  });

  // ──────────────────────────── Targets + PutEvents ────────────────────────────

  it('should create sink SQS queue', async () => {
    sinkQueueUrl = (await sqs.send(new CreateQueueCommand({ QueueName: uniqueName('eb-sink') }))).QueueUrl!;
    const attrs = await sqs.send(new GetQueueAttributesCommand({
      QueueUrl: sinkQueueUrl, AttributeNames: ['QueueArn'],
    }));
    sinkQueueArn = attrs.Attributes!['QueueArn'];
    expect(sinkQueueArn).toBeTruthy();
  });

  it('should put SQS target on rule', async () => {
    const response = await eb.send(new PutTargetsCommand({
      Rule: ruleName,
      Targets: [{ Id: 'sqs-target', Arn: sinkQueueArn }],
    }));
    expect(response.FailedEntryCount).toBe(0);
  });

  it('should list targets by rule', async () => {
    const response = await eb.send(new ListTargetsByRuleCommand({ Rule: ruleName }));
    const ids = response.Targets!.map(t => t.Id);
    expect(ids).toContain('sqs-target');
  });

  it('should deliver matching event to SQS target', async () => {
    await eb.send(new PutEventsCommand({
      Entries: [{
        Source: 'com.myapp',
        DetailType: 'OrderPlaced',
        Detail: JSON.stringify({ orderId: '123' }),
      }],
    }));

    const msg = await sqs.send(new ReceiveMessageCommand({
      QueueUrl: sinkQueueUrl,
      MaxNumberOfMessages: 1,
      WaitTimeSeconds: 2,
    }));
    expect(msg.Messages).toHaveLength(1);
    expect(msg.Messages![0].Body).toContain('com.myapp');
    expect(msg.Messages![0].Body).toContain('OrderPlaced');
  });

  it('should not deliver non-matching event', async () => {
    await eb.send(new PutEventsCommand({
      Entries: [{
        Source: 'other.app',
        DetailType: 'Ignored',
        Detail: '{}',
      }],
    }));

    const msg = await sqs.send(new ReceiveMessageCommand({
      QueueUrl: sinkQueueUrl,
      MaxNumberOfMessages: 1,
    }));
    expect(msg.Messages ?? []).toHaveLength(0);
  });

  // ──────────────────────────── InputTransformer ────────────────────────────

  it('should create transformer SQS queue', async () => {
    transformerQueueUrl = (await sqs.send(new CreateQueueCommand({ QueueName: uniqueName('eb-xform') }))).QueueUrl!;
    const attrs = await sqs.send(new GetQueueAttributesCommand({
      QueueUrl: transformerQueueUrl, AttributeNames: ['QueueArn'],
    }));
    transformerQueueArn = attrs.Attributes!['QueueArn'];
    expect(transformerQueueArn).toBeTruthy();
  });

  it('should put target with InputTransformer', async () => {
    const response = await eb.send(new PutTargetsCommand({
      Rule: ruleName,
      Targets: [{
        Id: 'transformer-target',
        Arn: transformerQueueArn,
        InputTransformer: {
          InputPathsMap: { src: '$.source', type: '$.detail-type' },
          InputTemplate: '{"source":"<src>","type":"<type>"}',
        },
      }],
    }));
    expect(response.FailedEntryCount).toBe(0);
  });

  it('should store InputTransformer correctly on target', async () => {
    const response = await eb.send(new ListTargetsByRuleCommand({ Rule: ruleName }));
    const xformTarget = response.Targets!.find(t => t.Id === 'transformer-target');
    expect(xformTarget).toBeDefined();
    expect(xformTarget!.InputTransformer).toBeDefined();
    expect(xformTarget!.InputTransformer!.InputPathsMap).toHaveProperty('src');
    expect(xformTarget!.InputTransformer!.InputTemplate).toContain('<src>');
  });

  it('should transform event payload before delivery', async () => {
    // Drain any prior messages
    await sqs.send(new ReceiveMessageCommand({
      QueueUrl: transformerQueueUrl, MaxNumberOfMessages: 10,
    }));

    await eb.send(new PutEventsCommand({
      Entries: [{
        Source: 'com.myapp',
        DetailType: 'OrderShipped',
        Detail: JSON.stringify({ orderId: '456' }),
      }],
    }));

    const msg = await sqs.send(new ReceiveMessageCommand({
      QueueUrl: transformerQueueUrl,
      MaxNumberOfMessages: 1,
      WaitTimeSeconds: 2,
    }));
    expect(msg.Messages).toHaveLength(1);
    const body = msg.Messages![0].Body!;
    expect(body).toContain('com.myapp');
    expect(body).toContain('OrderShipped');
    expect(body).not.toContain('orderId');
  });

  // ──────────────────────────── Tags ────────────────────────────

  it('should list tags for a rule resource', async () => {
    const response = await eb.send(new ListTagsForResourceCommand({ ResourceARN: ruleArn }));
    expect(response.Tags).toBeDefined();
  });

  // ──────────────────────────── Cleanup ────────────────────────────

  it('should remove targets', async () => {
    const response = await eb.send(new RemoveTargetsCommand({
      Rule: ruleName,
      Ids: ['sqs-target', 'transformer-target'],
    }));
    expect(response.FailedEntryCount).toBe(0);

    const list = await eb.send(new ListTargetsByRuleCommand({ Rule: ruleName }));
    expect(list.Targets ?? []).toHaveLength(0);
  });

  it('should delete the rule', async () => {
    await eb.send(new DeleteRuleCommand({ Name: ruleName }));
    await expect(eb.send(new DescribeRuleCommand({ Name: ruleName })))
      .rejects.toThrow();
  });

  it('should delete the event bus', async () => {
    await eb.send(new DeleteEventBusCommand({ Name: busName }));
    const list = await eb.send(new ListEventBusesCommand({}));
    expect(list.EventBuses!.map(b => b.Name)).not.toContain(busName);
  });
});

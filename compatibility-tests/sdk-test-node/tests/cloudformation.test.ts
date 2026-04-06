/**
 * CloudFormation naming integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  CloudFormationClient,
  CreateStackCommand,
  DescribeStacksCommand,
  DescribeStackResourcesCommand,
  DeleteStackCommand,
} from '@aws-sdk/client-cloudformation';
import { makeClient, uniqueName, sleep } from './setup';

describe('CloudFormation Naming', () => {
  let cfn: CloudFormationClient;
  let autoStackName: string;
  let explicitStackName: string;
  const token = Date.now().toString(36);

  beforeAll(() => {
    cfn = makeClient(CloudFormationClient);
    autoStackName = `cfn-auto-naming-${token}`;
    explicitStackName = `cfn-explicit-naming-${token}`;
  });

  afterAll(async () => {
    for (const stackName of [autoStackName, explicitStackName]) {
      try {
        await cfn.send(new DeleteStackCommand({ StackName: stackName }));
      } catch {
        // ignore
      }
    }
  });

  async function waitForStackTerminalState(stackName: string, expectedSuccess = true) {
    const successStates = new Set(['CREATE_COMPLETE', 'UPDATE_COMPLETE']);
    const failureStates = new Set([
      'CREATE_FAILED',
      'ROLLBACK_IN_PROGRESS',
      'ROLLBACK_FAILED',
      'ROLLBACK_COMPLETE',
      'DELETE_FAILED',
      'DELETE_COMPLETE',
    ]);

    for (let i = 0; i < 40; i++) {
      const resp = await cfn.send(new DescribeStacksCommand({ StackName: stackName }));
      const status = resp.Stacks?.[0]?.StackStatus;
      if (successStates.has(status!)) {
        return { ok: expectedSuccess, status };
      }
      if (failureStates.has(status!)) {
        return { ok: !expectedSuccess, status };
      }
      await sleep(1000);
    }
    return { ok: false, status: 'TIMEOUT' };
  }

  async function getResources(stackName: string) {
    const resp = await cfn.send(new DescribeStackResourcesCommand({ StackName: stackName }));
    return resp.StackResources || [];
  }

  function physicalId(resources: { LogicalResourceId?: string; PhysicalResourceId?: string }[], logicalId: string) {
    return resources.find((r) => r.LogicalResourceId === logicalId)?.PhysicalResourceId;
  }

  describe('Auto-generated names', () => {
    it('should create stack with auto-generated names', async () => {
      const template = {
        Resources: {
          AutoBucket: { Type: 'AWS::S3::Bucket' },
          AutoQueue: { Type: 'AWS::SQS::Queue' },
          AutoTopic: { Type: 'AWS::SNS::Topic' },
          AutoParameter: {
            Type: 'AWS::SSM::Parameter',
            Properties: { Type: 'String', Value: 'v1' },
          },
          CrossRefQueue: {
            Type: 'AWS::SQS::Queue',
            Properties: { QueueName: { 'Fn::Sub': '${AutoBucket}-cross' } },
          },
        },
      };

      await cfn.send(
        new CreateStackCommand({
          StackName: autoStackName,
          TemplateBody: JSON.stringify(template),
        })
      );
    });

    it('should reach CREATE_COMPLETE', async () => {
      const result = await waitForStackTerminalState(autoStackName, true);
      expect(result.ok).toBe(true);
    });

    it('should have valid auto-generated resource names', async () => {
      const resources = await getResources(autoStackName);
      expect(resources.length).toBeGreaterThan(0);

      const autoBucket = physicalId(resources, 'AutoBucket');
      const autoQueue = physicalId(resources, 'AutoQueue');
      const autoTopic = physicalId(resources, 'AutoTopic');
      const autoParam = physicalId(resources, 'AutoParameter');
      const crossQueue = physicalId(resources, 'CrossRefQueue');

      // S3 bucket constraints
      expect(autoBucket).toBeTruthy();
      expect(autoBucket!.length).toBeGreaterThanOrEqual(3);
      expect(autoBucket!.length).toBeLessThanOrEqual(63);
      expect(/^[a-z0-9.-]+$/.test(autoBucket!)).toBe(true);
      expect(autoBucket).toBe(autoBucket!.toLowerCase());

      // SQS queue constraints
      expect(autoQueue).toBeTruthy();
      const queueName = autoQueue!.includes('/') ? autoQueue!.slice(autoQueue!.lastIndexOf('/') + 1) : autoQueue!;
      expect(queueName.length).toBeGreaterThan(0);
      expect(queueName.length).toBeLessThanOrEqual(80);

      // SNS topic constraints
      expect(autoTopic).toBeTruthy();
      const topicName = autoTopic!.includes(':') ? autoTopic!.slice(autoTopic!.lastIndexOf(':') + 1) : autoTopic!;
      expect(topicName.length).toBeGreaterThan(0);
      expect(topicName.length).toBeLessThanOrEqual(256);

      // SSM parameter constraints
      expect(autoParam).toBeTruthy();
      expect(autoParam!.length).toBeLessThanOrEqual(2048);

      // Cross-reference queue uses bucket name
      expect(crossQueue).toBeTruthy();
      const crossQueueName = crossQueue!.includes('/') ? crossQueue!.slice(crossQueue!.lastIndexOf('/') + 1) : crossQueue!;
      expect(crossQueueName.startsWith(`${autoBucket}-cross`)).toBe(true);
    });

    it('should delete auto stack', async () => {
      await cfn.send(new DeleteStackCommand({ StackName: autoStackName }));
    });
  });

  describe('Explicit names', () => {
    const explicitBucket = `cfn-explicit-${token}`;
    const explicitQueue = `cfn-explicit-${token}`;
    const explicitTopic = `cfn-explicit-${token}`;
    const explicitParam = `/cfn-explicit/${token}`;

    it('should create stack with explicit names', async () => {
      const template = {
        Resources: {
          NamedBucket: {
            Type: 'AWS::S3::Bucket',
            Properties: { BucketName: explicitBucket },
          },
          NamedQueue: {
            Type: 'AWS::SQS::Queue',
            Properties: { QueueName: explicitQueue },
          },
          NamedTopic: {
            Type: 'AWS::SNS::Topic',
            Properties: { TopicName: explicitTopic },
          },
          NamedParameter: {
            Type: 'AWS::SSM::Parameter',
            Properties: { Name: explicitParam, Type: 'String', Value: 'explicit' },
          },
        },
      };

      await cfn.send(
        new CreateStackCommand({
          StackName: explicitStackName,
          TemplateBody: JSON.stringify(template),
        })
      );
    });

    it('should reach CREATE_COMPLETE', async () => {
      const result = await waitForStackTerminalState(explicitStackName, true);
      expect(result.ok).toBe(true);
    });

    it('should respect explicit names', async () => {
      const resources = await getResources(explicitStackName);

      const actualBucket = physicalId(resources, 'NamedBucket');
      const actualQueue = physicalId(resources, 'NamedQueue');
      const actualTopic = physicalId(resources, 'NamedTopic');
      const actualParam = physicalId(resources, 'NamedParameter');

      expect(actualBucket).toBe(explicitBucket);
      expect(actualQueue).toContain(explicitQueue);
      expect(actualTopic).toContain(explicitTopic);
      expect(actualParam).toBe(explicitParam);
    });

    it('should delete explicit stack', async () => {
      await cfn.send(new DeleteStackCommand({ StackName: explicitStackName }));
    });
  });
});

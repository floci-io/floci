/**
 * Kinesis integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  KinesisClient,
  CreateStreamCommand,
  DescribeStreamCommand,
  PutRecordCommand,
  GetShardIteratorCommand,
  GetRecordsCommand,
  DeleteStreamCommand,
  ListStreamsCommand,
} from '@aws-sdk/client-kinesis';
import { makeClient, uniqueName } from './setup';

describe('Kinesis', () => {
  let kinesis: KinesisClient;
  let streamName: string;

  beforeAll(() => {
    kinesis = makeClient(KinesisClient);
    streamName = `test-stream-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await kinesis.send(new DeleteStreamCommand({ StreamName: streamName }));
    } catch {
      // ignore
    }
  });

  it('should create stream', async () => {
    await kinesis.send(new CreateStreamCommand({ StreamName: streamName, ShardCount: 1 }));
  });

  it('should describe stream', async () => {
    const response = await kinesis.send(new DescribeStreamCommand({ StreamName: streamName }));
    expect(['ACTIVE', 'CREATING']).toContain(response.StreamDescription?.StreamStatus);
  });

  it('should list streams', async () => {
    const response = await kinesis.send(new ListStreamsCommand({}));
    expect(response.StreamNames?.includes(streamName)).toBe(true);
  });

  it('should put record', async () => {
    const response = await kinesis.send(
      new PutRecordCommand({
        StreamName: streamName,
        Data: Buffer.from('kinesis-test-record'),
        PartitionKey: 'pk1',
      })
    );
    expect(response.SequenceNumber).toBeTruthy();
  });

  it('should get records', async () => {
    const descr = await kinesis.send(new DescribeStreamCommand({ StreamName: streamName }));
    const shardId = descr.StreamDescription!.Shards![0].ShardId!;

    const iterResult = await kinesis.send(
      new GetShardIteratorCommand({
        StreamName: streamName,
        ShardId: shardId,
        ShardIteratorType: 'TRIM_HORIZON',
      })
    );

    const recordsResult = await kinesis.send(
      new GetRecordsCommand({
        ShardIterator: iterResult.ShardIterator!,
        Limit: 10,
      })
    );
    expect(recordsResult.Records?.length).toBeGreaterThan(0);
    const data = Buffer.from(recordsResult.Records![0].Data!).toString();
    expect(data).toBe('kinesis-test-record');
  });

  it('should delete stream', async () => {
    await kinesis.send(new DeleteStreamCommand({ StreamName: streamName }));
    streamName = '';
  });
});

/**
 * DynamoDB integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  DynamoDBClient,
  CreateTableCommand,
  PutItemCommand,
  GetItemCommand,
  DeleteItemCommand,
  ScanCommand,
  QueryCommand,
  UpdateItemCommand,
  DeleteTableCommand,
  ListTablesCommand,
  DescribeTableCommand,
} from '@aws-sdk/client-dynamodb';
import { makeClient, uniqueName } from './setup';

describe('DynamoDB', () => {
  let dynamo: DynamoDBClient;
  let tableName: string;

  beforeAll(() => {
    dynamo = makeClient(DynamoDBClient);
    tableName = `test-table-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await dynamo.send(new DeleteTableCommand({ TableName: tableName }));
    } catch {
      // ignore
    }
  });

  it('should create table', async () => {
    await dynamo.send(
      new CreateTableCommand({
        TableName: tableName,
        KeySchema: [
          { AttributeName: 'pk', KeyType: 'HASH' },
          { AttributeName: 'sk', KeyType: 'RANGE' },
        ],
        AttributeDefinitions: [
          { AttributeName: 'pk', AttributeType: 'S' },
          { AttributeName: 'sk', AttributeType: 'S' },
        ],
        BillingMode: 'PAY_PER_REQUEST',
      })
    );
  });

  it('should describe table', async () => {
    const response = await dynamo.send(new DescribeTableCommand({ TableName: tableName }));
    expect(response.Table?.TableStatus).toBe('ACTIVE');
  });

  it('should list tables', async () => {
    const response = await dynamo.send(new ListTablesCommand({}));
    expect(response.TableNames?.includes(tableName)).toBe(true);
  });

  it('should put item', async () => {
    await dynamo.send(
      new PutItemCommand({
        TableName: tableName,
        Item: {
          pk: { S: 'user#1' },
          sk: { S: 'profile' },
          name: { S: 'Alice' },
          age: { N: '30' },
        },
      })
    );
  });

  it('should get item', async () => {
    const response = await dynamo.send(
      new GetItemCommand({
        TableName: tableName,
        Key: { pk: { S: 'user#1' }, sk: { S: 'profile' } },
      })
    );
    expect(response.Item?.name?.S).toBe('Alice');
  });

  it('should update item', async () => {
    await dynamo.send(
      new UpdateItemCommand({
        TableName: tableName,
        Key: { pk: { S: 'user#1' }, sk: { S: 'profile' } },
        UpdateExpression: 'SET #n = :v',
        ExpressionAttributeNames: { '#n': 'name' },
        ExpressionAttributeValues: { ':v': { S: 'Bob' } },
      })
    );

    const response = await dynamo.send(
      new GetItemCommand({
        TableName: tableName,
        Key: { pk: { S: 'user#1' }, sk: { S: 'profile' } },
      })
    );
    expect(response.Item?.name?.S).toBe('Bob');
  });

  it('should put second item', async () => {
    await dynamo.send(
      new PutItemCommand({
        TableName: tableName,
        Item: {
          pk: { S: 'user#2' },
          sk: { S: 'profile' },
          name: { S: 'Carol' },
          age: { N: '25' },
        },
      })
    );
  });

  it('should scan table', async () => {
    const response = await dynamo.send(new ScanCommand({ TableName: tableName }));
    expect(response.Count).toBeGreaterThanOrEqual(2);
  });

  it('should query table', async () => {
    const response = await dynamo.send(
      new QueryCommand({
        TableName: tableName,
        KeyConditionExpression: 'pk = :pk',
        ExpressionAttributeValues: { ':pk': { S: 'user#1' } },
      })
    );
    expect(response.Count).toBeGreaterThanOrEqual(1);
  });

  it('should delete items', async () => {
    await dynamo.send(
      new DeleteItemCommand({
        TableName: tableName,
        Key: { pk: { S: 'user#1' }, sk: { S: 'profile' } },
      })
    );
    await dynamo.send(
      new DeleteItemCommand({
        TableName: tableName,
        Key: { pk: { S: 'user#2' }, sk: { S: 'profile' } },
      })
    );
  });

  it('should delete table', async () => {
    await dynamo.send(new DeleteTableCommand({ TableName: tableName }));
    tableName = '';
  });
});

describe('DynamoDB GSI/LSI', () => {
  let dynamo: DynamoDBClient;
  let tableName: string;

  beforeAll(() => {
    dynamo = makeClient(DynamoDBClient);
    tableName = `test-gsi-table-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await dynamo.send(new DeleteTableCommand({ TableName: tableName }));
    } catch {
      // ignore
    }
  });

  it('should create table with GSI and LSI', async () => {
    await dynamo.send(
      new CreateTableCommand({
        TableName: tableName,
        KeySchema: [
          { AttributeName: 'pk', KeyType: 'HASH' },
          { AttributeName: 'sk', KeyType: 'RANGE' },
        ],
        AttributeDefinitions: [
          { AttributeName: 'pk', AttributeType: 'S' },
          { AttributeName: 'sk', AttributeType: 'S' },
          { AttributeName: 'gsiPk', AttributeType: 'S' },
          { AttributeName: 'lsiSk', AttributeType: 'S' },
        ],
        GlobalSecondaryIndexes: [
          {
            IndexName: 'gsi-1',
            KeySchema: [
              { AttributeName: 'gsiPk', KeyType: 'HASH' },
              { AttributeName: 'sk', KeyType: 'RANGE' },
            ],
            Projection: { ProjectionType: 'ALL' },
            ProvisionedThroughput: { ReadCapacityUnits: 5, WriteCapacityUnits: 5 },
          },
        ],
        LocalSecondaryIndexes: [
          {
            IndexName: 'lsi-1',
            KeySchema: [
              { AttributeName: 'pk', KeyType: 'HASH' },
              { AttributeName: 'lsiSk', KeyType: 'RANGE' },
            ],
            Projection: { ProjectionType: 'KEYS_ONLY' },
          },
        ],
        ProvisionedThroughput: { ReadCapacityUnits: 5, WriteCapacityUnits: 5 },
      })
    );
  });

  it('should verify indexes via DescribeTable', async () => {
    const desc = await dynamo.send(new DescribeTableCommand({ TableName: tableName }));
    const gsis = desc.Table?.GlobalSecondaryIndexes || [];
    const lsis = desc.Table?.LocalSecondaryIndexes || [];

    expect(gsis.length).toBe(1);
    expect(gsis[0]?.IndexName).toBe('gsi-1');
    expect(gsis[0]?.Projection?.ProjectionType).toBe('ALL');
    expect(lsis.length).toBe(1);
    expect(lsis[0]?.IndexName).toBe('lsi-1');
    expect(lsis[0]?.Projection?.ProjectionType).toBe('KEYS_ONLY');
  });

  it('should put items with GSI attributes', async () => {
    await dynamo.send(
      new PutItemCommand({
        TableName: tableName,
        Item: {
          pk: { S: 'item-1' },
          sk: { S: 'rev-1' },
          gsiPk: { S: 'group-A' },
          lsiSk: { S: '2024-01-01' },
        },
      })
    );
    await dynamo.send(
      new PutItemCommand({
        TableName: tableName,
        Item: {
          pk: { S: 'item-2' },
          sk: { S: 'rev-1' },
          gsiPk: { S: 'group-A' },
          lsiSk: { S: '2024-01-02' },
        },
      })
    );
    await dynamo.send(
      new PutItemCommand({
        TableName: tableName,
        Item: { pk: { S: 'item-3' }, sk: { S: 'rev-1' }, data: { S: 'no-gsi-attrs' } },
      })
    );
  });

  it('should query GSI', async () => {
    const resp = await dynamo.send(
      new QueryCommand({
        TableName: tableName,
        IndexName: 'gsi-1',
        KeyConditionExpression: 'gsiPk = :gpk',
        ExpressionAttributeValues: { ':gpk': { S: 'group-A' } },
      })
    );
    expect(resp.Count).toBe(2);
    const pks = new Set(resp.Items?.map((i) => i.pk.S));
    expect(pks.has('item-3')).toBe(false);
  });

  it('should query LSI', async () => {
    const resp = await dynamo.send(
      new QueryCommand({
        TableName: tableName,
        IndexName: 'lsi-1',
        KeyConditionExpression: 'pk = :pk AND lsiSk > :d',
        ExpressionAttributeValues: { ':pk': { S: 'item-1' }, ':d': { S: '2024-01-00' } },
      })
    );
    expect(resp.Count).toBe(1);
  });

  it('should delete table', async () => {
    await dynamo.send(new DeleteTableCommand({ TableName: tableName }));
    tableName = '';
  });
});

/**
 * DynamoDB conformance change tests.
 *
 * Covers:
 *  - Phase 1: ListTables (alphabetical order, Limit pagination, Limit validation)
 *             + ValidationException rename for invalid table names
 *  - Phase 2: ProjectionExpression on GetItem, Query, Scan, BatchGetItem
 *  - Phase 3: Batch limits (BatchWrite >25, BatchGet >100), empty PK rejection,
 *             TransactWriteItems duplicate key + idempotency token,
 *             ReturnItemCollectionMetrics=SIZE on LSI table
 *  - Phase 4: Select=COUNT on Query/Scan, parallel scan, attribute_type() function,
 *             parenthesized key condition, missing key condition validation
 *  - Phase 7: TagResource invalid ARN, ListTagsOfResource non-existent ARN
 *  - Phase 8: ReturnValuesOnConditionCheckFailure on PutItem/UpdateItem
 *  - Phase 9: Reserved word rejection in expressions; alias passes
 *  - Phase 10: AttributesToGet, AttributeUpdates, QueryFilter, ScanFilter (legacy API)
 *  - Phase 11: Enum validation fires before table lookup
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  DynamoDBClient,
  BatchGetItemCommand,
  BatchWriteItemCommand,
  CreateTableCommand,
  DeleteItemCommand,
  DeleteTableCommand,
  GetItemCommand,
  ListTablesCommand,
  ListTagsOfResourceCommand,
  PutItemCommand,
  QueryCommand,
  ScanCommand,
  TagResourceCommand,
  TransactWriteItemsCommand,
  UpdateItemCommand,
} from '@aws-sdk/client-dynamodb';
import { makeClient, uniqueName } from './setup';

// ─── helpers ────────────────────────────────────────────────────────────────

function s(v: string) {
  return { S: v };
}
function n(v: number) {
  return { N: String(v) };
}

async function expectError(promise: Promise<unknown>, expectedName: string): Promise<void> {
  let caught: unknown;
  try {
    await promise;
  } catch (e) {
    caught = e;
  }
  expect(caught, `expected ${expectedName} to be thrown`).toBeDefined();
  expect((caught as Error).name).toBe(expectedName);
}

// ─── Phase 1: ListTables ────────────────────────────────────────────────────

describe('DynamoDB — Phase 1: ListTables', () => {
  let ddb: DynamoDBClient;
  const tables: string[] = [];

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    // Create three tables with deterministic alphabetical names
    for (const suffix of ['aaa', 'bbb', 'ccc']) {
      const name = `lt-${suffix}-${uniqueName()}`;
      tables.push(name);
      await ddb.send(new CreateTableCommand({
        TableName: name,
        KeySchema: [{ AttributeName: 'pk', KeyType: 'HASH' }],
        AttributeDefinitions: [{ AttributeName: 'pk', AttributeType: 'S' }],
        BillingMode: 'PAY_PER_REQUEST',
      }));
    }
    tables.sort(); // ensure our expectation baseline is sorted
  });

  afterAll(async () => {
    for (const t of tables) {
      try { await ddb.send(new DeleteTableCommand({ TableName: t })); } catch { /* ignore */ }
    }
  });

  it('returns tables in alphabetical order', async () => {
    const resp = await ddb.send(new ListTablesCommand({}));
    const names = resp.TableNames ?? [];
    const sorted = [...names].sort();
    expect(names).toEqual(sorted);
  });

  it('respects Limit and returns LastEvaluatedTableName', async () => {
    const first = await ddb.send(new ListTablesCommand({ Limit: 1 }));
    expect(first.TableNames).toHaveLength(1);
    expect(first.LastEvaluatedTableName).toBeDefined();

    const second = await ddb.send(new ListTablesCommand({
      Limit: 1,
      ExclusiveStartTableName: first.LastEvaluatedTableName,
    }));
    expect(second.TableNames).toHaveLength(1);
    expect(second.TableNames![0]).not.toBe(first.TableNames![0]);
  });

  it('rejects Limit=0 with ValidationException', async () => {
    await expectError(ddb.send(new ListTablesCommand({ Limit: 0 })), 'ValidationException');
  });

  it('rejects Limit=101 with ValidationException', async () => {
    await expectError(ddb.send(new ListTablesCommand({ Limit: 101 })), 'ValidationException');
  });
});

// ─── Phase 1: ValidationException for invalid table names ───────────────────

describe('DynamoDB — Phase 1: ValidationException rename', () => {
  let ddb: DynamoDBClient;

  beforeAll(() => { ddb = makeClient(DynamoDBClient); });

  it('rejects a 2-char table name with ValidationException', async () => {
    await expectError(
      ddb.send(new PutItemCommand({ TableName: 'ab', Item: { pk: s('x') } })),
      'ValidationException',
    );
  });

  it('rejects empty table name with ValidationException', async () => {
    await expectError(
      ddb.send(new GetItemCommand({ TableName: '', Key: { pk: s('x') } })),
      'ValidationException',
    );
  });
});

// ─── Phase 2: ProjectionExpression ──────────────────────────────────────────

describe('DynamoDB — Phase 2: ProjectionExpression', () => {
  let ddb: DynamoDBClient;
  const table = `proj-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
    for (let i = 0; i < 3; i++) {
      await ddb.send(new PutItemCommand({
        TableName: table,
        Item: { pk: s('p1'), sk: s(`s${i}`), name: s(`Item-${i}`), count: n(i * 10), status: s('active') },
      }));
    }
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
  });

  it('GetItem: returns only projected attributes', async () => {
    const resp = await ddb.send(new GetItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s0') },
      ProjectionExpression: '#n',
      ExpressionAttributeNames: { '#n': 'name' },
    }));
    expect(resp.Item).toHaveProperty('name');
    expect(resp.Item).not.toHaveProperty('count');
    expect(resp.Item).not.toHaveProperty('status');
  });

  it('Query: returns only projected attributes', async () => {
    const resp = await ddb.send(new QueryCommand({
      TableName: table,
      KeyConditionExpression: 'pk = :pk',
      ExpressionAttributeValues: { ':pk': s('p1') },
      ProjectionExpression: 'sk, #n',
      ExpressionAttributeNames: { '#n': 'name' },
    }));
    expect(resp.Items!.length).toBeGreaterThan(0);
    for (const item of resp.Items!) {
      expect(item).toHaveProperty('sk');
      expect(item).toHaveProperty('name');
      expect(item).not.toHaveProperty('count');
    }
  });

  it('Scan: returns only projected attributes', async () => {
    const resp = await ddb.send(new ScanCommand({
      TableName: table,
      FilterExpression: 'pk = :pk',
      ExpressionAttributeValues: { ':pk': s('p1') },
      ProjectionExpression: 'pk, sk',
    }));
    expect(resp.Items!.length).toBeGreaterThan(0);
    for (const item of resp.Items!) {
      expect(item).toHaveProperty('pk');
      expect(item).toHaveProperty('sk');
      expect(item).not.toHaveProperty('name');
    }
  });

  it('BatchGetItem: returns only projected attributes', async () => {
    const resp = await ddb.send(new BatchGetItemCommand({
      RequestItems: {
        [table]: {
          Keys: [{ pk: s('p1'), sk: s('s0') }],
          ProjectionExpression: '#n',
          ExpressionAttributeNames: { '#n': 'name' },
        },
      },
    }));
    const items = resp.Responses?.[table] ?? [];
    expect(items.length).toBeGreaterThan(0);
    expect(items[0]).toHaveProperty('name');
    expect(items[0]).not.toHaveProperty('count');
  });

  it('rejects AttributesToGet combined with ProjectionExpression', async () => {
    await expectError(
      ddb.send(new GetItemCommand({
        TableName: table,
        Key: { pk: s('p1'), sk: s('s0') },
        AttributesToGet: ['name'],
        ProjectionExpression: 'name',
      })),
      'ValidationException',
    );
  });
});

// ─── Phase 3: Validation limits ─────────────────────────────────────────────

describe('DynamoDB — Phase 3: Batch limits & key validation', () => {
  let ddb: DynamoDBClient;
  const table = `val-${uniqueName()}`;
  const lsiTable = `val-lsi-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
    await ddb.send(new CreateTableCommand({
      TableName: lsiTable,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
        { AttributeName: 'lsi_sk', AttributeType: 'S' },
      ],
      LocalSecondaryIndexes: [{
        IndexName: 'lsi-by-lsi-sk',
        KeySchema: [
          { AttributeName: 'pk', KeyType: 'HASH' },
          { AttributeName: 'lsi_sk', KeyType: 'RANGE' },
        ],
        Projection: { ProjectionType: 'ALL' },
      }],
      BillingMode: 'PAY_PER_REQUEST',
    }));
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
    try { await ddb.send(new DeleteTableCommand({ TableName: lsiTable })); } catch { /* ignore */ }
  });

  it('BatchWriteItem rejects more than 25 items', async () => {
    const writes = Array.from({ length: 26 }, (_, i) => ({
      PutRequest: { Item: { pk: s(`excess-${i}`), sk: s('s') } },
    }));
    await expectError(
      ddb.send(new BatchWriteItemCommand({ RequestItems: { [table]: writes } })),
      'ValidationException',
    );
  });

  it('BatchGetItem rejects more than 100 keys', async () => {
    const keys = Array.from({ length: 101 }, (_, i) => ({ pk: s(`k${i}`), sk: s('s') }));
    await expectError(
      ddb.send(new BatchGetItemCommand({ RequestItems: { [table]: { Keys: keys } } })),
      'ValidationException',
    );
  });

  it('PutItem rejects empty string as PK', async () => {
    await expectError(
      ddb.send(new PutItemCommand({ TableName: table, Item: { pk: s(''), sk: s('s') } })),
      'ValidationException',
    );
  });

  it('TransactWriteItems rejects duplicate keys', async () => {
    await expectError(
      ddb.send(new TransactWriteItemsCommand({
        TransactItems: [
          { Put: { TableName: table, Item: { pk: s('dup'), sk: s('s') } } },
          { Put: { TableName: table, Item: { pk: s('dup'), sk: s('s') } } },
        ],
      })),
      'ValidationException',
    );
  });

  it('TransactWriteItems idempotency token conflict throws IdempotentParameterMismatchException', async () => {
    const token = uniqueName('tok');
    // First call succeeds
    await ddb.send(new TransactWriteItemsCommand({
      ClientRequestToken: token,
      TransactItems: [{ Put: { TableName: table, Item: { pk: s('idem-node'), sk: s('s') } } }],
    }));
    // Same token, different payload
    await expectError(
      ddb.send(new TransactWriteItemsCommand({
        ClientRequestToken: token,
        TransactItems: [{ Put: { TableName: table, Item: { pk: s('idem-other'), sk: s('s') } } }],
      })),
      'IdempotentParameterMismatchException',
    );
  });

  it('PutItem with ReturnItemCollectionMetrics=SIZE returns metrics on LSI table', async () => {
    const resp = await ddb.send(new PutItemCommand({
      TableName: lsiTable,
      Item: { pk: s('m1'), sk: s('sk-a'), lsi_sk: s('l1') },
      ReturnItemCollectionMetrics: 'SIZE',
    }));
    expect(resp.ItemCollectionMetrics).toBeDefined();
    expect(resp.ItemCollectionMetrics!.SizeEstimateRangeGB).toHaveLength(2);
  });
});

// ─── Phase 4: Query/Scan behaviour ──────────────────────────────────────────

describe('DynamoDB — Phase 4: Query/Scan behaviour', () => {
  let ddb: DynamoDBClient;
  const table = `qscan-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
    for (let i = 0; i < 5; i++) {
      await ddb.send(new PutItemCommand({
        TableName: table,
        Item: { pk: s('p1'), sk: s(`s${i}`), name: s(`Item-${i}`), count: n(i) },
      }));
    }
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
  });

  it('Query Select=COUNT returns Count without Items', async () => {
    const resp = await ddb.send(new QueryCommand({
      TableName: table,
      KeyConditionExpression: 'pk = :pk',
      ExpressionAttributeValues: { ':pk': s('p1') },
      Select: 'COUNT',
    }));
    expect(resp.Count).toBe(5);
    expect(resp.Items).toBeUndefined();
  });

  it('Scan Select=COUNT returns Count without Items', async () => {
    const resp = await ddb.send(new ScanCommand({
      TableName: table,
      FilterExpression: 'pk = :pk',
      ExpressionAttributeValues: { ':pk': s('p1') },
      Select: 'COUNT',
    }));
    expect(resp.Count).toBeGreaterThan(0);
    expect(resp.Items).toBeUndefined();
  });

  it('parallel scan partitions with no overlap and full coverage', async () => {
    const totalSegments = 2;
    const [seg0, seg1] = await Promise.all([
      ddb.send(new ScanCommand({ TableName: table, Segment: 0, TotalSegments: totalSegments })),
      ddb.send(new ScanCommand({ TableName: table, Segment: 1, TotalSegments: totalSegments })),
    ]);

    const keys0 = new Set((seg0.Items ?? []).map(i => i.sk.S!));
    const keys1 = new Set((seg1.Items ?? []).map(i => i.sk.S!));

    // No overlap
    for (const k of keys0) expect(keys1.has(k)).toBe(false);
    // Full coverage
    expect(keys0.size + keys1.size).toBe(5);
  });

  it('parallel scan requires both Segment and TotalSegments', async () => {
    await expectError(
      ddb.send(new ScanCommand({ TableName: table, Segment: 0 })),
      'ValidationException',
    );
    await expectError(
      ddb.send(new ScanCommand({ TableName: table, TotalSegments: 2 })),
      'ValidationException',
    );
  });

  it('attribute_type() function in FilterExpression', async () => {
    const resp = await ddb.send(new QueryCommand({
      TableName: table,
      KeyConditionExpression: 'pk = :pk',
      FilterExpression: 'attribute_type(#n, :t)',
      ExpressionAttributeValues: { ':pk': s('p1'), ':t': s('S') },
      ExpressionAttributeNames: { '#n': 'name' },
    }));
    expect(resp.Count).toBe(5);
  });

  it('parenthesized key condition expression works', async () => {
    const resp = await ddb.send(new QueryCommand({
      TableName: table,
      KeyConditionExpression: '(pk = :pk AND sk = :sk)',
      ExpressionAttributeValues: { ':pk': s('p1'), ':sk': s('s0') },
    }));
    expect(resp.Count).toBe(1);
    expect(resp.Items![0].sk.S).toBe('s0');
  });

  it('Query without KeyConditionExpression throws ValidationException', async () => {
    await expectError(
      ddb.send(new QueryCommand({ TableName: table })),
      'ValidationException',
    );
  });
});

// ─── Phase 7: Tags API errors ────────────────────────────────────────────────

describe('DynamoDB — Phase 7: Tags API errors', () => {
  let ddb: DynamoDBClient;

  beforeAll(() => { ddb = makeClient(DynamoDBClient); });

  it('TagResource with invalid ARN throws ValidationException', async () => {
    await expectError(
      ddb.send(new TagResourceCommand({
        ResourceArn: 'not-an-arn',
        Tags: [{ Key: 'k', Value: 'v' }],
      })),
      'ValidationException',
    );
  });

  it('ListTagsOfResource with valid-format non-existent ARN throws AccessDeniedException', async () => {
    await expectError(
      ddb.send(new ListTagsOfResourceCommand({
        ResourceArn: 'arn:aws:dynamodb:us-east-1:000000000000:table/does-not-exist',
      })),
      'AccessDeniedException',
    );
  });
});

// ─── Phase 8: ReturnValuesOnConditionCheckFailure ────────────────────────────

describe('DynamoDB — Phase 8: ReturnValuesOnConditionCheckFailure', () => {
  let ddb: DynamoDBClient;
  const table = `cond-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
    await ddb.send(new PutItemCommand({
      TableName: table,
      Item: { pk: s('existing'), sk: s('s'), val: s('old') },
    }));
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
  });

  it('PutItem condition failure throws ConditionalCheckFailedException', async () => {
    await expectError(
      ddb.send(new PutItemCommand({
        TableName: table,
        Item: { pk: s('existing'), sk: s('s'), val: s('new') },
        ConditionExpression: 'attribute_not_exists(pk)',
        ReturnValuesOnConditionCheckFailure: 'ALL_OLD',
      })),
      'ConditionalCheckFailedException',
    );
  });

  it('UpdateItem condition failure throws ConditionalCheckFailedException', async () => {
    await expectError(
      ddb.send(new UpdateItemCommand({
        TableName: table,
        Key: { pk: s('existing'), sk: s('s') },
        UpdateExpression: 'SET val = :new',
        ConditionExpression: 'val = :expected',
        ExpressionAttributeValues: { ':new': s('new'), ':expected': s('wrong') },
        ReturnValuesOnConditionCheckFailure: 'ALL_OLD',
      })),
      'ConditionalCheckFailedException',
    );
  });

  it('DeleteItem condition failure throws ConditionalCheckFailedException', async () => {
    await expectError(
      ddb.send(new DeleteItemCommand({
        TableName: table,
        Key: { pk: s('existing'), sk: s('s') },
        ConditionExpression: 'val = :expected',
        ExpressionAttributeValues: { ':expected': s('wrong') },
        ReturnValuesOnConditionCheckFailure: 'ALL_OLD',
      })),
      'ConditionalCheckFailedException',
    );
  });
});

// ─── Phase 9: Reserved words ─────────────────────────────────────────────────

describe('DynamoDB — Phase 9: Reserved words', () => {
  let ddb: DynamoDBClient;
  const table = `rw-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
  });

  it('bare reserved word in ConditionExpression throws ValidationException', async () => {
    await expectError(
      ddb.send(new PutItemCommand({
        TableName: table,
        Item: { pk: s('rw1'), sk: s('s') },
        // 'status' is a DynamoDB reserved word
        ConditionExpression: 'attribute_not_exists(status)',
      })),
      'ValidationException',
    );
  });

  it('bare reserved word in FilterExpression throws ValidationException', async () => {
    await expectError(
      ddb.send(new ScanCommand({
        TableName: table,
        FilterExpression: 'name = :v',
        ExpressionAttributeValues: { ':v': s('x') },
        // 'name' is a reserved word used bare
      })),
      'ValidationException',
    );
  });

  it('reserved word aliased via ExpressionAttributeNames passes', async () => {
    // Using #st alias for the reserved word 'status'
    await ddb.send(new PutItemCommand({
      TableName: table,
      Item: { pk: s('rw-ok'), sk: s('s'), status: s('active') },
      ConditionExpression: 'attribute_not_exists(pk)',
      ExpressionAttributeNames: { '#st': 'status' },
    }));

    const resp = await ddb.send(new ScanCommand({
      TableName: table,
      FilterExpression: '#st = :v',
      ExpressionAttributeNames: { '#st': 'status' },
      ExpressionAttributeValues: { ':v': s('active') },
    }));
    expect(resp.Count).toBe(1);
  });
});

// ─── Phase 10: Legacy API ────────────────────────────────────────────────────

describe('DynamoDB — Phase 10: Legacy API', () => {
  let ddb: DynamoDBClient;
  const table = `legacy-${uniqueName()}`;

  beforeAll(async () => {
    ddb = makeClient(DynamoDBClient);
    await ddb.send(new CreateTableCommand({
      TableName: table,
      KeySchema: [
        { AttributeName: 'pk', KeyType: 'HASH' },
        { AttributeName: 'sk', KeyType: 'RANGE' },
      ],
      AttributeDefinitions: [
        { AttributeName: 'pk', AttributeType: 'S' },
        { AttributeName: 'sk', AttributeType: 'S' },
      ],
      BillingMode: 'PAY_PER_REQUEST',
    }));
    for (let i = 0; i < 3; i++) {
      await ddb.send(new PutItemCommand({
        TableName: table,
        Item: { pk: s('p1'), sk: s(`s${i}`), name: s(`Item-${i}`), color: s('red') },
      }));
    }
  });

  afterAll(async () => {
    try { await ddb.send(new DeleteTableCommand({ TableName: table })); } catch { /* ignore */ }
  });

  it('AttributesToGet returns only listed attributes (no auto-key-include)', async () => {
    const resp = await ddb.send(new GetItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s0') },
      AttributesToGet: ['name', 'color'],
    }));
    expect(resp.Item).toHaveProperty('name');
    expect(resp.Item).toHaveProperty('color');
    expect(resp.Item).not.toHaveProperty('pk');
    expect(resp.Item).not.toHaveProperty('sk');
  });

  it('AttributeUpdates (legacy) applies PUT action', async () => {
    await ddb.send(new UpdateItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s0') },
      AttributeUpdates: {
        color: { Value: s('blue'), Action: 'PUT' },
      },
    }));
    const resp = await ddb.send(new GetItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s0') },
    }));
    expect(resp.Item!.color.S).toBe('blue');
  });

  it('AttributeUpdates (legacy) applies DELETE action to remove attribute', async () => {
    await ddb.send(new UpdateItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s1') },
      AttributeUpdates: {
        color: { Action: 'DELETE' },
      },
    }));
    const resp = await ddb.send(new GetItemCommand({
      TableName: table,
      Key: { pk: s('p1'), sk: s('s1') },
    }));
    expect(resp.Item).not.toHaveProperty('color');
  });

  it('QueryFilter (legacy) filters query results', async () => {
    const resp = await ddb.send(new QueryCommand({
      TableName: table,
      KeyConditionExpression: 'pk = :pk',
      ExpressionAttributeValues: { ':pk': s('p1') },
      QueryFilter: {
        name: { ComparisonOperator: 'EQ', AttributeValueList: [s('Item-0')] },
      },
    }));
    expect(resp.Count).toBe(1);
    expect(resp.Items![0].name.S).toBe('Item-0');
  });

  it('ScanFilter (legacy) filters scan results', async () => {
    const resp = await ddb.send(new ScanCommand({
      TableName: table,
      ScanFilter: {
        name: { ComparisonOperator: 'EQ', AttributeValueList: [s('Item-2')] },
      },
    }));
    expect(resp.Count).toBe(1);
    expect(resp.Items![0].name.S).toBe('Item-2');
  });

  it('KeyConditions and KeyConditionExpression are mutually exclusive', async () => {
    await expectError(
      ddb.send(new QueryCommand({
        TableName: table,
        KeyConditionExpression: 'pk = :pk',
        ExpressionAttributeValues: { ':pk': s('p1') },
        KeyConditions: { pk: { ComparisonOperator: 'EQ', AttributeValueList: [s('p1')] } },
      })),
      'ValidationException',
    );
  });
});

// ─── Phase 11: Enum validation ordering ──────────────────────────────────────

describe('DynamoDB — Phase 11: Enum validation before table lookup', () => {
  let ddb: DynamoDBClient;

  beforeAll(() => { ddb = makeClient(DynamoDBClient); });

  it('invalid ReturnValues on PutItem throws ValidationException even for non-existent table', async () => {
    const err = await ddb.send(new PutItemCommand({
      TableName: 'nonexistent-table-xyz',
      Item: { pk: s('x') },
      ReturnValues: 'UPDATED_NEW', // invalid for PutItem
    })).catch(e => e);

    expect(err.name).toBe('ValidationException');
    // Must NOT be ResourceNotFoundException
    expect(err.name).not.toBe('ResourceNotFoundException');
  });

  it('invalid ReturnValues on DeleteItem throws ValidationException even for non-existent table', async () => {
    const err = await ddb.send(new DeleteItemCommand({
      TableName: 'nonexistent-table-xyz',
      Key: { pk: s('x'), sk: s('y') },
      ReturnValues: 'UPDATED_NEW', // invalid for DeleteItem
    })).catch(e => e);

    expect(err.name).toBe('ValidationException');
  });

  it('invalid ReturnConsumedCapacity throws ValidationException even for non-existent table', async () => {
    const err = await ddb.send(new GetItemCommand({
      TableName: 'nonexistent-table-xyz',
      Key: { pk: s('x'), sk: s('y') },
      // @ts-expect-error invalid enum value
      ReturnConsumedCapacity: 'INVALID_VALUE',
    })).catch(e => e);

    expect(err.name).toBe('ValidationException');
  });
});

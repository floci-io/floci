import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  ConflictException,
  CreateNamespaceCommand,
  CreateTableBucketCommand,
  CreateTableCommand,
  DeleteNamespaceCommand,
  DeleteTableBucketCommand,
  DeleteTableBucketPolicyCommand,
  DeleteTableCommand,
  DeleteTablePolicyCommand,
  GetNamespaceCommand,
  GetTableBucketCommand,
  GetTableBucketMaintenanceConfigurationCommand,
  GetTableBucketPolicyCommand,
  GetTableCommand,
  GetTableMaintenanceConfigurationCommand,
  GetTableMetadataLocationCommand,
  GetTablePolicyCommand,
  ListNamespacesCommand,
  ListTableBucketsCommand,
  ListTablesCommand,
  NotFoundException,
  PutTableBucketMaintenanceConfigurationCommand,
  PutTableBucketPolicyCommand,
  PutTableMaintenanceConfigurationCommand,
  PutTablePolicyCommand,
  RenameTableCommand,
  S3TablesClient,
  UpdateTableMetadataLocationCommand,
} from '@aws-sdk/client-s3tables';
import { makeClient, uniqueName } from './setup';

describe('S3 Tables lifecycle', () => {
  let client: S3TablesClient;
  let bucketArn: string;
  const bucketName = uniqueName('table-bucket');
  const namespace = 'analytics';
  let tableName = uniqueName('events');
  let versionToken: string;

  beforeAll(() => {
    client = makeClient(S3TablesClient);
  });

  afterAll(async () => {
    if (!bucketArn) return;
    await client.send(new DeleteTableCommand({ tableBucketARN: bucketArn, namespace, name: tableName })).catch(() => undefined);
    await client.send(new DeleteNamespaceCommand({ tableBucketARN: bucketArn, namespace })).catch(() => undefined);
    await client.send(new DeleteTableBucketCommand({ tableBucketARN: bucketArn })).catch(() => undefined);
  });

  it('creates a table bucket, namespace, and Iceberg table', async () => {
    const bucket = await client.send(new CreateTableBucketCommand({
      name: bucketName,
      tags: { suite: 'sdk-node' },
      encryptionConfiguration: { sseAlgorithm: 'AES256' },
      storageClassConfiguration: { storageClass: 'STANDARD' },
    }));
    bucketArn = bucket.arn!;
    expect(bucketArn).toContain(`bucket/${bucketName}`);

    const createdNamespace = await client.send(new CreateNamespaceCommand({
      tableBucketARN: bucketArn,
      namespace: [namespace],
    }));
    expect(createdNamespace.namespace).toEqual([namespace]);

    const table = await client.send(new CreateTableCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
      format: 'ICEBERG',
      metadata: { iceberg: { properties: { 'format-version': '2' } } },
      encryptionConfiguration: { sseAlgorithm: 'AES256' },
      storageClassConfiguration: { storageClass: 'STANDARD' },
      tags: { suite: 'sdk-node' },
    }));
    versionToken = table.versionToken!;
    expect(table.tableARN).toBe(`${bucketArn}/table/${tableName}`);
  });

  it('gets resources and applies list prefixes', async () => {
    const bucket = await client.send(new GetTableBucketCommand({ tableBucketARN: bucketArn }));
    expect(bucket.name).toBe(bucketName);

    const buckets = await client.send(new ListTableBucketsCommand({ prefix: 'table-bucket' }));
    expect(buckets.tableBuckets?.some((item) => item.name === bucketName)).toBe(true);

    const fetchedNamespace = await client.send(new GetNamespaceCommand({ tableBucketARN: bucketArn, namespace }));
    expect(fetchedNamespace.namespace).toEqual([namespace]);
    const namespaces = await client.send(new ListNamespacesCommand({ tableBucketARN: bucketArn, prefix: 'anal' }));
    expect(namespaces.namespaces?.some((item) => item.namespace?.includes(namespace))).toBe(true);

    const tables = await client.send(new ListTablesCommand({ tableBucketARN: bucketArn, namespace, prefix: 'events' }));
    expect(tables.tables?.some((table) => table.name === tableName)).toBe(true);
    const table = await client.send(new GetTableCommand({ tableBucketARN: bucketArn, namespace, name: tableName }));
    expect(table.tableARN).toBe(`${bucketArn}/table/${tableName}`);
  });

  it('round-trips policies and metadata version tokens', async () => {
    const policy = '{"Version":"2012-10-17","Statement":[]}';
    await client.send(new PutTableBucketPolicyCommand({ tableBucketARN: bucketArn, resourcePolicy: policy }));
    expect((await client.send(new GetTableBucketPolicyCommand({ tableBucketARN: bucketArn }))).resourcePolicy).toBe(policy);
    await client.send(new PutTablePolicyCommand({ tableBucketARN: bucketArn, namespace, name: tableName, resourcePolicy: policy }));
    expect((await client.send(new GetTablePolicyCommand({ tableBucketARN: bucketArn, namespace, name: tableName }))).resourcePolicy).toBe(policy);

    const updated = await client.send(new UpdateTableMetadataLocationCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
      metadataLocation: `s3://warehouse/${tableName}/v2.metadata.json`,
      versionToken,
    }));
    expect(updated.versionToken).not.toBe(versionToken);
    expect((await client.send(new GetTableMetadataLocationCommand({
      tableBucketARN: bucketArn, namespace, name: tableName,
    }))).metadataLocation).toBe(updated.metadataLocation);

    await expect(client.send(new UpdateTableMetadataLocationCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
      metadataLocation: `s3://warehouse/${tableName}/stale.metadata.json`,
      versionToken,
    }))).rejects.toBeInstanceOf(ConflictException);
    versionToken = updated.versionToken!;
  });

  it('round-trips typed maintenance configuration maps', async () => {
    await client.send(new PutTableBucketMaintenanceConfigurationCommand({
      tableBucketARN: bucketArn,
      type: 'icebergUnreferencedFileRemoval',
      value: { status: 'enabled' },
    }));
    const bucketMaintenance = await client.send(new GetTableBucketMaintenanceConfigurationCommand({
      tableBucketARN: bucketArn,
    }));
    expect(bucketMaintenance.configuration?.icebergUnreferencedFileRemoval?.status).toBe('enabled');

    await client.send(new PutTableMaintenanceConfigurationCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
      type: 'icebergCompaction',
      value: { status: 'enabled' },
    }));
    const tableMaintenance = await client.send(new GetTableMaintenanceConfigurationCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
    }));
    expect(tableMaintenance.configuration?.icebergCompaction?.status).toBe('enabled');
  });

  it('renames a table and rotates its version token', async () => {
    const originalToken = versionToken;
    const renamedTableName = `${tableName}-renamed`;
    await client.send(new RenameTableCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: tableName,
      newName: renamedTableName,
      versionToken: originalToken,
    }));

    const renamed = await client.send(new GetTableCommand({
      tableBucketARN: bucketArn,
      namespace,
      name: renamedTableName,
    }));
    expect(renamed.name).toBe(renamedTableName);
    expect(renamed.versionToken).not.toBe(originalToken);
    await expect(client.send(new GetTableCommand({ tableBucketARN: bucketArn, namespace, name: tableName })))
      .rejects.toBeInstanceOf(NotFoundException);
    tableName = renamedTableName;
    versionToken = renamed.versionToken!;
  });

  it('deletes policies and the resource hierarchy', async () => {
    await client.send(new DeleteTablePolicyCommand({ tableBucketARN: bucketArn, namespace, name: tableName }));
    await expect(client.send(new GetTablePolicyCommand({ tableBucketARN: bucketArn, namespace, name: tableName })))
      .rejects.toBeInstanceOf(NotFoundException);
    await client.send(new DeleteTableBucketPolicyCommand({ tableBucketARN: bucketArn }));
    await expect(client.send(new GetTableBucketPolicyCommand({ tableBucketARN: bucketArn })))
      .rejects.toBeInstanceOf(NotFoundException);

    await client.send(new DeleteTableCommand({ tableBucketARN: bucketArn, namespace, name: tableName }));
    await expect(client.send(new GetTableCommand({ tableBucketARN: bucketArn, namespace, name: tableName })))
      .rejects.toBeInstanceOf(NotFoundException);
    await client.send(new DeleteNamespaceCommand({ tableBucketARN: bucketArn, namespace }));
    await expect(client.send(new GetNamespaceCommand({ tableBucketARN: bucketArn, namespace })))
      .rejects.toBeInstanceOf(NotFoundException);
    await client.send(new DeleteTableBucketCommand({ tableBucketARN: bucketArn }));
    await expect(client.send(new GetTableBucketCommand({ tableBucketARN: bucketArn })))
      .rejects.toBeInstanceOf(NotFoundException);
  });
});

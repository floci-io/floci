/**
 * Neptune cluster and instance integration tests.
 */

import { describe, it, expect, afterEach } from 'vitest';
import {
  NeptuneClient,
  CreateDBClusterCommand,
  DescribeDBClustersCommand,
  ModifyDBClusterCommand,
  DeleteDBClusterCommand,
  CreateDBInstanceCommand,
  DescribeDBInstancesCommand,
  ModifyDBInstanceCommand,
  DeleteDBInstanceCommand,
} from '@aws-sdk/client-neptune';
import { makeClient, uniqueName } from './setup';

const neptune = makeClient(NeptuneClient);

async function deleteCluster(id: string) {
  try {
    await neptune.send(new DeleteDBClusterCommand({ DBClusterIdentifier: id, SkipFinalSnapshot: true }));
  } catch { /* ignore */ }
}

async function deleteInstance(id: string) {
  try {
    await neptune.send(new DeleteDBInstanceCommand({ DBInstanceIdentifier: id }));
  } catch { /* ignore */ }
}

describe('Neptune Clusters', () => {
  it('should create a cluster', async () => {
    const clusterId = `node-${uniqueName()}`;

    try {
      const response = await neptune.send(new CreateDBClusterCommand({
        DBClusterIdentifier: clusterId,
        Engine: 'neptune',
      }));
      const cluster = response.DBCluster!;
      expect(cluster.DBClusterIdentifier).toBe(clusterId);
      expect(cluster.Engine).toBe('neptune');
      expect(cluster.Status).toBe('available');
      expect(cluster.DBClusterArn).toMatch(/^arn:aws:neptune:/);
      expect(cluster.Port).toBeGreaterThan(0);
    } finally {
      await deleteCluster(clusterId);
    }
  });

  it('should fail on duplicate cluster creation', async () => {
    const clusterId = `node-${uniqueName()}`;

    await neptune.send(new CreateDBClusterCommand({
      DBClusterIdentifier: clusterId,
      Engine: 'neptune',
    }));

    try {
      await expect(
        neptune.send(new CreateDBClusterCommand({
          DBClusterIdentifier: clusterId,
          Engine: 'neptune',
        }))
      ).rejects.toMatchObject({
        name: 'DBClusterAlreadyExistsFault',
      });
    } finally {
      await deleteCluster(clusterId);
    }
  });

  it('should describe cluster by ID', async () => {
    const clusterId = `node-${uniqueName()}`;

    await neptune.send(new CreateDBClusterCommand({
      DBClusterIdentifier: clusterId,
      Engine: 'neptune',
    }));

    try {
      const response = await neptune.send(new DescribeDBClustersCommand({
        DBClusterIdentifier: clusterId,
      }));
      expect(response.DBClusters).toHaveLength(1);
      expect(response.DBClusters![0].DBClusterIdentifier).toBe(clusterId);
    } finally {
      await deleteCluster(clusterId);
    }
  });

  it('should modify cluster IAM auth', async () => {
    const clusterId = `node-${uniqueName()}`;

    await neptune.send(new CreateDBClusterCommand({
      DBClusterIdentifier: clusterId,
      Engine: 'neptune',
    }));

    try {
      const response = await neptune.send(new ModifyDBClusterCommand({
        DBClusterIdentifier: clusterId,
        EnableIAMDatabaseAuthentication: true,
      }));
      expect(response.DBCluster!.IAMDatabaseAuthenticationEnabled).toBe(true);
    } finally {
      await deleteCluster(clusterId);
    }
  });
});

describe('Neptune Instances', () => {
  let clusterId: string;

  afterEach(async () => {
    if (clusterId) {
      await deleteCluster(clusterId);
      clusterId = '';
    }
  });

  it('should create and describe an instance', async () => {
    clusterId = `node-cl-${uniqueName()}`;
    const instanceId = `node-inst-${uniqueName()}`;

    await neptune.send(new CreateDBClusterCommand({
      DBClusterIdentifier: clusterId,
      Engine: 'neptune',
    }));

    try {
      const createResp = await neptune.send(new CreateDBInstanceCommand({
        DBInstanceIdentifier: instanceId,
        DBClusterIdentifier: clusterId,
        DBInstanceClass: 'db.r5.large',
        Engine: 'neptune',
      }));
      const instance = createResp.DBInstance!;
      expect(instance.DBInstanceIdentifier).toBe(instanceId);
      expect(instance.DBClusterIdentifier).toBe(clusterId);
      expect(instance.DBInstanceStatus).toBe('available');

      const descResp = await neptune.send(new DescribeDBInstancesCommand({
        DBInstanceIdentifier: instanceId,
      }));
      expect(descResp.DBInstances).toHaveLength(1);
    } finally {
      await deleteInstance(instanceId);
    }
  });

  it('should modify instance class', async () => {
    clusterId = `node-cl-${uniqueName()}`;
    const instanceId = `node-inst-${uniqueName()}`;

    await neptune.send(new CreateDBClusterCommand({
      DBClusterIdentifier: clusterId,
      Engine: 'neptune',
    }));

    try {
      await neptune.send(new CreateDBInstanceCommand({
        DBInstanceIdentifier: instanceId,
        DBClusterIdentifier: clusterId,
        DBInstanceClass: 'db.r5.large',
        Engine: 'neptune',
      }));

      const response = await neptune.send(new ModifyDBInstanceCommand({
        DBInstanceIdentifier: instanceId,
        DBInstanceClass: 'db.r5.xlarge',
      }));
      expect(response.DBInstance!.DBInstanceClass).toBe('db.r5.xlarge');
    } finally {
      await deleteInstance(instanceId);
    }
  });
});

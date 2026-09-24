import { execFile } from 'node:child_process';
import { createConnection as createSocket } from 'node:net';
import { promisify } from 'node:util';
import mysql, { type ConnectionOptions, type RowDataPacket } from 'mysql2/promise';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import {
  CreateDBInstanceCommand,
  DeleteDBInstanceCommand,
  DescribeDBInstancesCommand,
  RDSClient,
  waitUntilDBInstanceAvailable,
} from '@aws-sdk/client-rds';
import { ENDPOINT, makeClient, uniqueName } from './setup';

const run = promisify(execFile);
const rds = makeClient(RDSClient);
const instanceId = uniqueName('node-mariadb');
const username = 'admin';
const password = 'secret123';
const database = 'app';

describe('RDS MySQL protocol shutdown', () => {
  let created = false;
  let options: ConnectionOptions;

  beforeAll(async () => {
    await rds.send(new CreateDBInstanceCommand({
      DBInstanceIdentifier: instanceId,
      DBInstanceClass: 'db.t3.micro',
      Engine: 'mariadb',
      EngineVersion: '11.4',
      MasterUsername: username,
      MasterUserPassword: password,
      DBName: database,
      AllocatedStorage: 5,
    }));
    created = true;
    await waitUntilDBInstanceAvailable(
      { client: rds, maxWaitTime: 120, minDelay: 1, maxDelay: 3 },
      { DBInstanceIdentifier: instanceId },
    );
    const response = await rds.send(new DescribeDBInstancesCommand({
      DBInstanceIdentifier: instanceId,
    }));
    const port = response.DBInstances![0].Endpoint!.Port!;
    const host = new URL(ENDPOINT).hostname;
    options = { host, port, user: username, password, database, connectTimeout: 5000 };
    // RDS metadata can become available before MariaDB accepts authenticated queries.
    await vi.waitFor(async () => {
      const socket = createSocket({ host, port });
      try {
        const connection = await mysql.createConnection({ ...options, stream: socket });
        const [rows] = await connection.query<RowDataPacket[]>('SELECT 1 AS ready');
        expect(rows[0].ready).toBe(1);
      } finally {
        socket.destroy();
      }
    }, { timeout: 60000, interval: 1000 });
  }, 240000);

  afterAll(async () => {
    try {
      if (created) {
        await rds.send(new DeleteDBInstanceCommand({
          DBInstanceIdentifier: instanceId,
          SkipFinalSnapshot: true,
        }));
      }
    } finally {
      rds.destroy();
    }
  }, 60000);

  it.each(['connection', 'pool'])('lets a mysql2 %s process exit after end()', async (kind) => {
    // end() resolves before the socket closes. A child must exit naturally to prove
    // that the proxy forwarded shutdown, rather than leaving the event loop alive.
    const { stdout } = await run(process.execPath, ['--input-type=module', '-e', `
      import assert from 'node:assert/strict';
      import mysql from 'mysql2/promise';

      const options = JSON.parse(process.argv[1]);
      const pooled = process.argv[2] === 'pool';
      const client = pooled ? mysql.createPool({ ...options, connectionLimit: 1 })
        : await mysql.createConnection(options);
      const connection = pooled ? await client.getConnection() : client;
      await connection.query('CREATE TEMPORARY TABLE shutdown_probe (value INT NOT NULL)');
      await connection.query('INSERT INTO shutdown_probe VALUES (1)');
      await connection.beginTransaction();
      await connection.query('INSERT INTO shutdown_probe VALUES (13)');
      await connection.rollback();
      const [rolledBack] = await connection.query('SELECT SUM(value) AS total FROM shutdown_probe');
      assert.equal(Number(rolledBack[0].total), 1);
      await connection.beginTransaction();
      await connection.query('INSERT INTO shutdown_probe VALUES (7)');
      await connection.commit();
      const [committed] = await connection.query('SELECT SUM(value) AS total FROM shutdown_probe');
      assert.equal(Number(committed[0].total), 8);
      if (pooled) {
        const id = connection.threadId;
        connection.release();
        const reused = await client.getConnection();
        assert.equal(reused.threadId, id);
        const [rows] = await reused.query('SELECT 13 + 29 AS answer');
        assert.equal(rows[0].answer, 42);
        reused.release();
      }
      await client.end();
      console.log('end() resolved');
    `, JSON.stringify(options), kind], { timeout: 30000, killSignal: 'SIGKILL' });

    expect(stdout.trim()).toBe('end() resolved');
  });
});

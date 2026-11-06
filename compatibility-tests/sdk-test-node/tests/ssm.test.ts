/**
 * SSM Parameter Store integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  SSMClient,
  PutParameterCommand,
  GetParameterCommand,
  DeleteParameterCommand,
  GetParametersByPathCommand,
  DescribeParametersCommand,
} from '@aws-sdk/client-ssm';
import { makeClient, uniqueName } from './setup';

describe('SSM', () => {
  let ssm: SSMClient;
  let paramPath: string;

  beforeAll(() => {
    ssm = makeClient(SSMClient);
    paramPath = `/floci/test/${uniqueName()}`;
  });

  afterAll(async () => {
    // Cleanup any remaining parameters
    try {
      await ssm.send(new DeleteParameterCommand({ Name: `${paramPath}/p1` }));
    } catch {
      // ignore
    }
    try {
      await ssm.send(new DeleteParameterCommand({ Name: `${paramPath}/secret` }));
    } catch {
      // ignore
    }
  });

  it('should put and get a String parameter', async () => {
    const name = `${paramPath}/p1`;
    await ssm.send(
      new PutParameterCommand({ Name: name, Value: 'hello', Type: 'String' })
    );

    const response = await ssm.send(new GetParameterCommand({ Name: name }));
    expect(response.Parameter?.Value).toBe('hello');
  });

  it('should put and get a SecureString parameter', async () => {
    const name = `${paramPath}/secret`;
    await ssm.send(
      new PutParameterCommand({ Name: name, Value: 's3cr3t', Type: 'SecureString' })
    );

    const response = await ssm.send(
      new GetParameterCommand({ Name: name, WithDecryption: true })
    );
    expect(response.Parameter?.Value).toBe('s3cr3t');
  });

  it('should get parameters by path', async () => {
    const response = await ssm.send(
      new GetParametersByPathCommand({ Path: paramPath, Recursive: true })
    );
    expect(response.Parameters?.length).toBeGreaterThanOrEqual(2);
  });

  it('should describe parameters', async () => {
    const response = await ssm.send(new DescribeParametersCommand({}));
    expect(response.Parameters?.length).toBeGreaterThanOrEqual(2);
  });

  it('should overwrite parameter', async () => {
    const name = `${paramPath}/p1`;
    await ssm.send(
      new PutParameterCommand({
        Name: name,
        Value: 'updated',
        Type: 'String',
        Overwrite: true,
      })
    );

    const response = await ssm.send(new GetParameterCommand({ Name: name }));
    expect(response.Parameter?.Value).toBe('updated');
  });

  it('should fail for missing parameter', async () => {
    await expect(
      ssm.send(new GetParameterCommand({ Name: `${paramPath}/missing` }))
    ).rejects.toThrow();
  });

  it('should delete parameter', async () => {
    const name = `${paramPath}/p1`;
    await ssm.send(new DeleteParameterCommand({ Name: name }));

    await expect(ssm.send(new GetParameterCommand({ Name: name }))).rejects.toThrow();
  });
});

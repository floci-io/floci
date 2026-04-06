/**
 * Lambda integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  LambdaClient,
  CreateFunctionCommand,
  GetFunctionCommand,
  ListFunctionsCommand,
  DeleteFunctionCommand,
  CreateAliasCommand,
  GetAliasCommand,
  ListAliasesCommand,
  UpdateAliasCommand,
  DeleteAliasCommand,
  PublishVersionCommand,
} from '@aws-sdk/client-lambda';
import { makeClient, uniqueName, ACCOUNT, buildMinimalZip } from './setup';

describe('Lambda', () => {
  let lambda: LambdaClient;
  let fnName: string;

  beforeAll(() => {
    lambda = makeClient(LambdaClient);
    fnName = `test-fn-${uniqueName()}`;
  });

  afterAll(async () => {
    try {
      await lambda.send(new DeleteAliasCommand({ FunctionName: fnName, Name: 'live' }));
    } catch {
      // ignore
    }
    try {
      await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName }));
    } catch {
      // ignore
    }
  });

  it('should create function', async () => {
    const handlerCode = "exports.handler = async (event) => ({ statusCode: 200, body: 'ok' });";
    const zipBuffer = buildMinimalZip('index.js', Buffer.from(handlerCode));

    await lambda.send(
      new CreateFunctionCommand({
        FunctionName: fnName,
        Runtime: 'nodejs18.x',
        Role: `arn:aws:iam::${ACCOUNT}:role/lambda-role`,
        Handler: 'index.handler',
        Code: { ZipFile: zipBuffer },
      })
    );
  });

  it('should get function', async () => {
    const response = await lambda.send(new GetFunctionCommand({ FunctionName: fnName }));
    expect(response.Configuration?.FunctionName).toBe(fnName);
  });

  it('should list functions', async () => {
    const response = await lambda.send(new ListFunctionsCommand({}));
    expect(response.Functions?.some((f) => f.FunctionName === fnName)).toBe(true);
  });

  it('should publish version', async () => {
    const response = await lambda.send(
      new PublishVersionCommand({ FunctionName: fnName, Description: 'v1' })
    );
    expect(response.Version).toBeTruthy();
  });

  it('should create alias', async () => {
    const response = await lambda.send(
      new CreateAliasCommand({
        FunctionName: fnName,
        Name: 'live',
        FunctionVersion: '$LATEST',
        Description: 'live alias',
      })
    );
    expect(response.AliasArn).toBeTruthy();
  });

  it('should get alias', async () => {
    const response = await lambda.send(
      new GetAliasCommand({ FunctionName: fnName, Name: 'live' })
    );
    expect(response.Name).toBe('live');
  });

  it('should list aliases', async () => {
    const response = await lambda.send(new ListAliasesCommand({ FunctionName: fnName }));
    expect(response.Aliases?.some((a) => a.Name === 'live')).toBe(true);
  });

  it('should update alias', async () => {
    const response = await lambda.send(
      new UpdateAliasCommand({
        FunctionName: fnName,
        Name: 'live',
        Description: 'updated description',
      })
    );
    expect(response.Description).toBe('updated description');
  });

  it('should delete alias', async () => {
    await lambda.send(new DeleteAliasCommand({ FunctionName: fnName, Name: 'live' }));
  });

  it('should fail to get deleted alias', async () => {
    await expect(
      lambda.send(new GetAliasCommand({ FunctionName: fnName, Name: 'live' }))
    ).rejects.toThrow();
  });

  it('should delete function', async () => {
    await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName }));
    fnName = '';
  });

  it('should fail to get deleted function', async () => {
    await expect(
      lambda.send(new GetFunctionCommand({ FunctionName: `test-fn-${uniqueName()}` }))
    ).rejects.toThrow();
  });
});

/**
 * Lambda integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  LambdaClient,
  CreateFunctionCommand,
  GetFunctionCommand,
  GetFunctionConfigurationCommand,
  UpdateFunctionConfigurationCommand,
  ListFunctionsCommand,
  DeleteFunctionCommand,
  CreateAliasCommand,
  GetAliasCommand,
  ListAliasesCommand,
  UpdateAliasCommand,
  DeleteAliasCommand,
  PublishVersionCommand,
  ListVersionsByFunctionCommand,
  UpdateFunctionCodeCommand,
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

describe('Lambda ImageConfig.WorkingDirectory', () => {
  let lambda: LambdaClient;
  const IMAGE_URI = '000000000000.dkr.ecr.us-east-1.amazonaws.com/fake-repo:latest';
  const ROLE = 'arn:aws:iam::000000000000:role/lambda-role';

  beforeAll(() => {
    lambda = makeClient(LambdaClient);
  });

  it('round-trips WorkingDirectory through create and get', async () => {
    const fnName = `test-imgwd-${uniqueName()}`;
    try {
      const createResp = await lambda.send(new CreateFunctionCommand({
        FunctionName: fnName,
        PackageType: 'Image',
        Role: ROLE,
        Code: { ImageUri: IMAGE_URI },
        ImageConfig: { WorkingDirectory: '/app' },
      }));

      expect(createResp.ImageConfigResponse?.ImageConfig?.WorkingDirectory).toBe('/app');

      const getResp = await lambda.send(new GetFunctionConfigurationCommand({
        FunctionName: fnName,
      }));

      expect(getResp.ImageConfigResponse?.ImageConfig?.WorkingDirectory).toBe('/app');
    } finally {
      await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })).catch(() => {});
    }
  });

  it('updates WorkingDirectory via updateFunctionConfiguration', async () => {
    const fnName = `test-imgwd-upd-${uniqueName()}`;
    try {
      await lambda.send(new CreateFunctionCommand({
        FunctionName: fnName,
        PackageType: 'Image',
        Role: ROLE,
        Code: { ImageUri: IMAGE_URI },
        ImageConfig: { WorkingDirectory: '/initial' },
      }));

      const updateResp = await lambda.send(new UpdateFunctionConfigurationCommand({
        FunctionName: fnName,
        ImageConfig: { WorkingDirectory: '/updated' },
      }));

      expect(updateResp.ImageConfigResponse?.ImageConfig?.WorkingDirectory).toBe('/updated');
    } finally {
      await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })).catch(() => {});
    }
  });
});

describe('Lambda Publish flag', () => {
  let lambda: LambdaClient;
  let fnName: string;

  beforeAll(() => {
    lambda = makeClient(LambdaClient);
    fnName = `test-publish-${uniqueName()}`;
  });

  afterAll(async () => {
    await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })).catch(() => {});
  });

  it('should publish version 1 on create and keep the ARN unqualified', async () => {
    const response = await lambda.send(new CreateFunctionCommand({
      FunctionName: fnName,
      Runtime: 'nodejs20.x',
      Role: `arn:aws:iam::${ACCOUNT}:role/lambda-role`,
      Handler: 'index.handler',
      Code: { ZipFile: buildMinimalZip('index.js', Buffer.from('exports.handler = async () => 1;')) },
      Publish: true,
    }));

    expect(response.Version).toBe('1');
    expect(response.FunctionArn).toMatch(new RegExp(`:function:${fnName}$`));

    const listed = await lambda.send(new ListVersionsByFunctionCommand({ FunctionName: fnName }));
    expect((listed.Versions ?? []).map((v) => v.Version).sort()).toEqual(['$LATEST', '1']);
  });

  it('should publish the next version on code update and return a qualified ARN', async () => {
    const response = await lambda.send(new UpdateFunctionCodeCommand({
      FunctionName: fnName,
      ZipFile: buildMinimalZip('index.js', Buffer.from('exports.handler = async () => 2;')),
      Publish: true,
    }));

    expect(response.Version).toBe('2');
    expect(response.FunctionArn).toMatch(new RegExp(`:function:${fnName}:2$`));
  });

  it('should create no version when Publish is not set', async () => {
    const response = await lambda.send(new UpdateFunctionCodeCommand({
      FunctionName: fnName,
      ZipFile: buildMinimalZip('index.js', Buffer.from('exports.handler = async () => 3;')),
    }));

    expect(response.Version).toBe('$LATEST');

    const listed = await lambda.send(new ListVersionsByFunctionCommand({ FunctionName: fnName }));
    expect((listed.Versions ?? []).map((v) => v.Version).sort()).toEqual(['$LATEST', '1', '2']);
  });
});

describe('Lambda version deletion', () => {
  let lambda: LambdaClient;
  let fnName: string;

  const versions = async () => {
    const r = await lambda.send(new ListVersionsByFunctionCommand({ FunctionName: fnName }));
    return (r.Versions ?? []).map((v) => v.Version).sort();
  };

  beforeAll(async () => {
    lambda = makeClient(LambdaClient);
    fnName = `test-delver-${uniqueName()}`;

    await lambda.send(new CreateFunctionCommand({
      FunctionName: fnName,
      Runtime: 'nodejs20.x',
      Role: `arn:aws:iam::${ACCOUNT}:role/lambda-role`,
      Handler: 'index.handler',
      Code: { ZipFile: buildMinimalZip('index.js', Buffer.from('exports.handler = async () => 1;')) },
    }));
    await lambda.send(new PublishVersionCommand({ FunctionName: fnName }));
    await lambda.send(new UpdateFunctionCodeCommand({
      FunctionName: fnName,
      ZipFile: buildMinimalZip('index.js', Buffer.from('exports.handler = async () => 2;')),
    }));
    await lambda.send(new PublishVersionCommand({ FunctionName: fnName }));
  });

  afterAll(async () => {
    await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })).catch(() => {});
  });

  it('should delete only the qualified version, leaving the function intact', async () => {
    expect(await versions()).toEqual(['$LATEST', '1', '2']);

    await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName, Qualifier: '2' }));

    // The function survives — before this was fixed, the whole function was deleted.
    const fn = await lambda.send(new GetFunctionCommand({ FunctionName: fnName }));
    expect(fn.Configuration?.FunctionName).toBe(fnName);
    expect(await versions()).toEqual(['$LATEST', '1']);
  });

  it('should refuse to delete $LATEST by qualifier', async () => {
    await expect(
      lambda.send(new DeleteFunctionCommand({ FunctionName: fnName, Qualifier: '$LATEST' }))
    ).rejects.toThrow(/cannot be deleted without deleting the function/);
    expect(await versions()).toEqual(['$LATEST', '1']);
  });

  it('should refuse to delete a version an alias references', async () => {
    await lambda.send(new CreateAliasCommand({
      FunctionName: fnName,
      Name: 'pinned',
      FunctionVersion: '1',
    }));

    await expect(
      lambda.send(new DeleteFunctionCommand({ FunctionName: fnName, Qualifier: '1' }))
    ).rejects.toThrow(/aliases reference it/);
    expect(await versions()).toEqual(['$LATEST', '1']);
  });

  it('should treat a version that does not exist as a no-op', async () => {
    await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName, Qualifier: '99' }));
    expect(await versions()).toEqual(['$LATEST', '1']);
  });
});

/**
 * Lambda Web Functions integration tests.
 *
 * One function through its life. The tests run in order and share it.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  LambdaWebClient,
  CreateWebFunctionCommand,
  CreateWebFunctionEndpointCommand,
  CreateWebFunctionRevisionCommand,
  DeleteResourcePolicyCommand,
  DeleteWebFunctionCommand,
  DeleteWebFunctionRevisionCommand,
  GetResourcePolicyCommand,
  GetWebAccountSettingsCommand,
  GetWebFunctionCommand,
  GetWebFunctionEndpointCommand,
  GetWebFunctionRevisionCommand,
  ListTagsCommand,
  ListWebFunctionRevisionsCommand,
  ListWebFunctionsCommand,
  PutResourcePolicyCommand,
  TagResourceCommand,
  UntagResourceCommand,
  UpdateWebFunctionEndpointCommand,
  waitUntilWebFunctionDeleted,
  waitUntilWebFunctionEndpointActive,
  waitUntilWebFunctionEndpointUpdated,
  waitUntilWebFunctionRevisionActive,
  type BuildConfig,
} from '@aws-sdk/client-lambda-web';
import {
  S3Client,
  CreateBucketCommand,
  DeleteBucketCommand,
  DeleteObjectCommand,
  ListObjectVersionsCommand,
  PutBucketPolicyCommand,
  PutBucketVersioningCommand,
  PutObjectCommand,
} from '@aws-sdk/client-s3';
import { IAMClient, CreateRoleCommand, DeleteRoleCommand } from '@aws-sdk/client-iam';
import { makeClient, uniqueName, REGION, buildMinimalZip } from './setup';

const FUNCTION_NAME = 'vitest-web-fn';
const LAMBDA_TRUST = JSON.stringify({
  Version: '2012-10-17',
  Statement: [{ Effect: 'Allow', Principal: { Service: 'lambda.amazonaws.com' }, Action: 'sts:AssumeRole' }],
});
// Stays under the 60 s test timeout so the waiter fails first with its own error.
const WAIT = { maxWaitTime: 50 };

function invokePolicy(principal: unknown, functionArn: string): string {
  return JSON.stringify({
    Version: '2012-10-17',
    Statement: [{
      Effect: 'Allow',
      Principal: principal,
      Action: 'lambda:InvokeWebFunctionEndpoint',
      Resource: `${functionArn}/endpoint/*`,
    }],
  });
}

function refusal(name: string, message: string) {
  return { name, message: expect.stringContaining(message) };
}

describe('Lambda Web Functions', () => {
  let web: LambdaWebClient;
  let s3: S3Client;
  let iam: IAMClient;
  let roleName: string;
  let role: string | undefined;
  let bucket: string;
  let bucketCreated = false;
  let functionArn: string;
  let firstRevision: string;
  let secondRevision: string;

  const buildConfig = (): BuildConfig => ({
    codeConfig: { s3Object: { bucket, key: 'app.zip' } },
    runtimeConfig: { runtime: 'nodejs24.x' },
  });

  beforeAll(async () => {
    web = makeClient(LambdaWebClient);
    s3 = makeClient(S3Client);
    iam = makeClient(IAMClient);
    roleName = uniqueName('vitest-web-role');
    bucket = uniqueName('vitest-web-code');
    role = (await iam.send(new CreateRoleCommand({ RoleName: roleName, AssumeRolePolicyDocument: LAMBDA_TRUST })))
      .Role?.Arn;
    await s3.send(new CreateBucketCommand({ Bucket: bucket }));
    bucketCreated = true;
    // AWS takes code only from a versioned bucket that lets Lambda read it.
    await s3.send(new PutBucketVersioningCommand({ Bucket: bucket, VersioningConfiguration: { Status: 'Enabled' } }));
    await s3.send(new PutBucketPolicyCommand({
      Bucket: bucket,
      Policy: JSON.stringify({
        Version: '2012-10-17',
        Statement: [{
          Effect: 'Allow',
          Principal: { Service: 'lambda.amazonaws.com' },
          Action: ['s3:GetObject', 's3:GetObjectVersion'],
          Resource: `arn:aws:s3:::${bucket}/*`,
        }],
      }),
    }));
    await s3.send(new PutObjectCommand({ Bucket: bucket, Key: 'app.zip', Body: buildMinimalZip('index.js', Buffer.from('')) }));
  });

  afterAll(async () => {
    try {
      await web.send(new DeleteWebFunctionCommand({ functionName: FUNCTION_NAME }));
    } catch (e) {
      // The last test deletes the function itself.
      if ((e as Error).name !== 'ResourceNotFoundException') throw e;
    } finally {
      try {
        if (bucketCreated) {
          const versions = await s3.send(new ListObjectVersionsCommand({ Bucket: bucket }));
          for (const version of versions.Versions ?? []) {
            await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: version.Key, VersionId: version.VersionId }));
          }
          await s3.send(new DeleteBucketCommand({ Bucket: bucket }));
        }
      } finally {
        if (role) {
          await iam.send(new DeleteRoleCommand({ RoleName: roleName }));
        }
      }
    }
  });

  it('create web function reports pending children', async () => {
    const created = await web.send(new CreateWebFunctionCommand({
      functionName: FUNCTION_NAME,
      revisionConfig: { buildConfig: buildConfig(), serviceConfig: { executionRoleArn: role } },
      endpointConfig: {
        endpointName: 'dev',
        endpointType: 'HomeRegion',
        authType: 'ApplicationManaged',
        autoDeploymentMode: 'LatestRevision',
      },
      tags: { env: 'sdk' },
    }));
    functionArn = created.functionArn!;
    firstRevision = created.revision!.revisionId!;
    expect(functionArn.endsWith(`:web-function/${FUNCTION_NAME}`)).toBe(true);
    expect(created.state).toBe('Active');
    expect(created.revision?.state).toBe('Pending');
    expect(created.endpoint?.state).toBe('Pending');
    expect(created.endpoint?.domainName).toContain('.lambda-web.');
    expect(created.createdAt).toBeInstanceOf(Date);

    await waitUntilWebFunctionEndpointActive({ client: web, ...WAIT }, { functionName: FUNCTION_NAME, endpointName: 'dev' });
    const endpoint = await web.send(new GetWebFunctionEndpointCommand({ functionName: FUNCTION_NAME, endpointName: 'dev' }));
    expect(endpoint.state).toBe('Active');
    expect(endpoint.revisionWeights?.map((w) => w.revisionId)).toEqual([firstRevision]);
    expect(endpoint.regionalEndpoints?.[REGION]).toBeDefined();
    expect(endpoint.updateStatus).toBeUndefined();
  });

  it('functions are addressable by name and ARN', async () => {
    expect((await web.send(new GetWebFunctionCommand({ functionName: functionArn }))).functionName).toBe(FUNCTION_NAME);
    const functions = await web.send(new ListWebFunctionsCommand({}));
    expect(functions.functions?.map((f) => f.functionName)).toContain(FUNCTION_NAME);
    const settings = await web.send(new GetWebAccountSettingsCommand({}));
    expect(settings.accountQuotas?.maxEndpointsPerFunction).toBe(10);

    const otherRegion = REGION === 'eu-west-1' ? 'us-west-2' : 'eu-west-1';
    const foreignArn = functionArn.replace(`:${REGION}:`, `:${otherRegion}:`);
    await expect(web.send(new GetWebFunctionCommand({ functionName: foreignArn }))).rejects.toMatchObject(
      refusal('ValidationException', `The operation is only allowed in the function's home region ${otherRegion}.`));
    await expect(web.send(new GetWebFunctionCommand({ functionName: 'vitest-no-such-web-fn' }))).rejects.toMatchObject(
      refusal('ResourceNotFoundException', 'does not exist.'));
  });

  it('a new revision moves a LatestRevision endpoint', async () => {
    const revision = await web.send(new CreateWebFunctionRevisionCommand({
      functionName: FUNCTION_NAME,
      description: 'second',
      buildConfig: buildConfig(),
      serviceConfig: { executionRoleArn: role, timeoutSeconds: 60, environmentVariables: { APP_VERSION: '2' } },
    }));
    secondRevision = revision.revisionId!;
    expect(revision.state).toBe('Pending');
    expect(revision.serviceConfig?.timeoutSeconds).toBe(60);
    expect(revision.serviceConfig?.maxConcurrencyPerEnvironment).toBe(64);
    expect(revision.serviceConfig?.telemetryConfig?.loggingConfig?.logGroup).toBe(`/aws/lambda/web/${FUNCTION_NAME}`);

    await waitUntilWebFunctionRevisionActive({ client: web, ...WAIT }, { functionName: FUNCTION_NAME, revisionId: secondRevision });
    const revisions = await web.send(new ListWebFunctionRevisionsCommand({ functionName: FUNCTION_NAME }));
    expect(revisions.revisions?.map((r) => r.revisionId)).toEqual([secondRevision, firstRevision]);
    const endpoint = await web.send(new GetWebFunctionEndpointCommand({ functionName: FUNCTION_NAME, endpointName: 'dev' }));
    expect(endpoint.revisionWeights?.map((w) => w.revisionId)).toEqual([secondRevision]);
  });

  it('weighted routing and the in-use guard', async () => {
    const update = await web.send(new UpdateWebFunctionEndpointCommand({
      functionName: FUNCTION_NAME,
      endpointName: 'dev',
      autoDeploymentMode: 'Disabled',
      revisionWeights: [{ revisionId: firstRevision, weight: 90 }, { revisionId: secondRevision, weight: 10 }],
    }));
    expect(update.updateStatus).toBe('InProgress');
    expect(update.revisionWeights).toHaveLength(2);

    await waitUntilWebFunctionEndpointUpdated({ client: web, ...WAIT }, { functionName: FUNCTION_NAME, endpointName: 'dev' });
    const endpoint = await web.send(new GetWebFunctionEndpointCommand({ functionName: FUNCTION_NAME, endpointName: 'dev' }));
    expect(endpoint.updateStatus).toBe('Successful');
    expect(endpoint.revisionWeights).toHaveLength(2);
    expect(endpoint.revisionWeights).toEqual(expect.arrayContaining([
      expect.objectContaining({ revisionId: firstRevision, weight: 90 }),
      expect.objectContaining({ revisionId: secondRevision, weight: 10 }),
    ]));

    await expect(web.send(new DeleteWebFunctionRevisionCommand({ functionName: FUNCTION_NAME, revisionId: firstRevision })))
      .rejects.toMatchObject(refusal('ConflictException', 'the endpoints [dev] are using it'));
    await expect(web.send(new UpdateWebFunctionEndpointCommand({
      functionName: FUNCTION_NAME,
      endpointName: 'dev',
      revisionWeights: [{ revisionId: 'rev-zzzzz', weight: 100 }],
    }))).rejects.toMatchObject(refusal('ResourceNotFoundException', 'The revision rev-zzzzz does not exist.'));
  });

  it('validation errors use the AWS wording', async () => {
    await expect(web.send(new CreateWebFunctionRevisionCommand({
      functionName: FUNCTION_NAME,
      buildConfig: buildConfig(),
      serviceConfig: { executionRoleArn: role, timeoutSeconds: 2 },
    }))).rejects.toMatchObject(refusal('ValidationException',
      "1 validation error detected: Value '2' at 'serviceConfig.timeoutSeconds' failed to satisfy constraint:"
        + ' Member must have value greater than or equal to 3'));
    await expect(web.send(new GetWebFunctionRevisionCommand({ functionName: FUNCTION_NAME, revisionId: 'nope' })))
      .rejects.toMatchObject(refusal('ValidationException', "Invalid revision id or ARN 'nope'"));
    await expect(web.send(new CreateWebFunctionEndpointCommand({
      functionName: FUNCTION_NAME,
      endpointName: 'prod',
      endpointType: 'HomeRegion',
      authType: 'ApplicationManaged',
    }))).rejects.toMatchObject(refusal('ValidationException',
      'The revisionWeights must be specified when the autoDeploymentMode is Disabled.'));

    const missingRole = role!.replace(roleName, `${roleName}-missing`);
    await expect(web.send(new CreateWebFunctionRevisionCommand({
      functionName: FUNCTION_NAME,
      buildConfig: buildConfig(),
      serviceConfig: { executionRoleArn: missingRole },
    }))).rejects.toMatchObject(refusal('ValidationException',
      `The execution role ${missingRole} cannot be assumed by lambda.amazonaws.com.`
        + ' Verify its trust policy. If the role was just created, retry in a few seconds.'));
    await expect(web.send(new CreateWebFunctionRevisionCommand({
      functionName: FUNCTION_NAME,
      buildConfig: buildConfig(),
      serviceConfig: { executionRoleArn: `arn:aws:iam::111122223333:role/${roleName}` },
    }))).rejects.toMatchObject({
      ...refusal('AccessDeniedException', 'Cross-account pass role is not allowed.'),
      $metadata: { httpStatusCode: 403 },
    });
  });

  it('tags and the resource policy', async () => {
    await web.send(new TagResourceCommand({ resource: functionArn, tags: { team: 'web' } }));
    expect((await web.send(new ListTagsCommand({ resource: functionArn }))).tags).toEqual({ env: 'sdk', team: 'web' });
    await web.send(new UntagResourceCommand({ resource: functionArn, tagKeys: ['env'] }));
    expect((await web.send(new ListTagsCommand({ resource: functionArn }))).tags).toEqual({ team: 'web' });

    await expect(web.send(new PutResourcePolicyCommand({ resourceArn: functionArn, policy: invokePolicy('*', functionArn) })))
      .rejects.toMatchObject(refusal('ValidationException', 'Lambda blocked the creation of this policy'));
    await expect(web.send(new PutResourcePolicyCommand({ resourceArn: functionArn, policy: '{}' })))
      .rejects.toMatchObject(refusal('ValidationException', 'Policy has syntax errors.'));

    const account = role!.split(':')[4];
    const policy = invokePolicy({ AWS: `arn:aws:iam::${account}:root` }, functionArn);
    const put = await web.send(new PutResourcePolicyCommand({ resourceArn: functionArn, policy }));
    expect(put.policy).toBe(policy);
    expect(put.revisionId).toMatch(/^[0-9a-f-]{36}$/);
    expect((await web.send(new GetResourcePolicyCommand({ resourceArn: functionArn }))).revisionId).toBe(put.revisionId);
    await web.send(new DeleteResourcePolicyCommand({ resourceArn: functionArn, revisionId: put.revisionId }));
    await expect(web.send(new GetResourcePolicyCommand({ resourceArn: functionArn })))
      .rejects.toMatchObject(refusal('ResourceNotFoundException', 'The function does not have a policy.'));
  });

  it('delete removes the function', async () => {
    await web.send(new DeleteWebFunctionCommand({ functionName: FUNCTION_NAME }));
    await waitUntilWebFunctionDeleted({ client: web, ...WAIT }, { functionName: FUNCTION_NAME });
    await expect(web.send(new GetWebFunctionCommand({ functionName: FUNCTION_NAME })))
      .rejects.toMatchObject({ name: 'ResourceNotFoundException' });
  });
});

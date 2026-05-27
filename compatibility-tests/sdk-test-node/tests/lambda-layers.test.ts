/**
 * Lambda Layers compatibility tests.
 *
 * Verifies that the real @aws-sdk/client-lambda can round-trip through
 * Floci's layer endpoints without wire-format issues.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  LambdaClient,
  PublishLayerVersionCommand,
  GetLayerVersionCommand,
  ListLayerVersionsCommand,
  ListLayersCommand,
  DeleteLayerVersionCommand,
  CreateFunctionCommand,
  GetFunctionConfigurationCommand,
  UpdateFunctionConfigurationCommand,
  DeleteFunctionCommand,
} from '@aws-sdk/client-lambda';
import { makeClient, uniqueName, ACCOUNT, buildMinimalZip } from './setup';

describe('Lambda Layers', () => {
  let lambda: LambdaClient;
  let layerName: string;
  let layerArn: string;
  let layerVersionArn1: string;
  let layerVersionArn2: string;

  beforeAll(() => {
    lambda = makeClient(LambdaClient);
    layerName = `compat-layer-${uniqueName()}`;
  });

  afterAll(async () => {
    // Clean up any remaining layer versions
    try {
      await lambda.send(new DeleteLayerVersionCommand({ LayerName: layerName, VersionNumber: 1 }));
    } catch { /* ignore */ }
    try {
      await lambda.send(new DeleteLayerVersionCommand({ LayerName: layerName, VersionNumber: 2 }));
    } catch { /* ignore */ }
  });

  it('should publish layer version 1', async () => {
    const layerContent = Buffer.from('module.exports.util = () => "v1";');
    const zipBuffer = buildMinimalZip('nodejs/node_modules/shared/index.js', layerContent);

    const response = await lambda.send(
      new PublishLayerVersionCommand({
        LayerName: layerName,
        Content: { ZipFile: zipBuffer },
        CompatibleRuntimes: ['nodejs20.x', 'nodejs22.x'],
        CompatibleArchitectures: ['x86_64'],
        Description: 'Shared utilities v1',
        LicenseInfo: 'MIT',
      })
    );

    expect(response.Version).toBe(1);
    expect(response.LayerArn).toContain(`:layer:${layerName}`);
    expect(response.LayerVersionArn).toContain(`:layer:${layerName}:1`);
    expect(response.Description).toBe('Shared utilities v1');
    expect(response.LicenseInfo).toBe('MIT');
    expect(response.CompatibleRuntimes).toContain('nodejs20.x');
    expect(response.CompatibleRuntimes).toContain('nodejs22.x');
    expect(response.CompatibleArchitectures).toContain('x86_64');
    expect(response.Content?.CodeSize).toBeGreaterThan(0);
    expect(response.Content?.CodeSha256).toBeTruthy();
    expect(response.CreatedDate).toBeTruthy();

    layerArn = response.LayerArn!;
    layerVersionArn1 = response.LayerVersionArn!;
  });

  it('should publish layer version 2', async () => {
    const layerContent = Buffer.from('module.exports.util = () => "v2";');
    const zipBuffer = buildMinimalZip('nodejs/node_modules/shared/index.js', layerContent);

    const response = await lambda.send(
      new PublishLayerVersionCommand({
        LayerName: layerName,
        Content: { ZipFile: zipBuffer },
        CompatibleRuntimes: ['nodejs22.x'],
        Description: 'Shared utilities v2',
      })
    );

    expect(response.Version).toBe(2);
    expect(response.LayerVersionArn).toContain(`:layer:${layerName}:2`);
    expect(response.Description).toBe('Shared utilities v2');

    layerVersionArn2 = response.LayerVersionArn!;
  });

  it('should get layer version 1', async () => {
    const response = await lambda.send(
      new GetLayerVersionCommand({
        LayerName: layerName,
        VersionNumber: 1,
      })
    );

    expect(response.Version).toBe(1);
    expect(response.LayerArn).toBe(layerArn);
    expect(response.LayerVersionArn).toBe(layerVersionArn1);
    expect(response.Description).toBe('Shared utilities v1');
    expect(response.Content?.CodeSha256).toBeTruthy();
    expect(response.Content?.CodeSize).toBeGreaterThan(0);
  });

  it('should fail to get non-existent layer version', async () => {
    await expect(
      lambda.send(new GetLayerVersionCommand({ LayerName: layerName, VersionNumber: 99 }))
    ).rejects.toThrow();
  });

  it('should list layer versions', async () => {
    const response = await lambda.send(
      new ListLayerVersionsCommand({ LayerName: layerName })
    );

    expect(response.LayerVersions).toHaveLength(2);
    expect(response.LayerVersions![0].Version).toBe(1);
    expect(response.LayerVersions![1].Version).toBe(2);
  });

  it('should list layers and include our layer', async () => {
    const response = await lambda.send(new ListLayersCommand({}));

    const ourLayer = response.Layers?.find((l) => l.LayerName === layerName);
    expect(ourLayer).toBeDefined();
    expect(ourLayer!.LayerArn).toBe(layerArn);
    expect(ourLayer!.LatestMatchingVersion?.Version).toBe(2);
  });

  it('should delete layer version 1', async () => {
    // DeleteLayerVersion returns nothing on success (204)
    await lambda.send(
      new DeleteLayerVersionCommand({ LayerName: layerName, VersionNumber: 1 })
    );

    // Verify it's gone
    await expect(
      lambda.send(new GetLayerVersionCommand({ LayerName: layerName, VersionNumber: 1 }))
    ).rejects.toThrow();
  });

  it('should list layer versions after deletion', async () => {
    const response = await lambda.send(
      new ListLayerVersionsCommand({ LayerName: layerName })
    );

    expect(response.LayerVersions).toHaveLength(1);
    expect(response.LayerVersions![0].Version).toBe(2);
  });

  it('should handle delete of non-existent version gracefully', async () => {
    // AWS returns 204 even for non-existent versions
    await lambda.send(
      new DeleteLayerVersionCommand({ LayerName: layerName, VersionNumber: 99 })
    );
  });
});

describe('Lambda Layer Attachment', () => {
  let lambda: LambdaClient;
  let layerName: string;
  let layerVersionArn: string;
  let fnName: string;

  beforeAll(async () => {
    lambda = makeClient(LambdaClient);
    layerName = `attach-layer-${uniqueName()}`;
    fnName = `layer-fn-${uniqueName()}`;

    // Publish a layer to attach
    const layerContent = Buffer.from('module.exports.dep = () => "shared";');
    const zipBuffer = buildMinimalZip('nodejs/node_modules/dep/index.js', layerContent);

    const layerResp = await lambda.send(
      new PublishLayerVersionCommand({
        LayerName: layerName,
        Content: { ZipFile: zipBuffer },
        CompatibleRuntimes: ['nodejs20.x'],
      })
    );
    layerVersionArn = layerResp.LayerVersionArn!;
  });

  afterAll(async () => {
    try {
      await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName }));
    } catch { /* ignore */ }
    try {
      await lambda.send(new DeleteLayerVersionCommand({ LayerName: layerName, VersionNumber: 1 }));
    } catch { /* ignore */ }
  });

  it('should create function with layer attached', async () => {
    const handlerCode = "exports.handler = async () => ({ statusCode: 200 });";
    const zipBuffer = buildMinimalZip('index.js', Buffer.from(handlerCode));

    const response = await lambda.send(
      new CreateFunctionCommand({
        FunctionName: fnName,
        Runtime: 'nodejs20.x',
        Role: `arn:aws:iam::${ACCOUNT}:role/lambda-role`,
        Handler: 'index.handler',
        Code: { ZipFile: zipBuffer },
        Layers: [layerVersionArn],
      })
    );

    expect(response.Layers).toHaveLength(1);
    expect(response.Layers![0].Arn).toBe(layerVersionArn);
  });

  it('should show layers in function configuration', async () => {
    const response = await lambda.send(
      new GetFunctionConfigurationCommand({ FunctionName: fnName })
    );

    expect(response.Layers).toHaveLength(1);
    expect(response.Layers![0].Arn).toBe(layerVersionArn);
  });

  it('should update function to remove layers', async () => {
    const response = await lambda.send(
      new UpdateFunctionConfigurationCommand({
        FunctionName: fnName,
        Layers: [],
      })
    );

    // After removing layers, the field should be empty or absent
    expect(response.Layers ?? []).toHaveLength(0);
  });

  it('should update function to add layers back', async () => {
    const response = await lambda.send(
      new UpdateFunctionConfigurationCommand({
        FunctionName: fnName,
        Layers: [layerVersionArn],
      })
    );

    expect(response.Layers).toHaveLength(1);
    expect(response.Layers![0].Arn).toBe(layerVersionArn);
  });
});

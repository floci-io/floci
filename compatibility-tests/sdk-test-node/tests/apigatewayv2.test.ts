/**
 * API Gateway v2 (HTTP & WebSocket APIs) compatibility tests.
 *
 * Validates management-plane CRUD for APIs, Routes, Integrations, Authorizers,
 * Stages, Deployments, Route Responses, Integration Responses, Models, and Tags
 * using the AWS SDK v3 ApiGatewayV2Client — the same client real applications use.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ApiGatewayV2Client,
  CreateApiCommand,
  GetApiCommand,
  GetApisCommand,
  UpdateApiCommand,
  DeleteApiCommand,
  CreateRouteCommand,
  GetRouteCommand,
  GetRoutesCommand,
  UpdateRouteCommand,
  DeleteRouteCommand,
  CreateIntegrationCommand,
  GetIntegrationCommand,
  GetIntegrationsCommand,
  UpdateIntegrationCommand,
  DeleteIntegrationCommand,
  CreateAuthorizerCommand,
  GetAuthorizerCommand,
  GetAuthorizersCommand,
  UpdateAuthorizerCommand,
  DeleteAuthorizerCommand,
  CreateStageCommand,
  GetStageCommand,
  GetStagesCommand,
  UpdateStageCommand,
  DeleteStageCommand,
  CreateDeploymentCommand,
  GetDeploymentCommand,
  GetDeploymentsCommand,
  UpdateDeploymentCommand,
  DeleteDeploymentCommand,
  CreateRouteResponseCommand,
  GetRouteResponseCommand,
  GetRouteResponsesCommand,
  UpdateRouteResponseCommand,
  DeleteRouteResponseCommand,
  CreateModelCommand,
  GetModelCommand,
  GetModelsCommand,
  UpdateModelCommand,
  DeleteModelCommand,
  TagResourceCommand,
  UntagResourceCommand,
  GetTagsCommand,
} from '@aws-sdk/client-apigatewayv2';
import { makeClient, uniqueName, REGION, ACCOUNT } from './setup';

describe('API Gateway v2', () => {
  let gw: ApiGatewayV2Client;

  beforeAll(() => {
    gw = makeClient(ApiGatewayV2Client);
  });

  // ──────────────────────────── HTTP API lifecycle ────────────────────────────

  describe('HTTP API lifecycle', () => {
    let apiId: string;

    afterAll(async () => {
      try { if (apiId) await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create an HTTP API with correct defaults', async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('http-api'),
        ProtocolType: 'HTTP',
      }));
      apiId = res.ApiId!;
      expect(apiId).toBeTruthy();
      expect(res.ProtocolType).toBe('HTTP');
      expect(res.ApiEndpoint).toContain('https://');
      expect(res.RouteSelectionExpression).toBe('${request.method} ${request.path}');
      expect(res.ApiKeySelectionExpression).toBe('$request.header.x-api-key');
    });

    it('should get the API', async () => {
      const res = await gw.send(new GetApiCommand({ ApiId: apiId }));
      expect(res.ApiId).toBe(apiId);
      expect(res.ProtocolType).toBe('HTTP');
      expect(res.RouteSelectionExpression).toBe('${request.method} ${request.path}');
      expect(res.ApiKeySelectionExpression).toBe('$request.header.x-api-key');
    });

    it('should list APIs including the created one', async () => {
      const res = await gw.send(new GetApisCommand({}));
      expect(res.Items!.some(a => a.ApiId === apiId)).toBe(true);
    });

    it('should update the API', async () => {
      const res = await gw.send(new UpdateApiCommand({
        ApiId: apiId,
        Description: 'updated-description',
      }));
      expect(res.Description).toBe('updated-description');
      expect(res.ProtocolType).toBe('HTTP');
    });

    it('should delete the API', async () => {
      await gw.send(new DeleteApiCommand({ ApiId: apiId }));
      await expect(gw.send(new GetApiCommand({ ApiId: apiId }))).rejects.toThrow();
      apiId = '';
    });
  });

  // ──────────────────────────── WebSocket API lifecycle ────────────────────────────

  describe('WebSocket API lifecycle', () => {
    let wsApiId: string;

    afterAll(async () => {
      try { if (wsApiId) await gw.send(new DeleteApiCommand({ ApiId: wsApiId })); } catch { /* ignore */ }
    });

    it('should create a WebSocket API', async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('ws-api'),
        ProtocolType: 'WEBSOCKET',
        RouteSelectionExpression: '$request.body.action',
        Description: 'WS compat test',
        ApiKeySelectionExpression: '$request.header.x-api-key',
      }));
      wsApiId = res.ApiId!;
      expect(wsApiId).toBeTruthy();
      expect(res.ProtocolType).toBe('WEBSOCKET');
      expect(res.ApiEndpoint).toContain('wss://');
      expect(res.RouteSelectionExpression).toBe('$request.body.action');
      expect(res.Description).toBe('WS compat test');
    });

    it('should reject WebSocket API without RouteSelectionExpression', async () => {
      await expect(gw.send(new CreateApiCommand({
        Name: uniqueName('ws-no-rse'),
        ProtocolType: 'WEBSOCKET',
      }))).rejects.toThrow();
    });

    it('should update a WebSocket API preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateApiCommand({
        ApiId: wsApiId,
        Name: 'ws-updated',
      }));
      expect(res.Name).toBe('ws-updated');
      expect(res.ProtocolType).toBe('WEBSOCKET');
      expect(res.RouteSelectionExpression).toBe('$request.body.action');
      expect(res.ApiEndpoint).toContain('wss://');
    });
  });

  // ──────────────────────────── Routes ────────────────────────────

  describe('Routes', () => {
    let apiId: string;
    let routeId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('route-test'),
        ProtocolType: 'WEBSOCKET',
        RouteSelectionExpression: '$request.body.action',
      }));
      apiId = res.ApiId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a route with routeResponseSelectionExpression', async () => {
      const res = await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        AuthorizationType: 'NONE',
        RouteResponseSelectionExpression: '$default',
      }));
      routeId = res.RouteId!;
      expect(routeId).toBeTruthy();
      expect(res.RouteKey).toBe('$default');
      expect(res.RouteResponseSelectionExpression).toBe('$default');
    });

    it('should get the route', async () => {
      const res = await gw.send(new GetRouteCommand({ ApiId: apiId, RouteId: routeId }));
      expect(res.RouteId).toBe(routeId);
      expect(res.RouteResponseSelectionExpression).toBe('$default');
    });

    it('should list routes', async () => {
      const res = await gw.send(new GetRoutesCommand({ ApiId: apiId }));
      expect(res.Items!.some(r => r.RouteId === routeId)).toBe(true);
    });

    it('should update a route preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateRouteCommand({
        ApiId: apiId,
        RouteId: routeId,
        Target: 'integrations/fake-id',
      }));
      expect(res.Target).toBe('integrations/fake-id');
      expect(res.RouteKey).toBe('$default');
      expect(res.RouteResponseSelectionExpression).toBe('$default');
    });

    it('should delete the route', async () => {
      await gw.send(new DeleteRouteCommand({ ApiId: apiId, RouteId: routeId }));
      await expect(gw.send(new GetRouteCommand({ ApiId: apiId, RouteId: routeId }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Integrations ────────────────────────────

  describe('Integrations', () => {
    let apiId: string;
    let integrationId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('integ-test'),
        ProtocolType: 'HTTP',
      }));
      apiId = res.ApiId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create an integration', async () => {
      const res = await gw.send(new CreateIntegrationCommand({
        ApiId: apiId,
        IntegrationType: 'HTTP_PROXY',
        IntegrationUri: 'https://example.com',
        PayloadFormatVersion: '2.0',
      }));
      integrationId = res.IntegrationId!;
      expect(integrationId).toBeTruthy();
      expect(res.IntegrationType).toBe('HTTP_PROXY');
    });

    it('should get the integration', async () => {
      const res = await gw.send(new GetIntegrationCommand({ ApiId: apiId, IntegrationId: integrationId }));
      expect(res.IntegrationId).toBe(integrationId);
      expect(res.IntegrationUri).toBe('https://example.com');
    });

    it('should list integrations', async () => {
      const res = await gw.send(new GetIntegrationsCommand({ ApiId: apiId }));
      expect(res.Items!.some(i => i.IntegrationId === integrationId)).toBe(true);
    });

    it('should update an integration preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateIntegrationCommand({
        ApiId: apiId,
        IntegrationId: integrationId,
        IntegrationUri: 'https://updated.example.com',
      }));
      expect(res.IntegrationUri).toBe('https://updated.example.com');
      expect(res.IntegrationType).toBe('HTTP_PROXY');
      expect(res.PayloadFormatVersion).toBe('2.0');
    });

    it('should delete the integration', async () => {
      await gw.send(new DeleteIntegrationCommand({ ApiId: apiId, IntegrationId: integrationId }));
      await expect(gw.send(new GetIntegrationCommand({ ApiId: apiId, IntegrationId: integrationId }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Authorizers ────────────────────────────

  describe('Authorizers', () => {
    let apiId: string;
    let authorizerId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('auth-test'),
        ProtocolType: 'HTTP',
      }));
      apiId = res.ApiId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a JWT authorizer', async () => {
      const res = await gw.send(new CreateAuthorizerCommand({
        ApiId: apiId,
        AuthorizerType: 'JWT',
        Name: 'test-jwt-auth',
        IdentitySource: '$request.header.Authorization',
        JwtConfiguration: {
          Issuer: 'https://issuer.example.com',
          Audience: ['my-audience'],
        },
      }));
      authorizerId = res.AuthorizerId!;
      expect(authorizerId).toBeTruthy();
      expect(res.Name).toBe('test-jwt-auth');
    });

    it('should get the authorizer', async () => {
      const res = await gw.send(new GetAuthorizerCommand({ ApiId: apiId, AuthorizerId: authorizerId }));
      expect(res.AuthorizerId).toBe(authorizerId);
      expect(res.AuthorizerType).toBe('JWT');
    });

    it('should list authorizers', async () => {
      const res = await gw.send(new GetAuthorizersCommand({ ApiId: apiId }));
      expect(res.Items!.some(a => a.AuthorizerId === authorizerId)).toBe(true);
    });

    it('should update an authorizer preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateAuthorizerCommand({
        ApiId: apiId,
        AuthorizerId: authorizerId,
        Name: 'updated-auth',
      }));
      expect(res.Name).toBe('updated-auth');
      expect(res.AuthorizerType).toBe('JWT');
    });

    it('should delete the authorizer', async () => {
      await gw.send(new DeleteAuthorizerCommand({ ApiId: apiId, AuthorizerId: authorizerId }));
      await expect(gw.send(new GetAuthorizerCommand({ ApiId: apiId, AuthorizerId: authorizerId }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Stages & Deployments ────────────────────────────

  describe('Stages & Deployments', () => {
    let apiId: string;
    let deploymentId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('stage-test'),
        ProtocolType: 'HTTP',
      }));
      apiId = res.ApiId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteStageCommand({ ApiId: apiId, StageName: 'dev' })); } catch { /* ignore */ }
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a deployment', async () => {
      const res = await gw.send(new CreateDeploymentCommand({
        ApiId: apiId,
        Description: 'initial',
      }));
      deploymentId = res.DeploymentId!;
      expect(deploymentId).toBeTruthy();
    });

    it('should get the deployment', async () => {
      const res = await gw.send(new GetDeploymentCommand({ ApiId: apiId, DeploymentId: deploymentId }));
      expect(res.DeploymentId).toBe(deploymentId);
    });

    it('should list deployments', async () => {
      const res = await gw.send(new GetDeploymentsCommand({ ApiId: apiId }));
      expect(res.Items!.some(d => d.DeploymentId === deploymentId)).toBe(true);
    });

    it('should update a deployment', async () => {
      const res = await gw.send(new UpdateDeploymentCommand({
        ApiId: apiId,
        DeploymentId: deploymentId,
        Description: 'updated',
      }));
      expect(res.Description).toBe('updated');
    });

    it('should create a stage', async () => {
      const res = await gw.send(new CreateStageCommand({
        ApiId: apiId,
        StageName: 'dev',
        DeploymentId: deploymentId,
        AutoDeploy: false,
      }));
      expect(res.StageName).toBe('dev');
      expect(res.DeploymentId).toBe(deploymentId);
    });

    it('should get the stage', async () => {
      const res = await gw.send(new GetStageCommand({ ApiId: apiId, StageName: 'dev' }));
      expect(res.StageName).toBe('dev');
    });

    it('should list stages', async () => {
      const res = await gw.send(new GetStagesCommand({ ApiId: apiId }));
      expect(res.Items!.some(s => s.StageName === 'dev')).toBe(true);
    });

    it('should update a stage preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateStageCommand({
        ApiId: apiId,
        StageName: 'dev',
        AutoDeploy: true,
      }));
      expect(res.AutoDeploy).toBe(true);
      expect(res.DeploymentId).toBe(deploymentId);
    });

    it('should delete the deployment', async () => {
      await gw.send(new DeleteDeploymentCommand({ ApiId: apiId, DeploymentId: deploymentId }));
      await expect(gw.send(new GetDeploymentCommand({ ApiId: apiId, DeploymentId: deploymentId }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Route Responses ────────────────────────────

  describe('Route Responses', () => {
    let apiId: string;
    let routeId: string;
    let routeResponseId: string;

    beforeAll(async () => {
      const api = await gw.send(new CreateApiCommand({
        Name: uniqueName('rr-test'),
        ProtocolType: 'WEBSOCKET',
        RouteSelectionExpression: '$request.body.action',
      }));
      apiId = api.ApiId!;
      const route = await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        RouteResponseSelectionExpression: '$default',
      }));
      routeId = route.RouteId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a route response', async () => {
      const res = await gw.send(new CreateRouteResponseCommand({
        ApiId: apiId,
        RouteId: routeId,
        RouteResponseKey: '$default',
        ModelSelectionExpression: '$default',
      }));
      routeResponseId = res.RouteResponseId!;
      expect(routeResponseId).toBeTruthy();
      expect(res.RouteResponseKey).toBe('$default');
    });

    it('should get the route response', async () => {
      const res = await gw.send(new GetRouteResponseCommand({
        ApiId: apiId, RouteId: routeId, RouteResponseId: routeResponseId,
      }));
      expect(res.RouteResponseId).toBe(routeResponseId);
      expect(res.RouteResponseKey).toBe('$default');
    });

    it('should list route responses', async () => {
      const res = await gw.send(new GetRouteResponsesCommand({ ApiId: apiId, RouteId: routeId }));
      expect(res.Items!.some(rr => rr.RouteResponseId === routeResponseId)).toBe(true);
    });

    it('should update a route response', async () => {
      const res = await gw.send(new UpdateRouteResponseCommand({
        ApiId: apiId,
        RouteId: routeId,
        RouteResponseId: routeResponseId,
        RouteResponseKey: '$updated',
      }));
      expect(res.RouteResponseKey).toBe('$updated');
    });

    it('should delete the route response', async () => {
      await gw.send(new DeleteRouteResponseCommand({
        ApiId: apiId, RouteId: routeId, RouteResponseId: routeResponseId,
      }));
      await expect(gw.send(new GetRouteResponseCommand({
        ApiId: apiId, RouteId: routeId, RouteResponseId: routeResponseId,
      }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Models ────────────────────────────

  describe('Models', () => {
    let apiId: string;
    let modelId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('model-test'),
        ProtocolType: 'HTTP',
      }));
      apiId = res.ApiId!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a model', async () => {
      const res = await gw.send(new CreateModelCommand({
        ApiId: apiId,
        Name: 'PetModel',
        Schema: '{"$schema":"http://json-schema.org/draft-04/schema#","title":"Pet","type":"object"}',
        ContentType: 'application/json',
        Description: 'A pet schema',
      }));
      modelId = res.ModelId!;
      expect(modelId).toBeTruthy();
      expect(res.Name).toBe('PetModel');
    });

    it('should get the model', async () => {
      const res = await gw.send(new GetModelCommand({ ApiId: apiId, ModelId: modelId }));
      expect(res.ModelId).toBe(modelId);
      expect(res.Name).toBe('PetModel');
      expect(res.ContentType).toBe('application/json');
    });

    it('should list models', async () => {
      const res = await gw.send(new GetModelsCommand({ ApiId: apiId }));
      expect(res.Items!.some(m => m.ModelId === modelId)).toBe(true);
    });

    it('should update a model preserving unmodified fields', async () => {
      const res = await gw.send(new UpdateModelCommand({
        ApiId: apiId,
        ModelId: modelId,
        Description: 'updated description',
      }));
      expect(res.Description).toBe('updated description');
      expect(res.Name).toBe('PetModel');
      expect(res.ContentType).toBe('application/json');
    });

    it('should delete the model', async () => {
      await gw.send(new DeleteModelCommand({ ApiId: apiId, ModelId: modelId }));
      await expect(gw.send(new GetModelCommand({ ApiId: apiId, ModelId: modelId }))).rejects.toThrow();
    });
  });

  // ──────────────────────────── Tagging ────────────────────────────

  describe('Tagging', () => {
    let apiId: string;
    let apiArn: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateApiCommand({
        Name: uniqueName('tag-test'),
        ProtocolType: 'HTTP',
        Tags: { initial: 'tag' },
      }));
      apiId = res.ApiId!;
      apiArn = `arn:aws:apigateway:${REGION}::/apis/${apiId}`;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create API with tags', async () => {
      const res = await gw.send(new GetApiCommand({ ApiId: apiId }));
      expect(res.Tags?.initial).toBe('tag');
    });

    it('should add tags via TagResource', async () => {
      await gw.send(new TagResourceCommand({
        ResourceArn: apiArn,
        Tags: { env: 'production', team: 'platform' },
      }));
      const res = await gw.send(new GetTagsCommand({ ResourceArn: apiArn }));
      expect(res.Tags?.env).toBe('production');
      expect(res.Tags?.team).toBe('platform');
      expect(res.Tags?.initial).toBe('tag');
    });

    it('should remove tags via UntagResource', async () => {
      await gw.send(new UntagResourceCommand({
        ResourceArn: apiArn,
        TagKeys: ['initial', 'team'],
      }));
      const res = await gw.send(new GetTagsCommand({ ResourceArn: apiArn }));
      expect(res.Tags?.env).toBe('production');
      expect(res.Tags?.initial).toBeUndefined();
      expect(res.Tags?.team).toBeUndefined();
    });

    it('should return empty tags for untagged API', async () => {
      const fresh = await gw.send(new CreateApiCommand({
        Name: uniqueName('no-tags'),
        ProtocolType: 'HTTP',
      }));
      const freshArn = `arn:aws:apigateway:${REGION}::/apis/${fresh.ApiId}`;
      const res = await gw.send(new GetTagsCommand({ ResourceArn: freshArn }));
      expect(Object.keys(res.Tags ?? {}).length).toBe(0);
      await gw.send(new DeleteApiCommand({ ApiId: fresh.ApiId! }));
    });
  });

  // ──────────────────────────── Not-found errors ────────────────────────────

  describe('Not-found errors', () => {
    it('should 404 on GetApi with non-existent ID', async () => {
      await expect(gw.send(new GetApiCommand({ ApiId: 'nonexistent999' }))).rejects.toThrow();
    });

    it('should 404 on UpdateApi with non-existent ID', async () => {
      await expect(gw.send(new UpdateApiCommand({
        ApiId: 'nonexistent999',
        Name: 'ghost',
      }))).rejects.toThrow();
    });
  });
});

/**
 * API Gateway v1 (REST API) compatibility tests.
 *
 * Covers management-plane CRUD (RestApis, Resources, Methods, Integrations,
 * Authorizers, Deployments, Stages) and execute-api data-plane behaviour,
 * including the REQUEST authorizer full-event shape.
 *
 * Uses @aws-sdk/client-api-gateway for management-plane calls and the native
 * fetch API for execute-api invocations (which are plain HTTP, not SDK calls).
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  APIGatewayClient,
  CreateRestApiCommand,
  GetRestApiCommand,
  GetRestApisCommand,
  DeleteRestApiCommand,
  GetResourcesCommand,
  CreateAuthorizerCommand,
  GetAuthorizerCommand,
  GetAuthorizersCommand,
  AuthorizerType,
} from '@aws-sdk/client-api-gateway';
import {
  LambdaClient,
  CreateFunctionCommand,
  DeleteFunctionCommand,
} from '@aws-sdk/client-lambda';
import { makeClient, uniqueName, ENDPOINT, ACCOUNT, REGION, buildMinimalZip } from './setup';

// ──────────────────────────── helpers ────────────────────────────

/** POST a raw JSON body to the Floci management plane (no SDK needed for simple calls). */
async function apigwFetch(path: string, method = 'GET', body?: unknown): Promise<Response> {
  return fetch(`${ENDPOINT}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}

/** Invoke the execute-api data plane and return the raw Response. */
async function executeApi(
  apiId: string,
  stage: string,
  resourcePath: string,
  opts: { method?: string; headers?: Record<string, string>; query?: string } = {}
): Promise<Response> {
  const { method = 'GET', headers = {}, query = '' } = opts;
  const qs = query ? `?${query}` : '';
  return fetch(`${ENDPOINT}/execute-api/${apiId}/${stage}${resourcePath}${qs}`, {
    method,
    headers,
  });
}

/** Create a Node.js Lambda function with the given inline handler source. */
async function createLambda(lambda: LambdaClient, name: string, handlerSrc: string): Promise<void> {
  const zip = buildMinimalZip('index.js', Buffer.from(handlerSrc));
  await lambda.send(new CreateFunctionCommand({
    FunctionName: name,
    Runtime: 'nodejs20.x',
    Role: `arn:aws:iam::${ACCOUNT}:role/lambda-role`,
    Handler: 'index.handler',
    Code: { ZipFile: zip },
    Timeout: 30,
  }));
}

/** Delete a Lambda function, ignoring 404. */
async function deleteLambda(lambda: LambdaClient, name: string): Promise<void> {
  try { await lambda.send(new DeleteFunctionCommand({ FunctionName: name })); } catch { /* ignore */ }
}

// ──────────────────────────── Management-plane CRUD ────────────────────────────

describe('API Gateway v1 — management plane', () => {
  let gw: APIGatewayClient;

  beforeAll(() => {
    gw = makeClient(APIGatewayClient);
  });

  describe('RestApi lifecycle', () => {
    let apiId: string;

    afterAll(async () => {
      try { if (apiId) await gw.send(new DeleteRestApiCommand({ restApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a REST API', async () => {
      const res = await gw.send(new CreateRestApiCommand({ name: uniqueName('rest-api') }));
      apiId = res.id!;
      expect(apiId).toBeTruthy();
      expect(res.name).toContain('rest-api');
    });

    it('should get the REST API', async () => {
      const res = await gw.send(new GetRestApiCommand({ restApiId: apiId }));
      expect(res.id).toBe(apiId);
    });

    it('should list REST APIs including the created one', async () => {
      const res = await gw.send(new GetRestApisCommand({}));
      expect(res.items?.some(a => a.id === apiId)).toBe(true);
    });

    it('should delete the REST API', async () => {
      await gw.send(new DeleteRestApiCommand({ restApiId: apiId }));
      await expect(gw.send(new GetRestApiCommand({ restApiId: apiId }))).rejects.toThrow();
      apiId = '';
    });
  });

  describe('Authorizer CRUD', () => {
    let apiId: string;
    let authorizerId: string;

    beforeAll(async () => {
      const res = await gw.send(new CreateRestApiCommand({ name: uniqueName('auth-crud') }));
      apiId = res.id!;
    });

    afterAll(async () => {
      try { await gw.send(new DeleteRestApiCommand({ restApiId: apiId })); } catch { /* ignore */ }
    });

    it('should create a TOKEN authorizer', async () => {
      const res = await gw.send(new CreateAuthorizerCommand({
        restApiId: apiId,
        name: 'token-auth',
        type: AuthorizerType.TOKEN,
        authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:dummy/invocations`,
        identitySource: 'method.request.header.Authorization',
        authorizerResultTtlInSeconds: 0,
      }));
      authorizerId = res.id!;
      expect(authorizerId).toBeTruthy();
      expect(res.type).toBe(AuthorizerType.TOKEN);
    });

    it('should get the authorizer', async () => {
      const res = await gw.send(new GetAuthorizerCommand({ restApiId: apiId, authorizerId }));
      expect(res.id).toBe(authorizerId);
      expect(res.type).toBe(AuthorizerType.TOKEN);
    });

    it('should list authorizers', async () => {
      const res = await gw.send(new GetAuthorizersCommand({ restApiId: apiId }));
      expect(res.items?.some(a => a.id === authorizerId)).toBe(true);
    });
  });
});

// ──────────────────────────── Execute-API data plane ────────────────────────────

describe('API Gateway v1 — execute-api data plane', () => {
  let gw: APIGatewayClient;
  let lambda: LambdaClient;

  // Shared API wired up in beforeAll
  let apiId: string;
  let itemResourceId: string;
  let secureResourceId: string;
  let openResourceId: string;

  // Lambda function names
  const echoAuthFn = uniqueName('req-auth-echo');
  const tokenAuthFn = uniqueName('tok-auth-echo');
  const denyAuthFn = uniqueName('deny-auth');
  const proxyFn = uniqueName('proxy-echo');
  const simpleFn = uniqueName('simple-fn');

  beforeAll(async () => {
    gw = makeClient(APIGatewayClient);
    lambda = makeClient(LambdaClient);

    // ── Lambda: REQUEST authorizer — echoes received event into policy context ──
    await createLambda(lambda, echoAuthFn, `
      exports.handler = async (event) => ({
        principalId: 'user',
        policyDocument: {
          Version: '2012-10-17',
          Statement: [{ Action: 'execute-api:Invoke', Effect: 'Allow', Resource: event.methodArn }]
        },
        context: { receivedEvent: JSON.stringify(event) }
      });
    `);

    // ── Lambda: TOKEN authorizer — echoes received event into policy context ──
    await createLambda(lambda, tokenAuthFn, `
      exports.handler = async (event) => ({
        principalId: 'user',
        policyDocument: {
          Version: '2012-10-17',
          Statement: [{ Action: 'execute-api:Invoke', Effect: 'Allow', Resource: event.methodArn }]
        },
        context: { receivedEvent: JSON.stringify(event) }
      });
    `);

    // ── Lambda: REQUEST authorizer — always Deny ──
    await createLambda(lambda, denyAuthFn, `
      exports.handler = async (event) => ({
        principalId: 'user',
        policyDocument: {
          Version: '2012-10-17',
          Statement: [{ Action: 'execute-api:Invoke', Effect: 'Deny', Resource: event.methodArn }]
        }
      });
    `);

    // ── Lambda: proxy integration — returns authorizer context so tests can inspect it ──
    await createLambda(lambda, proxyFn, `
      exports.handler = async (event) => ({
        statusCode: 200,
        body: JSON.stringify({
          authorizer: event.requestContext?.authorizer ?? null
        })
      });
    `);

    // ── Lambda: simple integration — returns { invoked: true } ──
    await createLambda(lambda, simpleFn, `
      exports.handler = async (event) => ({
        statusCode: 200,
        body: JSON.stringify({ invoked: true })
      });
    `);

    // ── Build the REST API ──
    const api = await gw.send(new CreateRestApiCommand({ name: uniqueName('exec-api-test') }));
    apiId = api.id!;

    const resources = await gw.send(new GetResourcesCommand({ restApiId: apiId }));
    const rootId = resources.items![0].id!;

    // /items
    const itemsRes = await apigwFetch(`/restapis/${apiId}/resources/${rootId}`, 'POST', { pathPart: 'items' });
    const itemsId = (await itemsRes.json() as { id: string }).id;

    // /items/{id}
    const itemRes = await apigwFetch(`/restapis/${apiId}/resources/${itemsId}`, 'POST', { pathPart: '{id}' });
    itemResourceId = (await itemRes.json() as { id: string }).id;

    // /secure  (TOKEN authorizer)
    const secureRes = await apigwFetch(`/restapis/${apiId}/resources/${rootId}`, 'POST', { pathPart: 'secure' });
    secureResourceId = (await secureRes.json() as { id: string }).id;

    // /open  (NONE authorizer)
    const openRes = await apigwFetch(`/restapis/${apiId}/resources/${rootId}`, 'POST', { pathPart: 'open' });
    openResourceId = (await openRes.json() as { id: string }).id;

    // ── REQUEST authorizer ──
    const reqAuthRes = await apigwFetch(`/restapis/${apiId}/authorizers`, 'POST', {
      name: 'req-echo-auth',
      type: 'REQUEST',
      authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${echoAuthFn}/invocations`,
      identitySource: 'method.request.header.Authorization',
      authorizerResultTtlInSeconds: 0,
    });
    const reqAuthId = (await reqAuthRes.json() as { id: string }).id;

    // ── TOKEN authorizer ──
    const tokAuthRes = await apigwFetch(`/restapis/${apiId}/authorizers`, 'POST', {
      name: 'tok-echo-auth',
      type: 'TOKEN',
      authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${tokenAuthFn}/invocations`,
      identitySource: 'method.request.header.Authorization',
      authorizerResultTtlInSeconds: 0,
    });
    const tokAuthId = (await tokAuthRes.json() as { id: string }).id;

    const proxyUri = `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${proxyFn}/invocations`;
    const simpleUri = `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${simpleFn}/invocations`;

    // GET /items/{id}  — REQUEST authorizer + proxy integration
    await apigwFetch(`/restapis/${apiId}/resources/${itemResourceId}/methods/GET`, 'PUT', {
      authorizationType: 'CUSTOM',
      authorizerId: reqAuthId,
    });
    await apigwFetch(`/restapis/${apiId}/resources/${itemResourceId}/methods/GET/integration`, 'PUT', {
      type: 'AWS_PROXY',
      httpMethod: 'POST',
      uri: proxyUri,
    });

    // GET /secure  — TOKEN authorizer + proxy integration
    await apigwFetch(`/restapis/${apiId}/resources/${secureResourceId}/methods/GET`, 'PUT', {
      authorizationType: 'CUSTOM',
      authorizerId: tokAuthId,
    });
    await apigwFetch(`/restapis/${apiId}/resources/${secureResourceId}/methods/GET/integration`, 'PUT', {
      type: 'AWS_PROXY',
      httpMethod: 'POST',
      uri: proxyUri,
    });

    // GET /open  — NONE authorizer + simple integration
    await apigwFetch(`/restapis/${apiId}/resources/${openResourceId}/methods/GET`, 'PUT', {
      authorizationType: 'NONE',
    });
    await apigwFetch(`/restapis/${apiId}/resources/${openResourceId}/methods/GET/integration`, 'PUT', {
      type: 'AWS_PROXY',
      httpMethod: 'POST',
      uri: simpleUri,
    });

    // ── Deploy ──
    const depRes = await apigwFetch(`/restapis/${apiId}/deployments`, 'POST', { description: 'compat-test' });
    const depId = (await depRes.json() as { id: string }).id;
    await apigwFetch(`/restapis/${apiId}/stages`, 'POST', { stageName: 'prod', deploymentId: depId });
  });

  afterAll(async () => {
    try { if (apiId) await gw.send(new DeleteRestApiCommand({ restApiId: apiId })); } catch { /* ignore */ }
    await deleteLambda(lambda, echoAuthFn);
    await deleteLambda(lambda, tokenAuthFn);
    await deleteLambda(lambda, denyAuthFn);
    await deleteLambda(lambda, proxyFn);
    await deleteLambda(lambda, simpleFn);
  });

  // ──────────────────────────── REQUEST authorizer event shape ────────────────────────────

  describe('REQUEST authorizer — full event shape', () => {
    let receivedEvent: Record<string, unknown>;

    beforeAll(async () => {
      const res = await executeApi(apiId, 'prod', '/items/42', {
        headers: { Authorization: 'Bearer test-token', 'X-Custom-Header': 'hello' },
        query: 'foo=bar&baz=qux',
      });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      receivedEvent = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
    });

    it('should include type=REQUEST and methodArn', () => {
      expect(receivedEvent.type).toBe('REQUEST');
      expect(typeof receivedEvent.methodArn).toBe('string');
      expect(receivedEvent.methodArn as string).toContain(apiId);
    });

    it('should include the matched resource path template', () => {
      expect(receivedEvent.resource).toBe('/items/{id}');
    });

    it('should include the actual request path', () => {
      expect(receivedEvent.path).toBe('/items/42');
    });

    it('should include the HTTP method', () => {
      expect(receivedEvent.httpMethod).toBe('GET');
    });

    it('should include a non-null headers map', () => {
      expect(receivedEvent.headers).toBeTruthy();
      expect(typeof receivedEvent.headers).toBe('object');
      const headers = receivedEvent.headers as Record<string, string>;
      expect(headers['Authorization']).toBe('Bearer test-token');
      expect(headers['X-Custom-Header']).toBe('hello');
    });

    it('should include a multiValueHeaders map', () => {
      expect(receivedEvent.multiValueHeaders).toBeTruthy();
      const mvh = receivedEvent.multiValueHeaders as Record<string, string[]>;
      expect(Array.isArray(mvh['Authorization'])).toBe(true);
      expect(mvh['Authorization'][0]).toBe('Bearer test-token');
    });

    it('should include queryStringParameters with all query params', () => {
      expect(receivedEvent.queryStringParameters).toBeTruthy();
      const qsp = receivedEvent.queryStringParameters as Record<string, string>;
      expect(qsp.foo).toBe('bar');
      expect(qsp.baz).toBe('qux');
    });

    it('should include multiValueQueryStringParameters', () => {
      expect(receivedEvent.multiValueQueryStringParameters).toBeTruthy();
      const mqsp = receivedEvent.multiValueQueryStringParameters as Record<string, string[]>;
      expect(Array.isArray(mqsp.foo)).toBe(true);
      expect(mqsp.foo[0]).toBe('bar');
    });

    it('should include pathParameters with the id segment', () => {
      expect(receivedEvent.pathParameters).toBeTruthy();
      const pp = receivedEvent.pathParameters as Record<string, string>;
      expect(pp.id).toBe('42');
    });

    it('should include stageVariables as null', () => {
      expect(receivedEvent.stageVariables).toBeNull();
    });

    it('should include a requestContext with requestId', () => {
      expect(receivedEvent.requestContext).toBeTruthy();
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(typeof ctx.requestId).toBe('string');
      expect((ctx.requestId as string).length).toBeGreaterThan(0);
    });

    it('should include requestContext.identity.sourceIp', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      const identity = ctx.identity as Record<string, string>;
      expect(identity.sourceIp).toBe('127.0.0.1');
    });

    it('should include requestContext.resourcePath', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(ctx.resourcePath).toBe('/items/{id}');
    });

    it('should include requestContext.httpMethod', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(ctx.httpMethod).toBe('GET');
    });

    it('should include requestContext.stage', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(ctx.stage).toBe('prod');
    });

    it('should include requestContext.accountId', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(typeof ctx.accountId).toBe('string');
      expect((ctx.accountId as string).length).toBeGreaterThan(0);
    });

    it('should include requestContext.apiId', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(ctx.apiId).toBe(apiId);
    });

    it('should include requestContext.resourceId as a non-empty string', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(typeof ctx.resourceId).toBe('string');
      expect((ctx.resourceId as string).length).toBeGreaterThan(0);
    });

    it('should include requestContext.path equal to the actual request path', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      expect(ctx.path).toBe('/items/42');
    });

    it('should include requestContext.identity.apiKey as null when no usage plan key matches', () => {
      const ctx = receivedEvent.requestContext as Record<string, unknown>;
      const identity = ctx.identity as Record<string, unknown>;
      expect(Object.prototype.hasOwnProperty.call(identity, 'apiKey')).toBe(true);
      expect(identity.apiKey).toBeNull();
    });
  });

  describe('REQUEST authorizer — no query params', () => {
    it('should set queryStringParameters to null when no query string is present', async () => {
      const res = await executeApi(apiId, 'prod', '/items/99', {
        headers: { Authorization: 'Bearer test' },
      });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      const event = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
      expect(event.queryStringParameters).toBeNull();
      expect(event.multiValueQueryStringParameters).toBeNull();
    });
  });

  // ──────────────────────────── TOKEN authorizer preservation ────────────────────────────

  describe('TOKEN authorizer — event shape preserved', () => {
    let receivedEvent: Record<string, unknown>;

    beforeAll(async () => {
      const res = await executeApi(apiId, 'prod', '/secure', {
        headers: { Authorization: 'Bearer my-secret-token' },
      });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      receivedEvent = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
    });

    it('should include type=TOKEN', () => {
      expect(receivedEvent.type).toBe('TOKEN');
    });

    it('should include methodArn', () => {
      expect(typeof receivedEvent.methodArn).toBe('string');
      expect((receivedEvent.methodArn as string).length).toBeGreaterThan(0);
    });

    it('should include authorizationToken equal to the Authorization header value', () => {
      expect(receivedEvent.authorizationToken).toBe('Bearer my-secret-token');
    });

    it('should NOT include headers, queryStringParameters, or requestContext', () => {
      // TOKEN authorizer event must contain exactly type, methodArn, authorizationToken
      expect(receivedEvent.headers == null).toBe(true);
      expect(receivedEvent.queryStringParameters == null).toBe(true);
      expect(receivedEvent.requestContext == null).toBe(true);
    });
  });

  // ──────────────────────────── NONE authorizer preservation ────────────────────────────

  describe('NONE authorizer — skips authorizer invocation', () => {
    it('should invoke the integration directly without an Authorization header', async () => {
      const res = await executeApi(apiId, 'prod', '/open');
      expect(res.status).toBe(200);
      const body = await res.json() as { invoked: boolean };
      expect(body.invoked).toBe(true);
    });
  });

  // ──────────────────────────── Allow / Deny policy ────────────────────────────

  describe('REQUEST authorizer — Allow policy forwards to integration', () => {
    it('should return HTTP 200 when the authorizer returns Allow', async () => {
      const res = await executeApi(apiId, 'prod', '/items/1', {
        headers: { Authorization: 'Bearer allow-me' },
      });
      expect(res.status).toBe(200);
    });
  });

  describe('REQUEST authorizer — Deny policy returns 403', () => {
    let denyApiId: string;

    beforeAll(async () => {
      // Build a separate API with only the deny authorizer to avoid interfering with other tests
      const api = await gw.send(new CreateRestApiCommand({ name: uniqueName('deny-api') }));
      denyApiId = api.id!;

      const resources = await gw.send(new GetResourcesCommand({ restApiId: denyApiId }));
      const rootId = resources.items![0].id!;

      const protectedRes = await apigwFetch(`/restapis/${denyApiId}/resources/${rootId}`, 'POST', { pathPart: 'protected' });
      const protectedId = (await protectedRes.json() as { id: string }).id;

      const denyAuthRes = await apigwFetch(`/restapis/${denyApiId}/authorizers`, 'POST', {
        name: 'deny-auth',
        type: 'REQUEST',
        authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${denyAuthFn}/invocations`,
        identitySource: 'method.request.header.Authorization',
        authorizerResultTtlInSeconds: 0,
      });
      const denyAuthId = (await denyAuthRes.json() as { id: string }).id;

      await apigwFetch(`/restapis/${denyApiId}/resources/${protectedId}/methods/GET`, 'PUT', {
        authorizationType: 'CUSTOM',
        authorizerId: denyAuthId,
      });
      await apigwFetch(`/restapis/${denyApiId}/resources/${protectedId}/methods/GET/integration`, 'PUT', {
        type: 'MOCK',
        requestTemplates: { 'application/json': '{"statusCode": 200}' },
      });
      await apigwFetch(`/restapis/${denyApiId}/resources/${protectedId}/methods/GET/responses/200`, 'PUT', {
        responseParameters: {},
      });
      await apigwFetch(`/restapis/${denyApiId}/resources/${protectedId}/methods/GET/integration/responses/200`, 'PUT', {
        selectionPattern: '',
        responseTemplates: { 'application/json': '{"message":"ok"}' },
      });

      const depRes = await apigwFetch(`/restapis/${denyApiId}/deployments`, 'POST', { description: 'deny-test' });
      const depId = (await depRes.json() as { id: string }).id;
      await apigwFetch(`/restapis/${denyApiId}/stages`, 'POST', { stageName: 'prod', deploymentId: depId });
    });

    afterAll(async () => {
      try { if (denyApiId) await gw.send(new DeleteRestApiCommand({ restApiId: denyApiId })); } catch { /* ignore */ }
    });

    it('should return HTTP 403 when the authorizer returns Deny', async () => {
      const res = await executeApi(denyApiId, 'prod', '/protected', {
        headers: { Authorization: 'Bearer any-token' },
      });
      expect(res.status).toBe(403);
    });
  });

  // ──────────────────────────── Stage Variables ────────────────────────────

  describe('Stage variables — populated in authorizer and proxy events', () => {
    let svApiId: string;

    beforeAll(async () => {
      const svAuthName = uniqueName('sv-auth');
      const svProxyName = uniqueName('sv-proxy');
      await createLambda(lambda, svAuthName, `
        exports.handler = async (event) => ({
          principalId: 'user',
          policyDocument: { Version: '2012-10-17', Statement: [{ Action: 'execute-api:Invoke', Effect: 'Allow', Resource: event.methodArn }] },
          context: { receivedEvent: JSON.stringify(event) }
        });
      `);
      await createLambda(lambda, svProxyName, `
        exports.handler = async (event) => ({
          statusCode: 200,
          body: JSON.stringify({ stageVariables: event.stageVariables, authorizer: event.requestContext?.authorizer ?? null })
        });
      `);

      const api = await gw.send(new CreateRestApiCommand({ name: uniqueName('sv-api') }));
      svApiId = api.id!;
      const resources = await gw.send(new GetResourcesCommand({ restApiId: svApiId }));
      const rootId = resources.items![0].id!;
      const pingRes = await apigwFetch(`/restapis/${svApiId}/resources/${rootId}`, 'POST', { pathPart: 'ping' });
      const pingId = (await pingRes.json() as { id: string }).id;

      const authRes = await apigwFetch(`/restapis/${svApiId}/authorizers`, 'POST', {
        name: 'sv-auth', type: 'REQUEST',
        authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${svAuthName}/invocations`,
        identitySource: 'method.request.header.Authorization', authorizerResultTtlInSeconds: 0,
      });
      const svAuthId = (await authRes.json() as { id: string }).id;

      await apigwFetch(`/restapis/${svApiId}/resources/${pingId}/methods/GET`, 'PUT', { authorizationType: 'CUSTOM', authorizerId: svAuthId });
      await apigwFetch(`/restapis/${svApiId}/resources/${pingId}/methods/GET/integration`, 'PUT', {
        type: 'AWS_PROXY', httpMethod: 'POST',
        uri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${svProxyName}/invocations`,
      });

      const depRes = await apigwFetch(`/restapis/${svApiId}/deployments`, 'POST', { description: 'sv-test' });
      const depId = (await depRes.json() as { id: string }).id;
      // Create stage WITH variables
      await apigwFetch(`/restapis/${svApiId}/stages`, 'POST', {
        stageName: 'prod', deploymentId: depId,
        variables: { env: 'test', version: 'v1' },
      });
    });

    afterAll(async () => {
      try { if (svApiId) await gw.send(new DeleteRestApiCommand({ restApiId: svApiId })); } catch { /* ignore */ }
    });

    it('should populate stageVariables in the proxy event from the stage configuration', async () => {
      const res = await executeApi(svApiId, 'prod', '/ping', { headers: { Authorization: 'Bearer t' } });
      expect(res.status).toBe(200);
      const body = await res.json() as { stageVariables: Record<string, string>; authorizer: { receivedEvent: string } };
      expect(body.stageVariables).toBeTruthy();
      expect(body.stageVariables.env).toBe('test');
      expect(body.stageVariables.version).toBe('v1');
    });

    it('should populate stageVariables in the REQUEST authorizer event', async () => {
      const res = await executeApi(svApiId, 'prod', '/ping', { headers: { Authorization: 'Bearer t' } });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      const event = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
      const sv = event.stageVariables as Record<string, string>;
      expect(sv).toBeTruthy();
      expect(sv.env).toBe('test');
      expect(sv.version).toBe('v1');
    });
  });

  // ──────────────────────────── API Key Resolution ────────────────────────────

  describe('API key — identity.apiKey resolved from usage plan', () => {
    let akApiId: string;
    const testKeyValue = uniqueName('ak');

    beforeAll(async () => {
      const akAuthName = uniqueName('ak-auth');
      const akProxyName = uniqueName('ak-proxy');
      await createLambda(lambda, akAuthName, `
        exports.handler = async (event) => ({
          principalId: 'user',
          policyDocument: { Version: '2012-10-17', Statement: [{ Action: 'execute-api:Invoke', Effect: 'Allow', Resource: event.methodArn }] },
          context: { receivedEvent: JSON.stringify(event) }
        });
      `);
      await createLambda(lambda, akProxyName, `
        exports.handler = async (event) => ({
          statusCode: 200,
          body: JSON.stringify({ authorizer: event.requestContext?.authorizer ?? null })
        });
      `);

      const api = await gw.send(new CreateRestApiCommand({ name: uniqueName('ak-api') }));
      akApiId = api.id!;
      const resources = await gw.send(new GetResourcesCommand({ restApiId: akApiId }));
      const rootId = resources.items![0].id!;
      const secRes = await apigwFetch(`/restapis/${akApiId}/resources/${rootId}`, 'POST', { pathPart: 'secure' });
      const secId = (await secRes.json() as { id: string }).id;

      const authRes = await apigwFetch(`/restapis/${akApiId}/authorizers`, 'POST', {
        name: 'ak-auth', type: 'REQUEST',
        authorizerUri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${akAuthName}/invocations`,
        identitySource: 'method.request.header.Authorization', authorizerResultTtlInSeconds: 0,
      });
      const akAuthId = (await authRes.json() as { id: string }).id;

      await apigwFetch(`/restapis/${akApiId}/resources/${secId}/methods/GET`, 'PUT', { authorizationType: 'CUSTOM', authorizerId: akAuthId });
      await apigwFetch(`/restapis/${akApiId}/resources/${secId}/methods/GET/integration`, 'PUT', {
        type: 'AWS_PROXY', httpMethod: 'POST',
        uri: `arn:aws:apigateway:${REGION}:lambda:path/2015-03-31/functions/arn:aws:lambda:${REGION}:${ACCOUNT}:function:${akProxyName}/invocations`,
      });

      const depRes = await apigwFetch(`/restapis/${akApiId}/deployments`, 'POST', { description: 'ak-test' });
      const depId = (await depRes.json() as { id: string }).id;
      await apigwFetch(`/restapis/${akApiId}/stages`, 'POST', { stageName: 'prod', deploymentId: depId });

      // Create API key + usage plan linked to (akApiId, prod)
      const keyRes = await apigwFetch('/apikeys', 'POST', { name: uniqueName('key'), value: testKeyValue, enabled: true });
      const keyId = (await keyRes.json() as { id: string }).id;
      const planRes = await apigwFetch('/usageplans', 'POST', { name: uniqueName('plan'), apiStages: [{ apiId: akApiId, stage: 'prod' }] });
      const planId = (await planRes.json() as { id: string }).id;
      await apigwFetch(`/usageplans/${planId}/keys`, 'POST', { keyId, keyType: 'API_KEY' });
    });

    afterAll(async () => {
      try { if (akApiId) await gw.send(new DeleteRestApiCommand({ restApiId: akApiId })); } catch { /* ignore */ }
    });

    it('should populate identity.apiKey when x-api-key header matches a usage plan key', async () => {
      const res = await executeApi(akApiId, 'prod', '/secure', {
        headers: { Authorization: 'Bearer t', 'x-api-key': testKeyValue },
      });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      const event = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
      const identity = (event.requestContext as Record<string, unknown>).identity as Record<string, unknown>;
      expect(identity.apiKey).toBe(testKeyValue);
    });

    it('should set identity.apiKey to null when x-api-key header is absent', async () => {
      const res = await executeApi(akApiId, 'prod', '/secure', { headers: { Authorization: 'Bearer t' } });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      const event = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
      const identity = (event.requestContext as Record<string, unknown>).identity as Record<string, unknown>;
      expect(identity.apiKey).toBeNull();
    });

    it('should set identity.apiKey to null when x-api-key does not match any plan key', async () => {
      const res = await executeApi(akApiId, 'prod', '/secure', {
        headers: { Authorization: 'Bearer t', 'x-api-key': 'wrong-key' },
      });
      expect(res.status).toBe(200);
      const body = await res.json() as { authorizer: { receivedEvent: string } };
      const event = JSON.parse(body.authorizer.receivedEvent) as Record<string, unknown>;
      const identity = (event.requestContext as Record<string, unknown>).identity as Record<string, unknown>;
      expect(identity.apiKey).toBeNull();
    });
  });
});

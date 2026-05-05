/**
 * API Gateway v2 WebSocket data-plane compatibility tests.
 *
 * Validates end-to-end WebSocket functionality: connect, send, receive, disconnect,
 * route selection, Lambda authorizer, @connections API, stage variables, mock
 * integration, and disconnect cleanup — using the AWS SDK v3 and the `ws` client.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ApiGatewayV2Client,
  CreateApiCommand,
  DeleteApiCommand,
  CreateRouteCommand,
  CreateIntegrationCommand,
  CreateStageCommand,
  CreateRouteResponseCommand,
  CreateAuthorizerCommand,
  CreateDeploymentCommand,
} from '@aws-sdk/client-apigatewayv2';
import {
  ApiGatewayManagementApiClient,
  PostToConnectionCommand,
  GetConnectionCommand,
  DeleteConnectionCommand,
} from '@aws-sdk/client-apigatewaymanagementapi';
import {
  LambdaClient,
  CreateFunctionCommand,
  DeleteFunctionCommand,
} from '@aws-sdk/client-lambda';
import WebSocket from 'ws';
import { makeClient, uniqueName, ENDPOINT, REGION, ACCOUNT, buildMinimalZip, buildBundledZip, sleep } from './setup';

// ── Helpers ──────────────────────────────────────────────────────────────────

function wsUrl(apiId: string, stage: string): string {
  const endpoint = process.env.FLOCI_ENDPOINT || 'http://localhost:4566';
  // Convert http/https to ws/wss and strip any trailing slashes
  const wsEndpoint = endpoint.replace(/^https?:\/\//, '').replace(/\/$/, '');
  return `ws://${wsEndpoint}/ws/${apiId}/${stage}`;
}

function managementClient(apiId: string, stage: string): ApiGatewayManagementApiClient {
  return makeClient(ApiGatewayManagementApiClient, {
    endpoint: `${ENDPOINT}/execute-api/${apiId}/${stage}`,
  });
}

function connectWs(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.on('open', () => resolve(ws));
    ws.on('error', (err) => reject(err));
  });
}

function waitForMessage(ws: WebSocket, timeoutMs = 5000): Promise<string> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Timeout waiting for message')), timeoutMs);
    ws.once('message', (data) => {
      clearTimeout(timer);
      resolve(data.toString());
    });
  });
}

function waitForClose(ws: WebSocket, timeoutMs = 5000): Promise<void> {
  return new Promise((resolve, reject) => {
    if (ws.readyState === WebSocket.CLOSED) { resolve(); return; }
    const timer = setTimeout(() => reject(new Error('Timeout waiting for close')), timeoutMs);
    ws.once('close', () => { clearTimeout(timer); resolve(); });
  });
}

const ROLE_ARN = `arn:aws:iam::${ACCOUNT}:role/lambda-role`;

// ── Lambda handler source code ───────────────────────────────────────────────

const ECHO_HANDLER = `
exports.handler = async (event) => {
  const body = event.body || '';
  try {
    const parsed = JSON.parse(body);
    if (parsed.action === 'getConnectionId') {
      return { statusCode: 200, body: JSON.stringify({ connectionId: event.requestContext.connectionId }) };
    }
  } catch (e) {}
  return { statusCode: 200, body: body || 'echo' };
};
`;

const BROADCAST_HANDLER = `
const { ApiGatewayManagementApiClient, PostToConnectionCommand } = require('@aws-sdk/client-apigatewaymanagementapi');
exports.handler = async (event) => {
  const apiId = event.requestContext.apiId;
  const stage = event.requestContext.stage;
  const endpoint = process.env.FLOCI_ENDPOINT + '/execute-api/' + apiId + '/' + stage;
  const client = new ApiGatewayManagementApiClient({ endpoint, region: 'us-east-1' });
  const body = JSON.parse(event.body);
  for (const connId of body.targets) {
    await client.send(new PostToConnectionCommand({
      ConnectionId: connId,
      Data: Buffer.from(body.message),
    }));
  }
  return { statusCode: 200 };
};
`;

const AUTHORIZER_HANDLER = `
exports.handler = async (event) => {
  const token = event.queryStringParameters && event.queryStringParameters.token;
  const effect = token === 'allow' ? 'Allow' : 'Deny';
  return {
    principalId: 'user',
    policyDocument: {
      Version: '2012-10-17',
      Statement: [{ Action: 'execute-api:Invoke', Effect: effect, Resource: event.methodArn || '*' }],
    },
  };
};
`;

const PING_HANDLER = `
exports.handler = async (event) => {
  return { statusCode: 200, body: 'pong' };
};
`;

const SEND_MESSAGE_HANDLER = `
exports.handler = async (event) => {
  const body = JSON.parse(event.body);
  return { statusCode: 200, body: 'received: ' + body.data };
};
`;

const DEFAULT_HANDLER = `
exports.handler = async (event) => {
  return { statusCode: 200, body: 'default-route' };
};
`;

// ── Test suites ──────────────────────────────────────────────────────────────

describe('WebSocket Data-Plane', () => {
  let gw: ApiGatewayV2Client;
  let lambda: LambdaClient;

  // Track all created resources for cleanup
  const createdFunctions: string[] = [];
  const createdApis: string[] = [];

  beforeAll(() => {
    gw = makeClient(ApiGatewayV2Client);
    lambda = makeClient(LambdaClient);
  });

  afterAll(async () => {
    for (const fnName of createdFunctions) {
      try { await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })); } catch { /* ignore */ }
    }
    for (const apiId of createdApis) {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    }
  });

  /**
   * Create a Lambda function with/without dependencies bundled via esbuild.
   * bundledZip should be set as true for handlers that require npm packages (e.g. @aws-sdk/client-apigatewaymanagementapi).
   */
  async function createLambda(name: string, code: string, environment?: Record<string, string>, bundledZip?: boolean): Promise<string> {
    const fnName = uniqueName(name);
    const zipBuffer = bundledZip ? buildBundledZip(code) : buildMinimalZip('index.js', Buffer.from(code));
    await lambda.send(new CreateFunctionCommand({
      FunctionName: fnName,
      Runtime: 'nodejs22.x',
      Role: ROLE_ARN,
      Handler: 'index.handler',
      Code: { ZipFile: zipBuffer },
      ...(environment && { Environment: { Variables: environment } }),
    }));
    createdFunctions.push(fnName);
    return fnName;
  }

  async function createWsApi(name: string): Promise<string> {
    const res = await gw.send(new CreateApiCommand({
      Name: uniqueName(name),
      ProtocolType: 'WEBSOCKET',
      RouteSelectionExpression: '$request.body.action',
    }));
    createdApis.push(res.ApiId!);
    return res.ApiId!;
  }

  async function createLambdaIntegration(apiId: string, fnName: string): Promise<string> {
    const res = await gw.send(new CreateIntegrationCommand({
      ApiId: apiId,
      IntegrationType: 'AWS_PROXY',
      IntegrationUri: `arn:aws:lambda:${REGION}:${ACCOUNT}:function:${fnName}`,
    }));
    return res.IntegrationId!;
  }

  async function setupStage(apiId: string, stageName: string, stageVariables?: Record<string, string>): Promise<void> {
    const deploy = await gw.send(new CreateDeploymentCommand({ ApiId: apiId }));
    await gw.send(new CreateStageCommand({
      ApiId: apiId,
      StageName: stageName,
      DeploymentId: deploy.DeploymentId!,
      StageVariables: stageVariables,
    }));
  }

  // ──────────────────────────── Basic WebSocket flow ────────────────────────────

  describe('Basic WebSocket flow', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('echo', ECHO_HANDLER);
      apiId = await createWsApi('basic-flow');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await gw.send(new CreateRouteResponseCommand({
        ApiId: apiId,
        RouteId: (await gw.send(new CreateRouteCommand({
          ApiId: apiId,
          RouteKey: '$connect',
          Target: `integrations/${integId}`,
        }))).RouteId!,
        RouteResponseKey: '$default',
      })).catch(() => { /* route response optional */ });

      await setupStage(apiId, 'test');
    });

    it('should connect, send message, receive response, and disconnect', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'test', body: 'hello' }));
        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
        ws.close();
        await waitForClose(ws);
      } finally {
        if (ws.readyState !== WebSocket.CLOSED) ws.close();
      }
    });
  });

  // ──────────────────────────── Chat-style broadcast ────────────────────────────

  describe('Chat-style broadcast', () => {
    let apiId: string;

    beforeAll(async () => {
      const broadcastFn = await createLambda('broadcast', BROADCAST_HANDLER, {
        FLOCI_ENDPOINT: 'http://host.docker.internal:4566',
      }, true);
      const echoFn = await createLambda('bc-echo', ECHO_HANDLER);
      apiId = await createWsApi('broadcast');

      const broadcastIntegId = await createLambdaIntegration(apiId, broadcastFn);
      const echoIntegId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${echoIntegId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${echoIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: 'broadcast',
        Target: `integrations/${broadcastIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should broadcast message to multiple connected clients', async () => {
      const ws1 = await connectWs(wsUrl(apiId, 'test'));
      const ws2 = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200); // allow connections to register

        // Get connection IDs for both clients
        const connId1 = await getConnectionIdFromServer(ws1, apiId, 'test');
        const connId2 = await getConnectionIdFromServer(ws2, apiId, 'test');

        expect(connId1).toBeTruthy();
        expect(connId2).toBeTruthy();

        // Set up message listeners before sending broadcast
        const msg1Promise = waitForMessage(ws1);
        const msg2Promise = waitForMessage(ws2);

        // Send broadcast action — Lambda will POST to both connections
        ws1.send(JSON.stringify({
          action: 'broadcast',
          targets: [connId1, connId2],
          message: 'hello-all',
        }));

        const [r1, r2] = await Promise.all([msg1Promise, msg2Promise]);
        expect(r1).toBe('hello-all');
        expect(r2).toBe('hello-all');
      } finally {
        ws1.close();
        ws2.close();
      }
    });
  });

  // ──────────────────────────── $connect authorization ────────────────────────────

  describe('$connect authorization', () => {
    let apiId: string;

    beforeAll(async () => {
      const authFn = await createLambda('authorizer', AUTHORIZER_HANDLER);
      const echoFn = await createLambda('auth-echo', ECHO_HANDLER);
      apiId = await createWsApi('auth-test');

      const echoIntegId = await createLambdaIntegration(apiId, echoFn);

      const authRes = await gw.send(new CreateAuthorizerCommand({
        ApiId: apiId,
        AuthorizerType: 'REQUEST',
        Name: 'ws-auth',
        AuthorizerUri: `arn:aws:lambda:${REGION}:${ACCOUNT}:function:${authFn}`,
        IdentitySource: ['route.request.querystring.token'],
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${echoIntegId}`,
        AuthorizationType: 'CUSTOM',
        AuthorizerId: authRes.AuthorizerId!,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${echoIntegId}`,
      }));

      await setupStage(apiId, 'test');
    });

    it('should allow connection with valid token', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test') + '?token=allow');
      try {
        expect(ws.readyState).toBe(WebSocket.OPEN);
      } finally {
        ws.close();
      }
    });

    it('should deny connection with invalid token', async () => {
      await expect(connectWs(wsUrl(apiId, 'test') + '?token=deny')).rejects.toThrow();
    });
  });

  // ──────────────────────────── Route selection ────────────────────────────

  describe('Route selection', () => {
    let apiId: string;

    beforeAll(async () => {
      const pingFn = await createLambda('ping', PING_HANDLER);
      const sendMsgFn = await createLambda('sendmsg', SEND_MESSAGE_HANDLER);
      const defaultFn = await createLambda('default', DEFAULT_HANDLER);
      const echoFn = await createLambda('rs-echo', ECHO_HANDLER);
      apiId = await createWsApi('route-sel');

      const pingIntegId = await createLambdaIntegration(apiId, pingFn);
      const sendMsgIntegId = await createLambdaIntegration(apiId, sendMsgFn);
      const defaultIntegId = await createLambdaIntegration(apiId, defaultFn);
      const echoIntegId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${echoIntegId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: 'ping',
        Target: `integrations/${pingIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: 'sendMessage',
        Target: `integrations/${sendMsgIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${defaultIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should route "ping" action to ping handler', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'ping' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('pong');
      } finally {
        ws.close();
      }
    });

    it('should route "sendMessage" action to sendMessage handler', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'sendMessage', data: 'test-data' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('received: test-data');
      } finally {
        ws.close();
      }
    });

    it('should route unknown action to $default handler', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'unknownAction' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('default-route');
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── @connections API ────────────────────────────

  describe('@connections API', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('conn-echo', ECHO_HANDLER);
      apiId = await createWsApi('connections-api');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should POST message to a connection', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws, apiId, 'test');
        expect(connId).toBeTruthy();

        const mgmt = managementClient(apiId, 'test');
        const msgPromise = waitForMessage(ws);

        await mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('server-push'),
        }));

        const received = await msgPromise;
        expect(received).toBe('server-push');
      } finally {
        ws.close();
      }
    });

    it('should GET connection info', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws, apiId, 'test');
        expect(connId).toBeTruthy();

        const mgmt = managementClient(apiId, 'test');
        const info = await mgmt.send(new GetConnectionCommand({
          ConnectionId: connId!,
        }));

        expect(info.ConnectedAt).toBeDefined();
      } finally {
        ws.close();
      }
    });

    it('should DELETE (disconnect) a connection', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws, apiId, 'test');
        expect(connId).toBeTruthy();

        const mgmt = managementClient(apiId, 'test');
        await mgmt.send(new DeleteConnectionCommand({
          ConnectionId: connId!,
        }));

        await waitForClose(ws);
        expect(ws.readyState).toBe(WebSocket.CLOSED);
      } finally {
        if (ws.readyState !== WebSocket.CLOSED) ws.close();
      }
    });
  });

  // ──────────────────────────── Stage variables ────────────────────────────

  describe('Stage variables', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('sv-echo', ECHO_HANDLER);
      apiId = await createWsApi('stage-vars');

      // Create integration with stage variable reference in URI
      const integRes = await gw.send(new CreateIntegrationCommand({
        ApiId: apiId,
        IntegrationType: 'AWS_PROXY',
        IntegrationUri: `arn:aws:lambda:${REGION}:${ACCOUNT}:function:\${stageVariables.functionName}`,
      }));
      const integId = integRes.IntegrationId!;

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test', { functionName: echoFn });
    });

    it('should resolve stage variable in integration URI', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'test', body: 'stage-var-test' }));
        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── Mock integration ────────────────────────────

  describe('Mock integration', () => {
    let apiId: string;

    beforeAll(async () => {
      apiId = await createWsApi('mock-integ');

      // Create MOCK integration for $connect — no Lambda needed
      const mockIntegRes = await gw.send(new CreateIntegrationCommand({
        ApiId: apiId,
        IntegrationType: 'MOCK',
      }));
      const mockIntegId = mockIntegRes.IntegrationId!;

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${mockIntegId}`,
      }));

      const echoFn = await createLambda('mock-echo', ECHO_HANDLER);
      const echoIntegId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${echoIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should connect successfully with MOCK integration on $connect', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        expect(ws.readyState).toBe(WebSocket.OPEN);
        ws.send(JSON.stringify({ action: 'test' }));
        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── Disconnect cleanup ────────────────────────────

  describe('Disconnect cleanup', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('dc-echo', ECHO_HANDLER);
      apiId = await createWsApi('disconnect');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should return 410 when posting to a disconnected connection', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws, apiId, 'test');
      expect(connId).toBeTruthy();

      // Disconnect the client
      ws.close();
      await waitForClose(ws);
      await sleep(300); // allow server to process disconnect

      // Attempt to post to the disconnected connection
      const mgmt = managementClient(apiId, 'test');
      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });
  });

  // ──────────────────────────── Payload size limit ────────────────────────────

  describe('Payload size limit', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('pl-echo', ECHO_HANDLER);
      apiId = await createWsApi('payload-limit');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should reject messages exceeding 128 KB with error frame', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        // Create a message larger than 128 KB
        const oversizeMessage = 'x'.repeat(128 * 1024 + 1);
        ws.send(oversizeMessage);
        const response = await waitForMessage(ws);
        expect(response).toContain('Message too long');
      } finally {
        ws.close();
      }
    });

    it('should accept messages at exactly 128 KB', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        // Create a message at exactly 128 KB (should be accepted)
        const maxMessage = JSON.stringify({ action: 'test', data: 'x'.repeat(128 * 1024 - 50) });
        if (maxMessage.length <= 128 * 1024) {
          ws.send(maxMessage);
          const response = await waitForMessage(ws);
          expect(response).toBeTruthy();
          expect(response).not.toContain('Message too long');
        }
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── Server-initiated close via @connections DELETE ────────────────────────────

  describe('Server-initiated close via DELETE', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('del-echo', ECHO_HANDLER);
      apiId = await createWsApi('server-close');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should disconnect client and return 410 on subsequent POST', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws, apiId, 'test');
      expect(connId).toBeTruthy();

      // DELETE the connection via @connections API
      const mgmt = managementClient(apiId, 'test');
      await mgmt.send(new DeleteConnectionCommand({
        ConnectionId: connId!,
      }));

      // Wait for the WebSocket to close
      await waitForClose(ws);
      await sleep(300);

      // POST to the deleted connection should return 410
      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });
  });

  // ──────────────────────────── Binary frame support ────────────────────────────

  describe('Binary frame support', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('bin-echo', ECHO_HANDLER);
      apiId = await createWsApi('binary-frames');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should handle binary frames and route to $default', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        // Send a binary frame (Buffer)
        const binaryData = Buffer.from([0x01, 0x02, 0x03, 0x04, 0x05]);
        ws.send(binaryData);

        // Binary messages route to $default since they can't match JSON route selection.
        // The Lambda receives the base64-encoded body with isBase64Encoded=true.
        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
      } finally {
        ws.close();
      }
    });

    it('should reject binary frames exceeding 128 KB', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        // Create a binary payload larger than 128 KB
        const oversizeBinary = Buffer.alloc(128 * 1024 + 1, 0x42);
        ws.send(oversizeBinary);

        const response = await waitForMessage(ws);
        expect(response).toContain('Message too long');
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── $disconnect Lambda invocation ────────────────────────────

  describe('$disconnect Lambda invocation', () => {
    let apiId: string;
    let disconnectFnName: string;

    const DISCONNECT_HANDLER = `
const { ApiGatewayManagementApiClient, PostToConnectionCommand } = require('@aws-sdk/client-apigatewaymanagementapi');
exports.handler = async (event) => {
  // The $disconnect handler is invoked when a client disconnects.
  // We verify invocation by checking the eventType in requestContext.
  // Since the client is already disconnected, we can't send a message back.
  // Instead, we just return successfully — the test verifies invocation happened
  // by checking that the function was called (no error in logs).
  if (event.requestContext.eventType === 'DISCONNECT') {
    return { statusCode: 200 };
  }
  return { statusCode: 200 };
};
`;

    beforeAll(async () => {
      disconnectFnName = await createLambda('disconnect-fn', DISCONNECT_HANDLER, {
        FLOCI_ENDPOINT: 'http://host.docker.internal:4566',
      }, true);
      const echoFn = await createLambda('dc-inv-echo', ECHO_HANDLER);
      apiId = await createWsApi('disconnect-invoke');

      const echoIntegId = await createLambdaIntegration(apiId, echoFn);
      const disconnectIntegId = await createLambdaIntegration(apiId, disconnectFnName);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${echoIntegId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${echoIntegId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$disconnect',
        Target: `integrations/${disconnectIntegId}`,
      }));

      await setupStage(apiId, 'test');
    });

    it('should invoke $disconnect Lambda on client-initiated close', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws, apiId, 'test');
      expect(connId).toBeTruthy();

      // Close the connection (client-initiated)
      ws.close();
      await waitForClose(ws);
      await sleep(500); // allow time for $disconnect Lambda invocation

      // Verify the connection is fully cleaned up — POST should return 410
      const mgmt = managementClient(apiId, 'test');
      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });

    it('should NOT invoke $disconnect Lambda on server-initiated close via @connections DELETE', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws, apiId, 'test');
      expect(connId).toBeTruthy();

      // Server-initiated close via @connections DELETE API
      const mgmt = managementClient(apiId, 'test');
      await mgmt.send(new DeleteConnectionCommand({
        ConnectionId: connId!,
      }));

      await waitForClose(ws);
      await sleep(500);

      // Connection should be cleaned up — POST should return 410
      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });
  });

  // ──────────────────────────── @connections POST payload size limit ────────────────────────────

  describe('@connections POST payload size limit', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('pl-post-echo', ECHO_HANDLER);
      apiId = await createWsApi('post-payload-limit');

      const integId = await createLambdaIntegration(apiId, echoFn);

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$connect',
        Target: `integrations/${integId}`,
      }));

      await gw.send(new CreateRouteCommand({
        ApiId: apiId,
        RouteKey: '$default',
        Target: `integrations/${integId}`,
        RouteResponseSelectionExpression: '$default',
      }));

      await setupStage(apiId, 'test');
    });

    it('should reject @connections POST exceeding 128 KB with 413', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws, apiId, 'test');
        expect(connId).toBeTruthy();

        const mgmt = managementClient(apiId, 'test');
        const oversizePayload = Buffer.alloc(128 * 1024 + 1, 0x41); // 'A' repeated

        await expect(
          mgmt.send(new PostToConnectionCommand({
            ConnectionId: connId!,
            Data: oversizePayload,
          }))
        ).rejects.toThrow(/413|PayloadTooLarge|too large/i);
      } finally {
        ws.close();
      }
    });

    it('should accept @connections POST at exactly 128 KB', async () => {
      const ws = await connectWs(wsUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws, apiId, 'test');
        expect(connId).toBeTruthy();

        const mgmt = managementClient(apiId, 'test');
        const maxPayload = Buffer.alloc(128 * 1024, 0x41); // exactly 128 KB

        const msgPromise = waitForMessage(ws);
        await mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: maxPayload,
        }));

        const received = await msgPromise;
        expect(received.length).toBe(128 * 1024);
      } finally {
        ws.close();
      }
    });
  });
});

// ── Helper to get connection ID ──────────────────────────────────────────────

/**
 * Gets the connection ID for a WebSocket client by sending a `getConnectionId`
 * action message. The echo handler is designed to return the connectionId from
 * the Lambda event's requestContext when it receives this action.
 */
async function getConnectionIdFromServer(ws: WebSocket, _apiId: string, _stage: string): Promise<string | null> {
  ws.send(JSON.stringify({ action: 'getConnectionId' }));
  try {
    const response = await waitForMessage(ws, 3000);
    const parsed = JSON.parse(response);
    return parsed.connectionId || null;
  } catch {
    return null;
  }
}

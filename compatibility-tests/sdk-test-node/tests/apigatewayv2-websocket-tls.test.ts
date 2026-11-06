/**
 * API Gateway v2 WebSocket over TLS compatibility tests.
 *
 * Validates WebSocket functionality over WSS (TLS): connect, send, receive,
 * disconnect, route selection, @connections API, and server-initiated close —
 * mirroring the plain-text WebSocket tests but using the HTTPS/WSS endpoint.
 *
 * Prerequisites:
 *   - Floci must be started with FLOCI_TLS_ENABLED=true
 *   - NODE_TLS_REJECT_UNAUTHORIZED=0 must be set in the test environment
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ApiGatewayV2Client,
  CreateApiCommand,
  DeleteApiCommand,
  CreateRouteCommand,
  CreateIntegrationCommand,
  CreateStageCommand,
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
import { uniqueName, ENDPOINT, REGION, ACCOUNT, CREDS, buildMinimalZip, buildBundledZip, sleep } from './setup';

// ── TLS Helpers ──────────────────────────────────────────────────────────────

const HTTPS_ENDPOINT = ENDPOINT.replace(/^http:\/\//, 'https://');

const TLS_CLIENT_CONFIG = {
  endpoint: HTTPS_ENDPOINT,
  region: REGION,
  credentials: CREDS,
  forcePathStyle: true,
};

function makeTlsClient<T>(
  ClientClass: new (config: typeof TLS_CLIENT_CONFIG) => T,
  extra: Record<string, unknown> = {},
): T {
  return new ClientClass({ ...TLS_CLIENT_CONFIG, ...extra });
}

function wssUrl(apiId: string, stage: string): string {
  // Convert http endpoint to wss — strip scheme and trailing slashes
  const host = ENDPOINT.replace(/^https?:\/\//, '').replace(/\/$/, '');
  return `wss://${host}/ws/${apiId}/${stage}`;
}

function managementClientTls(apiId: string, stage: string): ApiGatewayManagementApiClient {
  return makeTlsClient(ApiGatewayManagementApiClient, {
    endpoint: `${HTTPS_ENDPOINT}/execute-api/${apiId}/${stage}`,
  });
}

function connectWss(url: string): Promise<WebSocket> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { rejectUnauthorized: false });
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

// ── Helper to get connection ID ──────────────────────────────────────────────

async function getConnectionIdFromServer(ws: WebSocket): Promise<string | null> {
  ws.send(JSON.stringify({ action: 'getConnectionId' }));
  try {
    const response = await waitForMessage(ws, 3000);
    const parsed = JSON.parse(response);
    return parsed.connectionId || null;
  } catch {
    return null;
  }
}

// ── Test suites ──────────────────────────────────────────────────────────────

describe('WebSocket over TLS (WSS)', () => {
  let gw: ApiGatewayV2Client;
  let lambda: LambdaClient;

  const createdFunctions: string[] = [];
  const createdApis: string[] = [];

  beforeAll(() => {
    gw = makeTlsClient(ApiGatewayV2Client);
    lambda = makeTlsClient(LambdaClient);
  });

  afterAll(async () => {
    for (const fnName of createdFunctions) {
      try { await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })); } catch { /* ignore */ }
    }
    for (const apiId of createdApis) {
      try { await gw.send(new DeleteApiCommand({ ApiId: apiId })); } catch { /* ignore */ }
    }
  });

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

  async function setupStage(apiId: string, stageName: string): Promise<void> {
    const deploy = await gw.send(new CreateDeploymentCommand({ ApiId: apiId }));
    await gw.send(new CreateStageCommand({
      ApiId: apiId,
      StageName: stageName,
      DeploymentId: deploy.DeploymentId!,
    }));
  }

  // ──────────────────────────── Basic WSS flow ────────────────────────────

  describe('Basic WSS connection flow', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-basic');

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

    it('should connect over WSS, send message, receive response, and disconnect', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'test', body: 'hello-tls' }));
        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
        ws.close();
        await waitForClose(ws);
      } finally {
        if (ws.readyState !== WebSocket.CLOSED) ws.close();
      }
    });

    it('should establish WSS connection with valid readyState', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        expect(ws.readyState).toBe(WebSocket.OPEN);
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── Chat-style broadcast over WSS ────────────────────────────

  describe('Chat-style broadcast over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const broadcastFn = await createLambda('tls-broadcast', BROADCAST_HANDLER, {
        FLOCI_ENDPOINT: 'https://host.docker.internal:4566',
        NODE_TLS_REJECT_UNAUTHORIZED: '0',
      }, true);
      const echoFn = await createLambda('tls-bc-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-broadcast');

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

    it('should broadcast message to multiple WSS clients', async () => {
      const ws1 = await connectWss(wssUrl(apiId, 'test'));
      const ws2 = await connectWss(wssUrl(apiId, 'test'));
      try {
        await sleep(200);

        const connId1 = await getConnectionIdFromServer(ws1);
        const connId2 = await getConnectionIdFromServer(ws2);

        expect(connId1).toBeTruthy();
        expect(connId2).toBeTruthy();

        const msg1Promise = waitForMessage(ws1);
        const msg2Promise = waitForMessage(ws2);

        ws1.send(JSON.stringify({
          action: 'broadcast',
          targets: [connId1, connId2],
          message: 'hello-all-tls',
        }));

        const [r1, r2] = await Promise.all([msg1Promise, msg2Promise]);
        expect(r1).toBe('hello-all-tls');
        expect(r2).toBe('hello-all-tls');
      } finally {
        ws1.close();
        ws2.close();
      }
    });
  });

  // ──────────────────────────── $connect authorization over WSS ────────────────────────────

  describe('$connect authorization over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const authFn = await createLambda('tls-authorizer', AUTHORIZER_HANDLER);
      const echoFn = await createLambda('tls-auth-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-auth');

      const echoIntegId = await createLambdaIntegration(apiId, echoFn);

      const authRes = await gw.send(new CreateAuthorizerCommand({
        ApiId: apiId,
        AuthorizerType: 'REQUEST',
        Name: 'wss-auth',
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

    it('should allow WSS connection with valid token', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test') + '?token=allow');
      try {
        expect(ws.readyState).toBe(WebSocket.OPEN);
      } finally {
        ws.close();
      }
    });

    it('should deny WSS connection with invalid token', async () => {
      await expect(connectWss(wssUrl(apiId, 'test') + '?token=deny')).rejects.toThrow();
    });
  });

  // ──────────────────────────── Route selection over WSS ────────────────────────────

  describe('Route selection over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const pingFn = await createLambda('tls-ping', PING_HANDLER);
      const sendMsgFn = await createLambda('tls-sendmsg', SEND_MESSAGE_HANDLER);
      const defaultFn = await createLambda('tls-default', DEFAULT_HANDLER);
      const echoFn = await createLambda('tls-rs-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-route-sel');

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

    it('should route "ping" action over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'ping' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('pong');
      } finally {
        ws.close();
      }
    });

    it('should route "sendMessage" action over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'sendMessage', data: 'tls-data' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('received: tls-data');
      } finally {
        ws.close();
      }
    });

    it('should route unknown action to $default over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        ws.send(JSON.stringify({ action: 'unknownAction' }));
        const response = await waitForMessage(ws);
        expect(response).toBe('default-route');
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── @connections API over TLS ────────────────────────────

  describe('@connections API over TLS', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-conn-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-connections');

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

    it('should POST message to a WSS connection via @connections', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws);
        expect(connId).toBeTruthy();

        const mgmt = managementClientTls(apiId, 'test');
        const msgPromise = waitForMessage(ws);

        await mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('tls-server-push'),
        }));

        const received = await msgPromise;
        expect(received).toBe('tls-server-push');
      } finally {
        ws.close();
      }
    });

    it('should GET connection info over TLS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws);
        expect(connId).toBeTruthy();

        const mgmt = managementClientTls(apiId, 'test');
        const info = await mgmt.send(new GetConnectionCommand({
          ConnectionId: connId!,
        }));

        expect(info.ConnectedAt).toBeDefined();
      } finally {
        ws.close();
      }
    });

    it('should DELETE (disconnect) a WSS connection', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        await sleep(200);
        const connId = await getConnectionIdFromServer(ws);
        expect(connId).toBeTruthy();

        const mgmt = managementClientTls(apiId, 'test');
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

  // ──────────────────────────── Disconnect cleanup over WSS ────────────────────────────

  describe('Disconnect cleanup over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-dc-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-disconnect');

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

    it('should return 410 when posting to a disconnected WSS connection', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws);
      expect(connId).toBeTruthy();

      ws.close();
      await waitForClose(ws);
      await sleep(300);

      const mgmt = managementClientTls(apiId, 'test');
      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });
  });

  // ──────────────────────────── Server-initiated close over WSS ────────────────────────────

  describe('Server-initiated close via DELETE over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-del-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-server-close');

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

    it('should disconnect WSS client and return 410 on subsequent POST', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      await sleep(200);
      const connId = await getConnectionIdFromServer(ws);
      expect(connId).toBeTruthy();

      const mgmt = managementClientTls(apiId, 'test');
      await mgmt.send(new DeleteConnectionCommand({
        ConnectionId: connId!,
      }));

      await waitForClose(ws);
      await sleep(300);

      await expect(
        mgmt.send(new PostToConnectionCommand({
          ConnectionId: connId!,
          Data: Buffer.from('should-fail'),
        }))
      ).rejects.toThrow(/410|Gone/);
    });
  });

  // ──────────────────────────── Binary frames over WSS ────────────────────────────

  describe('Binary frame support over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-bin-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-binary');

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

    it('should handle binary frames over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        const binaryData = Buffer.from([0x01, 0x02, 0x03, 0x04, 0x05]);
        ws.send(binaryData);

        const response = await waitForMessage(ws);
        expect(response).toBeTruthy();
      } finally {
        ws.close();
      }
    });
  });

  // ──────────────────────────── Payload size limit over WSS ────────────────────────────

  describe('Payload size limit over WSS', () => {
    let apiId: string;

    beforeAll(async () => {
      const echoFn = await createLambda('tls-pl-echo', ECHO_HANDLER);
      apiId = await createWsApi('tls-payload');

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

    it('should reject messages exceeding 128 KB over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
        const oversizeMessage = 'x'.repeat(128 * 1024 + 1);
        ws.send(oversizeMessage);
        const response = await waitForMessage(ws);
        expect(response).toContain('Message too long');
      } finally {
        ws.close();
      }
    });

    it('should accept messages at exactly 128 KB over WSS', async () => {
      const ws = await connectWss(wssUrl(apiId, 'test'));
      try {
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
});

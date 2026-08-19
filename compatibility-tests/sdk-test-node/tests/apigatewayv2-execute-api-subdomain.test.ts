/**
 * API Gateway v2 execute-api host compatibility tests.
 *
 * Validates AWS-style virtual-host addressing for WebSocket APIs. Floci accepts both a
 * region-bearing host (mirroring AWS's `{apiId}.execute-api.{region}.amazonaws.com`, locally
 * `{apiId}.execute-api.{region}.localhost:4566`) and its regionless built-in suffix
 * (`{apiId}.execute-api.localhost.floci.io:4566`):
 *
 *  - #1846: the `@connections` Management API reaches the API Gateway controller
 *    via the execute-api host instead of being swallowed by the S3 controller.
 *  - #1871: a WebSocket `$connect` addressed via the execute-api host resolves an API
 *    created in a non-default region (from the host region label when present, else a
 *    cross-region apiId lookup) so it no longer returns 403.
 *  - The regionless built-in suffix must NOT be mis-parsed as region `localhost`
 *    (the defect that consolidating onto `ApiGatewayExecuteApiHostFilter` fixes).
 *
 * Routing is exercised deterministically by overriding the `Host` header to the
 * execute-api form while the connection still targets the real test endpoint — this
 * avoids depending on wildcard `*.localhost` DNS resolution.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ApiGatewayV2Client,
  CreateApiCommand,
  DeleteApiCommand,
  CreateIntegrationCommand,
  CreateRouteCommand,
  CreateRouteResponseCommand,
  CreateStageCommand,
  CreateDeploymentCommand,
} from '@aws-sdk/client-apigatewayv2';
import {
  ApiGatewayManagementApiClient,
  PostToConnectionCommand,
  GetConnectionCommand,
  DeleteConnectionCommand,
} from '@aws-sdk/client-apigatewaymanagementapi';
import { LambdaClient, CreateFunctionCommand, DeleteFunctionCommand } from '@aws-sdk/client-lambda';
import WebSocket from 'ws';
import { makeClient, uniqueName, ENDPOINT, REGION, ACCOUNT, buildMinimalZip, sleep } from './setup';

// ── Helpers ──────────────────────────────────────────────────────────────────

/** The local endpoint authority, e.g. `localhost:4566`. */
function authority(): string {
  return ENDPOINT.replace(/^https?:\/\//, '').replace(/\/$/, '');
}

/**
 * Floci's advertised local execute-api domain, e.g. `{apiId}.execute-api.localhost.floci.io:4566`
 * (regionless — see docs/services/api-gateway.md and the TLS SANs). This is the host form these
 * end-to-end tests exercise: it is what floci actually serves/routes, and the region is recovered
 * by a cross-region apiId lookup rather than a host label. The region-bearing AWS shape
 * (`{apiId}.execute-api.{region}.amazonaws.com`) is covered by the Java filter/resolver unit tests.
 */
function builtinSuffixHost(apiId: string): string {
  const port = authority().includes(':') ? `:${authority().split(':')[1]}` : '';
  return `${apiId}.execute-api.localhost.floci.io${port}`;
}

function configuredHostnameHost(apiId: string, region: string): string {
  const endpoint = new URL(ENDPOINT);
  const port = endpoint.port ? `:${endpoint.port}` : '';
  return `${apiId}.execute-api.${region}.${endpoint.hostname}${port}`;
}

/**
 * A Management API client that presents an execute-api Host header while still connecting to the
 * real test endpoint. A build-step middleware overrides the `Host` header (before SigV4 signing)
 * to the given execute-api host; the request path stays `/{stage}/@connections/{id}`. This is
 * exactly what floci's `ApiGatewayExecuteApiHostFilter` keys on, so it proves host-based routing
 * without relying on `*.localhost` DNS.
 */
function managementClientForHost(host: string, stage: string, region: string): ApiGatewayManagementApiClient {
  const client = makeClient(ApiGatewayManagementApiClient, {
    endpoint: `${ENDPOINT}/${stage}`,
    region,
  });
  client.middlewareStack.add(
    (next) => async (args) => {
      const req = args.request as { headers?: Record<string, string> };
      if (req && req.headers) {
        req.headers['host'] = host;
      }
      return next(args);
    },
    { step: 'build', name: 'setExecuteApiHost', priority: 'high' }
  );
  return client;
}

function subdomainManagementClient(apiId: string, stage: string, region: string): ApiGatewayManagementApiClient {
  return managementClientForHost(builtinSuffixHost(apiId), stage, region);
}

/** Connect a WebSocket to the real endpoint but with the execute-api Host header. */
function connectWsViaSubdomain(apiId: string, stage: string): Promise<WebSocket> {
  const url = `ws://${authority()}/${stage}`;
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url, { headers: { Host: builtinSuffixHost(apiId) } });
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

const ROLE_ARN = `arn:aws:iam::${ACCOUNT}:role/lambda-role`;

// Echo handler that can report its own connectionId (mirrors the data-plane suite).
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

// ── Test suite ─────────────────────────────────────────────────────────────

describe('API Gateway v2 execute-api subdomain routing', () => {
  const createdApis: Array<{ apiId: string; region: string }> = [];
  const createdFunctions: string[] = [];
  let lambda: LambdaClient;

  beforeAll(() => {
    lambda = makeClient(LambdaClient);
  });

  afterAll(async () => {
    for (const fnName of createdFunctions) {
      try { await lambda.send(new DeleteFunctionCommand({ FunctionName: fnName })); } catch { /* ignore */ }
    }
    for (const { apiId, region } of createdApis) {
      try {
        const gw = makeClient(ApiGatewayV2Client, { region });
        await gw.send(new DeleteApiCommand({ ApiId: apiId }));
      } catch { /* ignore */ }
    }
  });

  async function createWsApiWithStage(name: string, region: string, stage: string): Promise<string> {
    const gw = makeClient(ApiGatewayV2Client, { region });
    const res = await gw.send(new CreateApiCommand({
      Name: uniqueName(name),
      ProtocolType: 'WEBSOCKET',
      RouteSelectionExpression: '$request.body.action',
    }));
    const apiId = res.ApiId!;
    createdApis.push({ apiId, region });
    const deploy = await gw.send(new CreateDeploymentCommand({ ApiId: apiId }));
    await gw.send(new CreateStageCommand({ ApiId: apiId, StageName: stage, DeploymentId: deploy.DeploymentId! }));
    return apiId;
  }

  // ── #1846: @connections Management API via subdomain host ──────────────────

  it('routes @connections via the subdomain host to the API (not S3): unknown connection -> GoneException', async () => {
    const stage = 'prod';
    const apiId = await createWsApiWithStage('subdomain-conn', REGION, stage);
    const mgmt = subdomainManagementClient(apiId, stage, REGION);

    // A GET for a non-existent connection must reach the API Gateway controller and
    // return GoneException (410). Before the fix the S3 controller intercepted this
    // host, so it would NOT surface as GoneException.
    await expect(
      mgmt.send(new GetConnectionCommand({ ConnectionId: 'does-not-exist' }))
    ).rejects.toMatchObject({ name: 'GoneException' });

    await expect(
      mgmt.send(new DeleteConnectionCommand({ ConnectionId: 'does-not-exist' }))
    ).rejects.toMatchObject({ name: 'GoneException' });

    await expect(
      mgmt.send(new PostToConnectionCommand({ ConnectionId: 'does-not-exist', Data: Buffer.from('hi') }))
    ).rejects.toMatchObject({ name: 'GoneException' });
  });

  it('routes @connections through the region-bearing configured hostname (not S3)', async () => {
    const region = 'ap-northeast-2';
    const stage = 'prod';
    const apiId = await createWsApiWithStage('configured-host-conn', region, stage);
    const host = configuredHostnameHost(apiId, region);
    const mgmt = managementClientForHost(host, stage, region);

    // The Node compatibility container connects to the Compose service at floci:4566, while
    // FLOCI_HOSTNAME=floci. Present the AWS-shaped region-bearing virtual host through the SDK's
    // signed Host header so this proves configured-host routing without wildcard DNS.
    await expect(
      mgmt.send(new GetConnectionCommand({ ConnectionId: 'does-not-exist' }))
    ).rejects.toMatchObject({ name: 'GoneException' });
  });

  it('delivers a message to a live connection via the subdomain PostToConnection endpoint', async () => {
    const stage = 'prod';
    const region = REGION;
    const gw = makeClient(ApiGatewayV2Client, { region });
    const fnName = uniqueName('subdomain-echo');
    await lambda.send(new CreateFunctionCommand({
      FunctionName: fnName,
      Runtime: 'nodejs22.x',
      Role: ROLE_ARN,
      Handler: 'index.handler',
      Code: { ZipFile: buildMinimalZip('index.js', Buffer.from(ECHO_HANDLER)) },
    }));
    createdFunctions.push(fnName);

    const res = await gw.send(new CreateApiCommand({
      Name: uniqueName('subdomain-live'),
      ProtocolType: 'WEBSOCKET',
      RouteSelectionExpression: '$request.body.action',
    }));
    const apiId = res.ApiId!;
    createdApis.push({ apiId, region });
    const integ = await gw.send(new CreateIntegrationCommand({
      ApiId: apiId,
      IntegrationType: 'AWS_PROXY',
      IntegrationUri: `arn:aws:lambda:${region}:${ACCOUNT}:function:${fnName}`,
    }));
    // $default with a route response so the echo reply is delivered back to the
    // client (matches the data-plane suite; without a route response no frame is sent).
    const route = await gw.send(new CreateRouteCommand({
      ApiId: apiId,
      RouteKey: '$default',
      Target: `integrations/${integ.IntegrationId}`,
      RouteResponseSelectionExpression: '$default',
    }));
    await gw.send(new CreateRouteResponseCommand({
      ApiId: apiId,
      RouteId: route.RouteId!,
      RouteResponseKey: '$default',
    }));
    const deploy = await gw.send(new CreateDeploymentCommand({ ApiId: apiId }));
    await gw.send(new CreateStageCommand({ ApiId: apiId, StageName: stage, DeploymentId: deploy.DeploymentId! }));

    const ws = await connectWsViaSubdomain(apiId, stage);
    try {
      // Learn our own connectionId via the echo integration (first invoke is a
      // Lambda cold start, so allow extra headroom here).
      ws.send(JSON.stringify({ action: 'getConnectionId' }));
      const idResponse = await waitForMessage(ws, 15000);
      const connectionId = JSON.parse(idResponse).connectionId as string;
      expect(connectionId).toBeTruthy();

      // Push a server-initiated message through the subdomain Management endpoint.
      const mgmt = subdomainManagementClient(apiId, stage, region);
      const pushed = waitForMessage(ws);
      await mgmt.send(new PostToConnectionCommand({ ConnectionId: connectionId, Data: Buffer.from('via-subdomain') }));
      expect(await pushed).toBe('via-subdomain');
    } finally {
      if (ws.readyState !== WebSocket.CLOSED) ws.close();
    }
  });

  // ── #1871: WebSocket $connect via subdomain host resolves region ───────────

  it('connects a WebSocket in a non-default region via the subdomain host (no 403)', async () => {
    const region = 'ap-northeast-2';
    const stage = 'prod';
    const apiId = await createWsApiWithStage('subdomain-region', region, stage);

    // The execute-api host (regionless built-in suffix) carries no region and a handshake has no
    // Authorization header, so the API is found by a cross-region apiId lookup. Before the fix a
    // connect to a non-default-region WS API returned 403.
    const ws = await connectWsViaSubdomain(apiId, stage);
    try {
      expect(ws.readyState).toBe(WebSocket.OPEN);
    } finally {
      if (ws.readyState !== WebSocket.CLOSED) ws.close();
    }

    // Sanity: the region-less PATH form (/ws/{apiId}/{stage}) resolves only the default region and
    // must NOT connect to this ap-northeast-2 API — confirming the execute-api host is what routed it.
    await expect(new Promise<WebSocket>((resolve, reject) => {
      const bad = new WebSocket(`ws://${authority()}/ws/${apiId}/${stage}`);
      bad.on('open', () => resolve(bad));
      bad.on('error', (err) => reject(err));
    })).rejects.toBeTruthy();

    await sleep(50);
  });

  // ── Regionless built-in suffix must not be mis-parsed as region "localhost" ──

  it('routes @connections via the regionless built-in suffix host for a non-default-region API', async () => {
    const region = 'ap-northeast-2';
    const stage = 'prod';
    const apiId = await createWsApiWithStage('builtin-suffix', region, stage);

    // Host is {apiId}.execute-api.localhost.floci.io:4566 — NO region label. The old parser
    // read "localhost" as the region and the lookup missed; the consolidated filter resolves
    // the API by id across regions and still routes @connections to the controller (410 Gone),
    // not S3.
    const mgmt = managementClientForHost(builtinSuffixHost(apiId), stage, region);
    await expect(
      mgmt.send(new GetConnectionCommand({ ConnectionId: 'does-not-exist' }))
    ).rejects.toMatchObject({ name: 'GoneException' });
  });
});

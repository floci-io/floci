/**
 * Cognito OAuth/Resource Server integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createPublicKey, createVerify } from 'node:crypto';
import {
  CognitoIdentityProviderClient,
  CreateUserPoolCommand,
  CreateUserPoolClientCommand,
  CreateResourceServerCommand,
  DescribeResourceServerCommand,
  ListResourceServersCommand,
  UpdateResourceServerCommand,
  DeleteResourceServerCommand,
  DeleteUserPoolClientCommand,
  DeleteUserPoolCommand,
} from '@aws-sdk/client-cognito-identity-provider';
import { makeClient, uniqueName, ENDPOINT } from './setup';

// Helper functions
function decodeJwtPart(token: string, index: number): any {
  const parts = token.split('.');
  const part = parts[index];
  return JSON.parse(Buffer.from(part, 'base64url').toString('utf8'));
}

function scopeContains(actual: string | undefined, expected: string): boolean {
  if (!actual) return false;
  return actual.split(/\s+/).includes(expected);
}

async function fetchJwk(jwksUri: string, kid: string): Promise<any> {
  const resp = await fetch(jwksUri);
  const json = await resp.json();
  return json.keys?.find((k: any) => k.kid === kid);
}

function verifyRs256(token: string, jwk: any): boolean {
  const [headerB64, payloadB64, signatureB64] = token.split('.');
  const signature = Buffer.from(signatureB64, 'base64url');
  const data = `${headerB64}.${payloadB64}`;

  const key = createPublicKey({ key: jwk, format: 'jwk' });
  const verifier = createVerify('RSA-SHA256');
  verifier.update(data);
  return verifier.verify(key, signature);
}

async function discoverOpenIdConfiguration(poolId: string) {
  const resp = await fetch(`${ENDPOINT}/${poolId}/.well-known/openid-configuration`);
  const json = await resp.json();
  return {
    tokenEndpoint: json.token_endpoint as string,
    jwksUri: json.jwks_uri as string,
    issuer: json.issuer as string,
  };
}

async function requestConfidentialClientToken(
  tokenEndpoint: string,
  clientId: string,
  clientSecret: string,
  scope: string
): Promise<{ status: number; json: any; body: string }> {
  const basicAuth = Buffer.from(`${clientId}:${clientSecret}`).toString('base64');
  const resp = await fetch(tokenEndpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Authorization: `Basic ${basicAuth}`,
    },
    body: `grant_type=client_credentials&scope=${encodeURIComponent(scope)}`,
  });
  const body = await resp.text();
  let json = {};
  try {
    json = JSON.parse(body);
  } catch {
    // ignore
  }
  return { status: resp.status, json, body };
}

async function requestPublicClientToken(
  tokenEndpoint: string,
  clientId: string,
  scope: string
): Promise<{ status: number; json: any; body: string }> {
  const resp = await fetch(tokenEndpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=client_credentials&client_id=${clientId}&scope=${encodeURIComponent(scope)}`,
  });
  const body = await resp.text();
  let json = {};
  try {
    json = JSON.parse(body);
  } catch {
    // ignore
  }
  return { status: resp.status, json, body };
}

function isPublicClientRejectionError(e: any): boolean {
  const msg = e.message || '';
  return (
    msg.includes('public client') ||
    msg.includes('GenerateSecret') ||
    msg.includes('client_credentials')
  );
}

describe('Cognito OAuth', () => {
  let cognito: CognitoIdentityProviderClient;
  let poolId: string;
  let resourceServerId: string;
  let readScope: string;
  let adminScope: string;
  let confidentialClientId: string;
  let confidentialClientSecret: string;
  let publicClientId: string;
  let publicClientRejectedAtCreate = false;
  let discovery: { tokenEndpoint: string; jwksUri: string; issuer: string } | null = null;
  let accessToken: string | null = null;

  beforeAll(async () => {
    cognito = makeClient(CognitoIdentityProviderClient);
    const suffix = uniqueName();
    resourceServerId = `https://compat.floci.test/resource/${suffix}`;
    readScope = `${resourceServerId}/read`;
    adminScope = `${resourceServerId}/admin`;
  });

  afterAll(async () => {
    try {
      if (confidentialClientId) {
        await cognito.send(
          new DeleteUserPoolClientCommand({ UserPoolId: poolId, ClientId: confidentialClientId })
        );
      }
    } catch {
      // ignore
    }
    try {
      if (publicClientId) {
        await cognito.send(
          new DeleteUserPoolClientCommand({ UserPoolId: poolId, ClientId: publicClientId })
        );
      }
    } catch {
      // ignore
    }
    try {
      if (resourceServerId && poolId) {
        await cognito.send(
          new DeleteResourceServerCommand({ UserPoolId: poolId, Identifier: resourceServerId })
        );
      }
    } catch {
      // ignore
    }
    try {
      if (poolId) {
        await cognito.send(new DeleteUserPoolCommand({ UserPoolId: poolId }));
      }
    } catch {
      // ignore
    }
  });

  it('should create user pool for OAuth', async () => {
    const response = await cognito.send(
      new CreateUserPoolCommand({ PoolName: `floci-oauth-pool-${uniqueName()}` })
    );
    poolId = response.UserPool!.Id!;
    expect(poolId).toBeTruthy();
  });

  it('should create resource server', async () => {
    const response = await cognito.send(
      new CreateResourceServerCommand({
        UserPoolId: poolId,
        Identifier: resourceServerId,
        Name: 'compat-resource-server',
        Scopes: [
          { ScopeName: 'read', ScopeDescription: 'Read access' },
          { ScopeName: 'write', ScopeDescription: 'Write access' },
        ],
      })
    );
    expect(response.ResourceServer?.Identifier).toBe(resourceServerId);
    expect(response.ResourceServer?.Scopes).toHaveLength(2);
  });

  it('should describe resource server', async () => {
    const response = await cognito.send(
      new DescribeResourceServerCommand({
        UserPoolId: poolId,
        Identifier: resourceServerId,
      })
    );
    expect(response.ResourceServer?.Name).toBe('compat-resource-server');
    const scopes = response.ResourceServer?.Scopes?.map((s) => s.ScopeName) || [];
    expect(scopes).toContain('read');
    expect(scopes).toContain('write');
  });

  it('should list resource servers', async () => {
    const response = await cognito.send(
      new ListResourceServersCommand({ UserPoolId: poolId, MaxResults: 60 })
    );
    expect(
      response.ResourceServers?.some((s) => s.Identifier === resourceServerId)
    ).toBe(true);
  });

  it('should update resource server', async () => {
    await cognito.send(
      new UpdateResourceServerCommand({
        UserPoolId: poolId,
        Identifier: resourceServerId,
        Name: 'compat-resource-server-updated',
        Scopes: [
          { ScopeName: 'read', ScopeDescription: 'Read access updated' },
          { ScopeName: 'admin', ScopeDescription: 'Admin access' },
        ],
      })
    );

    const response = await cognito.send(
      new DescribeResourceServerCommand({
        UserPoolId: poolId,
        Identifier: resourceServerId,
      })
    );
    expect(response.ResourceServer?.Name).toBe('compat-resource-server-updated');
    const scopes = response.ResourceServer?.Scopes?.map((s) => s.ScopeName) || [];
    expect(scopes).toContain('read');
    expect(scopes).toContain('admin');
    expect(scopes).not.toContain('write');
  });

  it('should create confidential client', async () => {
    const response = await cognito.send(
      new CreateUserPoolClientCommand({
        UserPoolId: poolId,
        ClientName: `compat-confidential-client-${uniqueName()}`,
        GenerateSecret: true,
        AllowedOAuthFlowsUserPoolClient: true,
        AllowedOAuthFlows: ['client_credentials'],
        AllowedOAuthScopes: [readScope, adminScope],
      })
    );
    confidentialClientId = response.UserPoolClient?.ClientId!;
    confidentialClientSecret = response.UserPoolClient?.ClientSecret!;
    expect(confidentialClientId).toBeTruthy();
    expect(confidentialClientSecret).toBeTruthy();
  });

  it('should handle public client creation (rejected or accepted)', async () => {
    try {
      const response = await cognito.send(
        new CreateUserPoolClientCommand({
          UserPoolId: poolId,
          ClientName: `compat-public-client-${uniqueName()}`,
          AllowedOAuthFlowsUserPoolClient: true,
          AllowedOAuthFlows: ['client_credentials'],
          AllowedOAuthScopes: [readScope, adminScope],
        })
      );
      publicClientId = response.UserPoolClient?.ClientId!;
      expect(publicClientId).toBeTruthy();
      expect(response.UserPoolClient?.ClientSecret).toBeFalsy();
    } catch (e: any) {
      publicClientRejectedAtCreate = isPublicClientRejectionError(e);
      expect(publicClientRejectedAtCreate).toBe(true);
    }
  });

  it('should discover OIDC configuration', async () => {
    discovery = await discoverOpenIdConfiguration(poolId);
    expect(discovery.tokenEndpoint).toContain('/oauth2/token');
    expect(discovery.jwksUri).toContain('/.well-known/jwks.json');
    expect(discovery.issuer).toBeTruthy();
  });

  it('should obtain access token via client_credentials', async () => {
    if (!discovery || !confidentialClientId || !confidentialClientSecret) {
      return;
    }

    const resp = await requestConfidentialClientToken(
      discovery.tokenEndpoint,
      confidentialClientId,
      confidentialClientSecret,
      readScope
    );

    expect(resp.status).toBe(200);
    accessToken = resp.json.access_token;
    expect(accessToken).toBeTruthy();
    expect(resp.json.token_type?.toLowerCase()).toBe('bearer');
    expect(Number(resp.json.expires_in)).toBeGreaterThan(0);
  });

  it('should validate JWT structure and claims', async () => {
    if (!accessToken || !discovery) {
      return;
    }

    const header = decodeJwtPart(accessToken, 0);
    const payload = decodeJwtPart(accessToken, 1);

    expect(header.alg).toBe('RS256');
    expect(header.kid).toBeTruthy();
    expect(payload.iss).toBe(discovery.issuer);
    expect(payload.client_id).toBe(confidentialClientId);
    expect(scopeContains(payload.scope, readScope)).toBe(true);
  });

  it('should verify RS256 signature against JWKS', async () => {
    if (!accessToken || !discovery) {
      return;
    }

    const kid = decodeJwtPart(accessToken, 0).kid;
    const jwk = await fetchJwk(discovery.jwksUri, kid);
    expect(verifyRs256(accessToken, jwk)).toBe(true);
  });

  it('should reject public client token request', async () => {
    if (publicClientRejectedAtCreate || !discovery || !publicClientId) {
      return;
    }

    const resp = await requestPublicClientToken(discovery.tokenEndpoint, publicClientId, readScope);
    expect(resp.status).toBeGreaterThanOrEqual(400);
    expect(resp.status).toBeLessThan(500);
    expect(['invalid_client', 'unauthorized_client']).toContain(resp.json.error);
  });

  it('should reject unknown scope', async () => {
    if (!discovery || !confidentialClientId || !confidentialClientSecret) {
      return;
    }

    const resp = await requestConfidentialClientToken(
      discovery.tokenEndpoint,
      confidentialClientId,
      confidentialClientSecret,
      `${resourceServerId}/unknown`
    );
    expect(resp.status).toBe(400);
    expect(resp.json.error).toBe('invalid_scope');
  });

  it('should delete resource server', async () => {
    await cognito.send(
      new DeleteResourceServerCommand({
        UserPoolId: poolId,
        Identifier: resourceServerId,
      })
    );
    resourceServerId = '';
  });
});

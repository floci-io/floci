/**
 * S3 CORS enforcement integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  S3Client,
  CreateBucketCommand,
  PutObjectCommand,
  DeleteObjectCommand,
  DeleteBucketCommand,
  PutBucketCorsCommand,
  DeleteBucketCorsCommand,
} from '@aws-sdk/client-s3';
import { makeClient, uniqueName, ENDPOINT } from './setup';

describe('S3 CORS Enforcement', () => {
  let s3: S3Client;
  let bucketName: string;

  async function rawRequest(
    method: string,
    path: string,
    extraHeaders: Record<string, string> = {}
  ) {
    const url = `${ENDPOINT}/${bucketName}${path}`;
    const resp = await fetch(url, { method, headers: extraHeaders });
    const headers: Record<string, string> = {};
    resp.headers.forEach((v, k) => {
      headers[k.toLowerCase()] = v;
    });
    return { status: resp.status, headers };
  }

  beforeAll(async () => {
    s3 = makeClient(S3Client, { forcePathStyle: true });
    bucketName = `cors-test-bucket-${uniqueName()}`;

    await s3.send(new CreateBucketCommand({ Bucket: bucketName }));
    await s3.send(
      new PutObjectCommand({ Bucket: bucketName, Key: 'cors-test.txt', Body: 'hello cors' })
    );
  });

  afterAll(async () => {
    try {
      await s3.send(new DeleteBucketCorsCommand({ Bucket: bucketName }));
    } catch {
      // ignore
    }
    try {
      await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: 'cors-test.txt' }));
    } catch {
      // ignore
    }
    try {
      await s3.send(new DeleteBucketCommand({ Bucket: bucketName }));
    } catch {
      // ignore
    }
  });

  it('should reject preflight without CORS config', async () => {
    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'http://localhost:3000',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(403);
  });

  it('should set wildcard CORS config', async () => {
    await s3.send(
      new PutBucketCorsCommand({
        Bucket: bucketName,
        CORSConfiguration: {
          CORSRules: [
            {
              AllowedOrigins: ['*'],
              AllowedMethods: ['GET', 'PUT', 'POST', 'DELETE', 'HEAD'],
              AllowedHeaders: ['*'],
              ExposeHeaders: ['ETag'],
              MaxAgeSeconds: 3000,
            },
          ],
        },
      })
    );
  });

  it('should allow wildcard preflight', async () => {
    const { status, headers } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'http://localhost:3000',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(200);
    expect(headers['access-control-allow-origin']).toBe('*');
    expect(headers['access-control-max-age']).toBe('3000');
    expect(headers['access-control-allow-methods']?.toUpperCase()).toContain('GET');
  });

  it('should return CORS headers on actual GET with Origin', async () => {
    const { headers } = await rawRequest('GET', '/cors-test.txt', {
      Origin: 'http://localhost:3000',
    });
    expect(headers['access-control-allow-origin']).toBe('*');
    expect(headers['vary']?.toLowerCase()).toContain('origin');
    expect(headers['access-control-expose-headers']).toContain('ETag');
  });

  it('should not return CORS headers without Origin', async () => {
    const { headers } = await rawRequest('GET', '/cors-test.txt');
    expect(headers['access-control-allow-origin']).toBeUndefined();
  });

  it('should set specific origin CORS config', async () => {
    await s3.send(
      new PutBucketCorsCommand({
        Bucket: bucketName,
        CORSConfiguration: {
          CORSRules: [
            {
              AllowedOrigins: ['https://example.com'],
              AllowedMethods: ['GET', 'PUT'],
              AllowedHeaders: ['Content-Type', 'Authorization'],
              ExposeHeaders: ['ETag', 'x-amz-request-id'],
              MaxAgeSeconds: 600,
            },
          ],
        },
      })
    );
  });

  it('should allow matching origin preflight', async () => {
    const { status, headers } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'https://example.com',
      'Access-Control-Request-Method': 'GET',
      'Access-Control-Request-Headers': 'Content-Type',
    });
    expect(status).toBe(200);
    expect(headers['access-control-allow-origin']).toBe('https://example.com');
    expect(headers['access-control-max-age']).toBe('600');
  });

  it('should reject non-matching origin', async () => {
    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'https://attacker.evil.com',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(403);
  });

  it('should reject non-matching method', async () => {
    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'https://example.com',
      'Access-Control-Request-Method': 'DELETE',
    });
    expect(status).toBe(403);
  });

  it('should echo origin for matching actual GET', async () => {
    const { headers } = await rawRequest('GET', '/cors-test.txt', {
      Origin: 'https://example.com',
    });
    expect(headers['access-control-allow-origin']).toBe('https://example.com');
  });

  it('should not include CORS headers for non-matching origin GET', async () => {
    const { headers } = await rawRequest('GET', '/cors-test.txt', {
      Origin: 'https://not-allowed.com',
    });
    expect(headers['access-control-allow-origin']).toBeUndefined();
  });

  it('should reject preflight after CORS config deleted', async () => {
    await s3.send(new DeleteBucketCorsCommand({ Bucket: bucketName }));

    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'http://localhost:3000',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(403);
  });

  it('should support subdomain wildcard pattern', async () => {
    await s3.send(
      new PutBucketCorsCommand({
        Bucket: bucketName,
        CORSConfiguration: {
          CORSRules: [
            {
              AllowedOrigins: ['http://*.example.com'],
              AllowedMethods: ['GET'],
              AllowedHeaders: ['*'],
              MaxAgeSeconds: 120,
            },
          ],
        },
      })
    );

    const { status, headers } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'http://app.example.com',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(200);
    expect(headers['access-control-allow-origin']).toBe('http://app.example.com');
  });

  it('should reject https with http wildcard pattern', async () => {
    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'https://app.example.com',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(403);
  });

  it('should reject different domain with subdomain wildcard', async () => {
    const { status } = await rawRequest('OPTIONS', '/cors-test.txt', {
      Origin: 'http://app.other.com',
      'Access-Control-Request-Method': 'GET',
    });
    expect(status).toBe(403);
  });
});

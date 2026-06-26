/**
 * Common test setup and utilities for floci SDK tests.
 */

import { randomUUID } from 'node:crypto';
import { writeFileSync, readFileSync, mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { buildSync } from 'esbuild';

export const ENDPOINT = process.env.FLOCI_ENDPOINT || 'http://localhost:4566';
export const REGION = process.env.AWS_DEFAULT_REGION || 'us-east-1';
export const ACCOUNT = '000000000000';
export const CREDS = {
  accessKeyId: process.env.AWS_ACCESS_KEY_ID || 'test',
  secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY || 'test',
};

export const CLIENT_CONFIG = {
  endpoint: ENDPOINT,
  region: REGION,
  credentials: CREDS,
  forcePathStyle: true,
};

export function makeClient<T>(
  ClientClass: new (config: typeof CLIENT_CONFIG) => T,
  extra: Record<string, unknown> = {}
): T {
  return new ClientClass({ ...CLIENT_CONFIG, ...extra });
}

export function uniqueName(prefix = 'test'): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

export function sleep(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

const SIX_DIGIT_CODE = /\b(\d{6})\b/;

export async function fetchLatestSesVerificationCode(recipient: string): Promise<string> {
  const response = await fetch(
    `${ENDPOINT}/_aws/ses?email=${encodeURIComponent(recipient)}`
  );
  if (!response.ok) {
    throw new Error(`failed to fetch SES messages: ${response.status}`);
  }

  const payload = (await response.json()) as {
    messages?: Array<{ Body?: { text_part?: string } }>;
  };
  const firstMessage = payload.messages?.[0];
  const body =
    firstMessage?.Body?.text_part ??
    (firstMessage?.Body as { html_part?: string } | undefined)?.html_part ??
    '';
  const match = body.match(SIX_DIGIT_CODE);
  if (!match) {
    throw new Error('verification code email should contain a 6-digit code');
  }
  return match[1];
}

// Build a minimal ZIP file for Lambda functions
export function buildMinimalZip(filename: string, content: Buffer): Buffer {
  const filenameBytes = Buffer.from(filename);
  const crc = crc32(content);

  // Local file header
  const localHeader = Buffer.alloc(30 + filenameBytes.length);
  localHeader.writeUInt32LE(0x04034b50, 0); // signature
  localHeader.writeUInt16LE(20, 4); // version needed
  localHeader.writeUInt16LE(0, 6); // flags
  localHeader.writeUInt16LE(0, 8); // compression: store
  localHeader.writeUInt16LE(0, 10); // mod time
  localHeader.writeUInt16LE(0, 12); // mod date
  localHeader.writeUInt32LE(crc, 14); // crc32
  localHeader.writeUInt32LE(content.length, 18); // compressed size
  localHeader.writeUInt32LE(content.length, 22); // uncompressed size
  localHeader.writeUInt16LE(filenameBytes.length, 26); // filename len
  localHeader.writeUInt16LE(0, 28); // extra field len
  filenameBytes.copy(localHeader, 30);

  const centralDir = Buffer.alloc(46 + filenameBytes.length);
  centralDir.writeUInt32LE(0x02014b50, 0); // signature
  centralDir.writeUInt16LE(20, 4); // version made by
  centralDir.writeUInt16LE(20, 6); // version needed
  centralDir.writeUInt16LE(0, 8); // flags
  centralDir.writeUInt16LE(0, 10); // compression
  centralDir.writeUInt16LE(0, 12); // mod time
  centralDir.writeUInt16LE(0, 14); // mod date
  centralDir.writeUInt32LE(crc, 16); // crc32
  centralDir.writeUInt32LE(content.length, 20); // compressed size
  centralDir.writeUInt32LE(content.length, 24); // uncompressed size
  centralDir.writeUInt16LE(filenameBytes.length, 28); // filename len
  centralDir.writeUInt16LE(0, 30); // extra
  centralDir.writeUInt16LE(0, 32); // comment
  centralDir.writeUInt16LE(0, 34); // disk start
  centralDir.writeUInt16LE(0, 36); // int attributes
  centralDir.writeUInt32LE(0, 38); // ext attributes
  centralDir.writeUInt32LE(0, 42); // local header offset
  filenameBytes.copy(centralDir, 46);

  const centralDirOffset = localHeader.length + content.length;
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0); // signature
  eocd.writeUInt16LE(0, 4); // disk num
  eocd.writeUInt16LE(0, 6); // disk with cd
  eocd.writeUInt16LE(1, 8); // total entries on disk
  eocd.writeUInt16LE(1, 10); // total entries
  eocd.writeUInt32LE(centralDir.length, 12); // cd size
  eocd.writeUInt32LE(centralDirOffset, 16); // cd offset
  eocd.writeUInt16LE(0, 20); // comment len

  return Buffer.concat([localHeader, content, centralDir, eocd]);
}

function makeCrcTable(): Uint32Array {
  const table = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let c = i;
    for (let j = 0; j < 8; j++) {
      c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    }
    table[i] = c;
  }
  return table;
}

const crcTable = makeCrcTable();

function crc32(buf: Buffer): number {
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ crcTable[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * Build a Lambda ZIP with all dependencies bundled via esbuild.
 *
 * Use this when the handler code requires npm packages (e.g. @aws-sdk/client-apigatewaymanagementapi).
 * esbuild resolves imports from the test project's node_modules and inlines everything into a single
 * CommonJS file, which is then zipped using buildMinimalZip.
 *
 * This avoids maintaining a separate folder with its own package.json/node_modules.
 */
export function buildBundledZip(handlerCode: string): Buffer {
  const tmpDir = mkdtempSync(join(tmpdir(), 'lambda-bundle-'));
  const entryFile = join(tmpDir, 'index.js');
  const outFile = join(tmpDir, 'bundle.js');

  // Resolve node_modules from the test project root (where this file lives)
  const projectRoot = join(import.meta.dirname, '..');
  const nodeModulesPath = join(projectRoot, 'node_modules');

  try {
    writeFileSync(entryFile, handlerCode);

    buildSync({
      entryPoints: [entryFile],
      bundle: true,
      platform: 'node',
      target: 'node22',
      outfile: outFile,
      format: 'cjs',
      minify: false,
      nodePaths: [nodeModulesPath],
    });

    const bundled = readFileSync(outFile);
    return buildMinimalZip('index.js', bundled);
  } finally {
    rmSync(tmpDir, { recursive: true, force: true });
  }
}

/**
 * Common test setup and utilities for floci SDK tests.
 */

import { randomUUID } from 'node:crypto';

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

function crc32(buf: Buffer): number {
  const table = makeCrcTable();
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) {
    crc = (crc >>> 8) ^ table[(crc ^ buf[i]) & 0xff];
  }
  return (crc ^ 0xffffffff) >>> 0;
}

/**
 * Multipart uploads through @aws-sdk/lib-storage, the high-level uploader of the JavaScript SDK.
 * S3 stores a COMPOSITE checksum for SHA algorithms (the algorithm applied to the concatenated part
 * checksums, suffixed with "-<parts>"), reports it on HeadObject and drops the suffix on
 * GetObjectAttributes; a copy is written as a single object with a FULL_OBJECT checksum.
 */
import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import { createHash } from 'node:crypto';
import { crc32 } from 'node:zlib';
import {
  S3Client,
  CreateBucketCommand,
  DeleteBucketCommand,
  DeleteObjectCommand,
  HeadObjectCommand,
  GetObjectCommand,
  GetObjectAttributesCommand,
  CopyObjectCommand,
} from '@aws-sdk/client-s3';
import { Upload } from '@aws-sdk/lib-storage';
import { makeClient, uniqueName } from './setup';

const MIB = 1024 * 1024;
const PART_SIZE = 5 * MIB;

/** Deterministic pseudo-random payload (xorshift32), so every run computes the same checksums. */
function payloadOf(size: number): Buffer {
  const out = Buffer.alloc(size);
  let x = 20260903;
  for (let i = 0; i < size; i += 4) {
    x ^= x << 13; x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5; x >>>= 0;
    out.writeUInt32LE(x, i);
  }
  return out;
}

function parts(data: Buffer, size = PART_SIZE): Buffer[] {
  const result: Buffer[] = [];
  for (let start = 0; start < data.length; start += size) {
    result.push(data.subarray(start, Math.min(start + size, data.length)));
  }
  return result;
}

const sha256 = (data: Buffer) => createHash('sha256').update(data).digest();
const md5Hex = (data: Buffer) => createHash('md5').update(data).digest('hex');
function crc32Bytes(data: Buffer): Buffer {
  const out = Buffer.alloc(4);
  out.writeUInt32BE(crc32(data) >>> 0);
  return out;
}
const composite = (digest: (b: Buffer) => Buffer, pieces: Buffer[]) =>
  `${digest(Buffer.concat(pieces.map(digest))).toString('base64')}-${pieces.length}`;

/** CRC-64/NVME (reflected polynomial 0x9a6c9329ac4bc9b5), what S3 attaches when no algorithm is declared. */
function crc64Nvme(data: Buffer): Buffer {
  const poly = 0x9a6c9329ac4bc9b5n;
  const mask = 0xffffffffffffffffn;
  const table: bigint[] = [];
  for (let i = 0; i < 256; i++) {
    let c = BigInt(i);
    for (let j = 0; j < 8; j++) {
      c = c & 1n ? (c >> 1n) ^ poly : c >> 1n;
    }
    table.push(c);
  }
  let c = mask;
  for (const byte of data) {
    c = table[Number((c ^ BigInt(byte)) & 0xffn)] ^ (c >> 8n);
  }
  c = (~c) & mask;
  const out = Buffer.alloc(8);
  out.writeBigUInt64BE(c);
  return out;
}

describe('S3 multipart checksums', () => {
  const s3 = makeClient(S3Client);
  const bucket = uniqueName('node-multipart-checksums');
  const payload = payloadOf(12 * MIB);
  const pieces = parts(payload);
  const keys = { sha256: 'sha256.bin', byDefault: 'default.bin', copy: 'sha256-copy.bin' };

  const head = (Key: string) =>
    s3.send(new HeadObjectCommand({ Bucket: bucket, Key, ChecksumMode: 'ENABLED' }));

  async function upload(Key: string, ChecksumAlgorithm?: 'SHA256') {
    await new Upload({
      client: s3,
      params: { Bucket: bucket, Key, Body: payload, ...(ChecksumAlgorithm ? { ChecksumAlgorithm } : {}) },
      partSize: PART_SIZE,
      queueSize: 2,
    }).done();
  }

  beforeAll(async () => {
    await s3.send(new CreateBucketCommand({ Bucket: bucket }));
  });

  afterAll(async () => {
    for (const Key of Object.values(keys)) {
      await s3
        .send(new DeleteObjectCommand({ Bucket: bucket, Key }))
        .catch((e) => console.warn(`cleanup: could not delete s3://${bucket}/${Key}: ${e}`));
    }
    await s3
      .send(new DeleteBucketCommand({ Bucket: bucket }))
      .catch((e) => console.warn(`cleanup: could not delete bucket ${bucket}: ${e}`));
  });

  it('reports the composite SHA256 checksum of a multipart upload', async () => {
    await upload(keys.sha256, 'SHA256');
    const expected = composite(sha256, pieces);
    expect(expected.endsWith('-3')).toBe(true);

    const h = await head(keys.sha256);
    expect(h.ChecksumSHA256).toBe(expected);
    expect(h.ChecksumType).toBe('COMPOSITE');
    expect(h.ETag?.endsWith('-3"')).toBe(true);

    const attrs = await s3.send(
      new GetObjectAttributesCommand({
        Bucket: bucket,
        Key: keys.sha256,
        ObjectAttributes: ['Checksum', 'ObjectParts', 'ETag'],
      })
    );
    expect(attrs.Checksum?.ChecksumSHA256).toBe(expected.replace(/-3$/, ''));
    expect(attrs.Checksum?.ChecksumType).toBe('COMPOSITE');
    expect(attrs.ObjectParts?.TotalPartsCount).toBe(3);
    expect(attrs.ObjectParts?.Parts?.map((p) => p.ChecksumSHA256)).toEqual(
      pieces.map((p) => sha256(p).toString('base64'))
    );
    // GetObjectAttributes returns the ETag without the quotes HeadObject carries
    expect(attrs.ETag).toBe(h.ETag?.replace(/"/g, ''));
  });

  it('stores what the uploader sends by default and validates the download', async () => {
    await upload(keys.byDefault);

    // Which algorithm and type the SDK picks depends on its version; whatever it sent, the stored
    // value must be the one S3 would compute for that combination.
    const h = await head(keys.byDefault);
    if (h.ChecksumCRC32 && h.ChecksumType === 'COMPOSITE') {
      expect(h.ChecksumCRC32).toBe(composite(crc32Bytes, pieces));
    } else if (h.ChecksumCRC32 && h.ChecksumType === 'FULL_OBJECT') {
      expect(h.ChecksumCRC32).toBe(crc32Bytes(payload).toString('base64'));
    } else if (h.ChecksumCRC64NVME) {
      expect(h.ChecksumType).toBe('FULL_OBJECT');
      expect(h.ChecksumCRC64NVME).toBe(crc64Nvme(payload).toString('base64'));
    } else {
      throw new Error(`unexpected checksum on HeadObject: ${JSON.stringify(h)}`);
    }

    const got = await s3.send(
      new GetObjectCommand({ Bucket: bucket, Key: keys.byDefault, ChecksumMode: 'ENABLED' })
    );
    const body = Buffer.from(await got.Body!.transformToByteArray());
    expect(body.equals(payload)).toBe(true);
  });

  it('writes a copy of the multipart object as a single full object', async () => {
    const source = await head(keys.sha256);
    const copied = await s3.send(
      new CopyObjectCommand({
        Bucket: bucket,
        Key: keys.copy,
        CopySource: `${bucket}/${keys.sha256}`,
      })
    );
    const expectedETag = `"${md5Hex(payload)}"`;
    expect(copied.CopyObjectResult?.ChecksumSHA256).toBe(sha256(payload).toString('base64'));
    expect(copied.CopyObjectResult?.ChecksumType).toBe('FULL_OBJECT');
    expect(copied.CopyObjectResult?.ETag).toBe(expectedETag);
    expect(copied.CopyObjectResult?.ETag).not.toBe(source.ETag);

    const h = await head(keys.copy);
    expect(h.ChecksumSHA256).toBe(sha256(payload).toString('base64'));
    expect(h.ChecksumType).toBe('FULL_OBJECT');
    expect(h.ETag).toBe(expectedETag);
  });
});

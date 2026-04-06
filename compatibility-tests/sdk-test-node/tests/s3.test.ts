/**
 * S3 integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  S3Client,
  CreateBucketCommand,
  PutObjectCommand,
  GetObjectCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
  DeleteBucketCommand,
  HeadObjectCommand,
  HeadBucketCommand,
  ListBucketsCommand,
  CopyObjectCommand,
  GetBucketLocationCommand,
  CreateMultipartUploadCommand,
  UploadPartCopyCommand,
  CompleteMultipartUploadCommand,
  AbortMultipartUploadCommand,
} from '@aws-sdk/client-s3';
import { makeClient, uniqueName, CLIENT_CONFIG } from './setup';

describe('S3', () => {
  let s3: S3Client;
  let euS3: S3Client;
  let bucketName: string;
  let euBucketName: string;
  let signedRegionBucket: string;

  beforeAll(() => {
    s3 = makeClient(S3Client, { forcePathStyle: true });
    euS3 = new S3Client({ ...CLIENT_CONFIG, region: 'eu-central-1', forcePathStyle: true });
    bucketName = `test-bucket-${uniqueName()}`;
    euBucketName = `test-bucket-eu-${uniqueName()}`;
    signedRegionBucket = `test-bucket-signed-${uniqueName()}`;
  });

  afterAll(async () => {
    // Cleanup buckets
    for (const bucket of [bucketName, euBucketName, signedRegionBucket]) {
      try {
        const list = await s3.send(new ListObjectsV2Command({ Bucket: bucket }));
        for (const obj of list.Contents || []) {
          await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: obj.Key }));
        }
        await s3.send(new DeleteBucketCommand({ Bucket: bucket }));
      } catch {
        // ignore
      }
    }
  });

  it('should create bucket', async () => {
    const response = await s3.send(new CreateBucketCommand({ Bucket: bucketName }));
    expect(response.Location).toBe(`/${bucketName}`);
  });

  it('should create bucket with LocationConstraint', async () => {
    const response = await s3.send(
      new CreateBucketCommand({
        Bucket: euBucketName,
        CreateBucketConfiguration: { LocationConstraint: 'eu-central-1' },
      })
    );
    expect(response.Location).toBe(`/${euBucketName}`);
  });

  it('should get bucket location', async () => {
    const response = await s3.send(new GetBucketLocationCommand({ Bucket: euBucketName }));
    expect(response.LocationConstraint).toBe('eu-central-1');
  });

  it('should create bucket using signing region when body empty', async () => {
    await euS3.send(new CreateBucketCommand({ Bucket: signedRegionBucket }));
    const head = await euS3.send(new HeadBucketCommand({ Bucket: signedRegionBucket }));
    expect(head.BucketRegion).toBe('eu-central-1');
    const loc = await euS3.send(new GetBucketLocationCommand({ Bucket: signedRegionBucket }));
    expect(loc.LocationConstraint).toBe('eu-central-1');
  });

  it('should reject explicit us-east-1 LocationConstraint', async () => {
    await expect(
      s3.send(
        new CreateBucketCommand({
          Bucket: `invalid-loc-${uniqueName()}`,
          CreateBucketConfiguration: { LocationConstraint: 'us-east-1' },
        })
      )
    ).rejects.toThrow();
  });

  it('should list buckets', async () => {
    const response = await s3.send(new ListBucketsCommand({}));
    expect(response.Buckets?.some((b) => b.Name === bucketName)).toBe(true);
  });

  it('should put object', async () => {
    await s3.send(
      new PutObjectCommand({ Bucket: bucketName, Key: 'test.txt', Body: 'hello from test' })
    );
  });

  it('should head object', async () => {
    const response = await s3.send(
      new HeadObjectCommand({ Bucket: bucketName, Key: 'test.txt' })
    );
    expect(response.ContentLength).toBeGreaterThan(0);
    expect(response.LastModified).toBeInstanceOf(Date);
  });

  it('should get object', async () => {
    const response = await s3.send(
      new GetObjectCommand({ Bucket: bucketName, Key: 'test.txt' })
    );
    const body = await response.Body?.transformToString();
    expect(body).toBe('hello from test');
  });

  it('should copy object', async () => {
    await s3.send(
      new CopyObjectCommand({
        CopySource: `${bucketName}/test.txt`,
        Bucket: bucketName,
        Key: 'test-copy.txt',
      })
    );
  });

  it('should copy object with non-ASCII key', async () => {
    const nonAsciiKey = 'src/テスト画像.png';
    const nonAsciiDst = 'dst/テスト画像.png';

    await s3.send(
      new PutObjectCommand({
        Bucket: bucketName,
        Key: nonAsciiKey,
        Body: Buffer.from('non-ascii content'),
      })
    );
    await s3.send(
      new CopyObjectCommand({
        CopySource: `${bucketName}/${encodeURIComponent(nonAsciiKey)}`,
        Bucket: bucketName,
        Key: nonAsciiDst,
      })
    );
    const response = await s3.send(
      new GetObjectCommand({ Bucket: bucketName, Key: nonAsciiDst })
    );
    const body = await response.Body?.transformToString();
    expect(body).toBe('non-ascii content');

    // Cleanup
    await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: nonAsciiKey }));
    await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: nonAsciiDst }));
  });

  it('should multipart copy object with non-ASCII key', async () => {
    const srcKey = 'src/テスト画像.bin';
    const dstKey = 'dst/テスト画像.bin';
    const body = Buffer.alloc(6 * 1024 * 1024, 'a');
    let uploadId: string | undefined;

    try {
      await s3.send(
        new PutObjectCommand({
          Bucket: bucketName,
          Key: srcKey,
          Body: body,
        })
      );

      const create = await s3.send(
        new CreateMultipartUploadCommand({
          Bucket: bucketName,
          Key: dstKey,
        })
      );
      uploadId = create.UploadId;
      expect(uploadId).toBeTruthy();

      const part = await s3.send(
        new UploadPartCopyCommand({
          Bucket: bucketName,
          Key: dstKey,
          UploadId: uploadId,
          PartNumber: 1,
          CopySource: `${bucketName}/${encodeURIComponent(srcKey)}`,
        })
      );
      expect(part.CopyPartResult?.ETag).toBeTruthy();

      await s3.send(
        new CompleteMultipartUploadCommand({
          Bucket: bucketName,
          Key: dstKey,
          UploadId: uploadId,
          MultipartUpload: {
            Parts: [
              {
                ETag: part.CopyPartResult?.ETag,
                PartNumber: 1,
              },
            ],
          },
        })
      );
      uploadId = undefined;

      const response = await s3.send(
        new GetObjectCommand({ Bucket: bucketName, Key: dstKey })
      );
      const copied = await response.Body?.transformToByteArray();
      expect(copied && Buffer.from(copied)).toEqual(body);
    } finally {
      if (uploadId) {
        await s3.send(
          new AbortMultipartUploadCommand({
            Bucket: bucketName,
            Key: dstKey,
            UploadId: uploadId,
          })
        ).catch(() => {});
      }
      await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: srcKey })).catch(() => {});
      await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: dstKey })).catch(() => {});
    }
  });

  it('should list objects', async () => {
    const response = await s3.send(new ListObjectsV2Command({ Bucket: bucketName }));
    expect(response.KeyCount).toBeGreaterThanOrEqual(2);
  });

  it('should upload large object (25 MB)', async () => {
    await s3.send(
      new PutObjectCommand({
        Bucket: bucketName,
        Key: 'large-object-25mb.bin',
        Body: Buffer.alloc(25 * 1024 * 1024),
        ContentType: 'application/octet-stream',
      })
    );

    const response = await s3.send(
      new HeadObjectCommand({ Bucket: bucketName, Key: 'large-object-25mb.bin' })
    );
    expect(response.ContentLength).toBe(25 * 1024 * 1024);

    await s3.send(
      new DeleteObjectCommand({ Bucket: bucketName, Key: 'large-object-25mb.bin' })
    );
  });

  it('should delete object', async () => {
    await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: 'test.txt' }));
    await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: 'test-copy.txt' }));
  });

  it('should fail for missing object', async () => {
    await expect(
      s3.send(new GetObjectCommand({ Bucket: bucketName, Key: 'missing.txt' }))
    ).rejects.toThrow();
  });

  it('should delete bucket', async () => {
    await s3.send(new DeleteBucketCommand({ Bucket: bucketName }));
    await s3.send(new DeleteBucketCommand({ Bucket: euBucketName }));
    await euS3.send(new DeleteBucketCommand({ Bucket: signedRegionBucket }));
    bucketName = '';
    euBucketName = '';
    signedRegionBucket = '';
  });
});

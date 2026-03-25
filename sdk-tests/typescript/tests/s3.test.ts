import {
  S3Client,
  CreateBucketCommand,
  ListBucketsCommand,
  PutObjectCommand,
  GetObjectCommand,
  ListObjectsV2Command,
  DeleteObjectCommand,
  DeleteBucketCommand,
} from '@aws-sdk/client-s3';
import { describe, it, expect, afterAll } from 'vitest';
import { randomUUID } from 'crypto';

const s3Client = new S3Client({
  endpoint: process.env.FLOCI_ENDPOINT || 'http://localhost:4566',
  region: process.env.AWS_DEFAULT_REGION || 'us-east-1',
  credentials: {
    accessKeyId: process.env.AWS_ACCESS_KEY_ID || 'test',
    secretAccessKey: process.env.AWS_SECRET_ACCESS_KEY || 'test',
  },
  forcePathStyle: true,
});

// Helper to convert stream to string
async function streamToString(stream: any): Promise<string> {
  const chunks: Buffer[] = [];
  for await (const chunk of stream) {
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf-8');
}

describe('S3 Operations', () => {
  const testBucketName = `test-bucket-${randomUUID().slice(0, 8)}`;
  const createdBuckets: string[] = [];

  afterAll(async () => {
    // Cleanup all created buckets
    for (const bucket of createdBuckets) {
      try {
        // List and delete all objects
        const listResponse = await s3Client.send(
          new ListObjectsV2Command({ Bucket: bucket })
        );
        if (listResponse.Contents) {
          for (const obj of listResponse.Contents) {
            await s3Client.send(
              new DeleteObjectCommand({ Bucket: bucket, Key: obj.Key! })
            );
          }
        }
        // Delete bucket
        await s3Client.send(new DeleteBucketCommand({ Bucket: bucket }));
      } catch (e) {
        // Ignore cleanup errors
      }
    }
  });

  it('should create a bucket', async () => {
    const bucketName = `test-create-${randomUUID().slice(0, 8)}`;
    createdBuckets.push(bucketName);

    const response = await s3Client.send(
      new CreateBucketCommand({ Bucket: bucketName })
    );

    expect(response.$metadata.httpStatusCode).toBe(200);
  });

  it('should list buckets', async () => {
    // Create a bucket first
    createdBuckets.push(testBucketName);
    await s3Client.send(new CreateBucketCommand({ Bucket: testBucketName }));

    const response = await s3Client.send(new ListBucketsCommand({}));

    expect(response.Buckets).toBeDefined();
    const bucketNames = response.Buckets!.map((b) => b.Name);
    expect(bucketNames).toContain(testBucketName);
  });

  it('should put an object', async () => {
    const objectKey = `test-object-${randomUUID().slice(0, 8)}.txt`;
    const objectBody = 'Hello, floci!';

    const response = await s3Client.send(
      new PutObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
        Body: objectBody,
      })
    );

    expect(response.$metadata.httpStatusCode).toBe(200);
  });

  it('should get an object', async () => {
    const objectKey = `test-get-${randomUUID().slice(0, 8)}.txt`;
    const objectBody = 'Hello, floci!';

    // Put object first
    await s3Client.send(
      new PutObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
        Body: objectBody,
      })
    );

    // Get object
    const response = await s3Client.send(
      new GetObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
      })
    );

    const retrievedBody = await streamToString(response.Body);
    expect(retrievedBody).toBe(objectBody);
  });

  it('should list objects', async () => {
    const objectKey = `test-list-${randomUUID().slice(0, 8)}.txt`;

    // Put object first
    await s3Client.send(
      new PutObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
        Body: 'test content',
      })
    );

    // List objects
    const response = await s3Client.send(
      new ListObjectsV2Command({ Bucket: testBucketName })
    );

    expect(response.Contents).toBeDefined();
    const objectKeys = response.Contents!.map((obj) => obj.Key);
    expect(objectKeys).toContain(objectKey);
  });

  it('should delete an object', async () => {
    const objectKey = `test-delete-${randomUUID().slice(0, 8)}.txt`;

    // Put object first
    await s3Client.send(
      new PutObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
        Body: 'test content',
      })
    );

    // Delete object
    const response = await s3Client.send(
      new DeleteObjectCommand({
        Bucket: testBucketName,
        Key: objectKey,
      })
    );

    expect(response.$metadata.httpStatusCode).toBe(204);

    // Verify object is deleted
    const listResponse = await s3Client.send(
      new ListObjectsV2Command({ Bucket: testBucketName })
    );
    const objectKeys = listResponse.Contents?.map((obj) => obj.Key) || [];
    expect(objectKeys).not.toContain(objectKey);
  });

  it('should delete a bucket', async () => {
    const bucketName = `test-delete-bucket-${randomUUID().slice(0, 8)}`;

    // Create bucket
    await s3Client.send(new CreateBucketCommand({ Bucket: bucketName }));

    // Delete bucket
    const response = await s3Client.send(
      new DeleteBucketCommand({ Bucket: bucketName })
    );

    expect(response.$metadata.httpStatusCode).toBe(204);

    // Verify bucket is deleted
    const listResponse = await s3Client.send(new ListBucketsCommand({}));
    const bucketNames = listResponse.Buckets?.map((b) => b.Name) || [];
    expect(bucketNames).not.toContain(bucketName);
  });
});

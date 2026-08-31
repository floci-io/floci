import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import {
  BedrockClient,
  CreateModelInvocationJobCommand,
  GetModelInvocationJobCommand,
  ListModelInvocationJobsCommand,
  StopModelInvocationJobCommand,
} from '@aws-sdk/client-bedrock';
import {
  S3Client,
  CreateBucketCommand,
  DeleteBucketCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  ListObjectsV2Command,
  PutObjectCommand,
} from '@aws-sdk/client-s3';
import { makeClient, uniqueName } from './setup';

describe('Bedrock Batch Inference', () => {
  let bedrock: BedrockClient;
  let s3: S3Client;
  let bucketName: string;

  beforeAll(async () => {
    bedrock = makeClient(BedrockClient);
    s3 = makeClient(S3Client);
    bucketName = uniqueName('bedrock-bucket');
    await s3.send(new CreateBucketCommand({ Bucket: bucketName }));
  });

  afterAll(async () => {
    if (!bucketName) return;
    try {
      const list = await s3.send(new ListObjectsV2Command({ Bucket: bucketName }));
      for (const obj of list.Contents ?? []) {
        if (obj.Key) {
          await s3.send(new DeleteObjectCommand({ Bucket: bucketName, Key: obj.Key })).catch(() => undefined);
        }
      }
      await s3.send(new DeleteBucketCommand({ Bucket: bucketName })).catch(() => undefined);
    } catch {
      // ignore cleanup errors
    }
  });

  it('creates and retrieves a model invocation job', async () => {
    const jobName = uniqueName('batch-job');
    const inputUri = `s3://${bucketName}/in.jsonl`;
    const outputUri = `s3://${bucketName}/out`;

    const createRes = await bedrock.send(
      new CreateModelInvocationJobCommand({
        jobName,
        modelId: 'amazon.titan-text-express-v1',
        roleArn: 'arn:aws:iam::000000000000:role/BedrockBatchRole',
        inputDataConfig: {
          s3InputDataConfig: {
            s3Uri: inputUri,
          },
        },
        outputDataConfig: {
          s3OutputDataConfig: {
            s3Uri: outputUri,
          },
        },
        tags: [{ key: 'Environment', value: 'testing' }],
      })
    );

    expect(createRes.jobArn).toBeDefined();
    expect(createRes.jobArn).toContain(':model-invocation-job/');

    const getRes = await bedrock.send(
      new GetModelInvocationJobCommand({
        jobIdentifier: createRes.jobArn,
      })
    );

    expect(getRes.jobArn).toBe(createRes.jobArn);
    expect(getRes.jobName).toBe(jobName);
    expect(getRes.modelId).toBe('amazon.titan-text-express-v1');
    expect(getRes.status).toBeDefined();
    expect(getRes.inputDataConfig?.s3InputDataConfig?.s3Uri).toBe(inputUri);
    expect(getRes.outputDataConfig?.s3OutputDataConfig?.s3Uri).toBe(outputUri);
  });

  it('lists model invocation jobs with filtering', async () => {
    const jobName1 = uniqueName('list-job-1');
    const jobName2 = uniqueName('list-job-2');

    await bedrock.send(
      new CreateModelInvocationJobCommand({
        jobName: jobName1,
        modelId: 'amazon.titan-text-express-v1',
        roleArn: 'arn:aws:iam::000000000000:role/BedrockBatchRole',
        inputDataConfig: { s3InputDataConfig: { s3Uri: `s3://${bucketName}/1.jsonl` } },
        outputDataConfig: { s3OutputDataConfig: { s3Uri: `s3://${bucketName}/out1` } },
      })
    );

    await bedrock.send(
      new CreateModelInvocationJobCommand({
        jobName: jobName2,
        modelId: 'anthropic.claude-3-sonnet-20240229-v1:0',
        roleArn: 'arn:aws:iam::000000000000:role/BedrockBatchRole',
        inputDataConfig: { s3InputDataConfig: { s3Uri: `s3://${bucketName}/2.jsonl` } },
        outputDataConfig: { s3OutputDataConfig: { s3Uri: `s3://${bucketName}/out2` } },
      })
    );

    const listRes = await bedrock.send(new ListModelInvocationJobsCommand({}));
    expect(listRes.invocationJobSummaries).toBeDefined();
    const names = listRes.invocationJobSummaries!.map((j) => j.jobName);
    expect(names).toContain(jobName1);
    expect(names).toContain(jobName2);

    const filteredRes = await bedrock.send(
      new ListModelInvocationJobsCommand({
        nameContains: jobName1,
      })
    );
    const filteredNames = filteredRes.invocationJobSummaries!.map((j) => j.jobName);
    expect(filteredNames).toContain(jobName1);
    expect(filteredNames).not.toContain(jobName2);
  });

  it('stops an in-flight model invocation job', async () => {
    const jobName = uniqueName('stop-job');
    const createRes = await bedrock.send(
      new CreateModelInvocationJobCommand({
        jobName,
        modelId: 'amazon.titan-text-express-v1',
        roleArn: 'arn:aws:iam::000000000000:role/BedrockBatchRole',
        inputDataConfig: { s3InputDataConfig: { s3Uri: `s3://${bucketName}/stop.jsonl` } },
        outputDataConfig: { s3OutputDataConfig: { s3Uri: `s3://${bucketName}/stop-out` } },
      })
    );

    // If the job already reached a terminal state before stop could be called, skip the stop call
    const terminal = new Set(['Completed', 'Failed', 'Stopped', 'Expired', 'PartiallyCompleted']);
    const current = await bedrock.send(new GetModelInvocationJobCommand({ jobIdentifier: createRes.jobArn }));
    if (!terminal.has(current.status as string)) {
      await bedrock.send(new StopModelInvocationJobCommand({ jobIdentifier: createRes.jobArn }));
    }

    const job = await bedrock.send(
      new GetModelInvocationJobCommand({
        jobIdentifier: createRes.jobArn,
      })
    );
    expect(['Stopping', 'Stopped', 'Failed']).toContain(job.status);
    expect(job.endTime).toBeDefined();
  });

  it('processes batch inference against S3 and produces output and manifest files', async () => {
    const inputKey = 'prompts.jsonl';
    const outputPrefix = uniqueName('batch-out');
    const inputUri = `s3://${bucketName}/${inputKey}`;
    const outputUri = `s3://${bucketName}/${outputPrefix}`;

    const records = [
      { recordId: 'rec-1', modelInput: { inputText: 'Hello Bedrock' } },
      { recordId: 'rec-2', modelInput: { inputText: 'How are you?' } },
    ];
    const jsonlContent = records.map((r) => JSON.stringify(r)).join('\n');

    await s3.send(
      new PutObjectCommand({
        Bucket: bucketName,
        Key: inputKey,
        Body: Buffer.from(jsonlContent, 'utf-8'),
      })
    );

    const jobName = uniqueName('s3-exec-job');
    const createRes = await bedrock.send(
      new CreateModelInvocationJobCommand({
        jobName,
        modelId: 'amazon.titan-text-express-v1',
        roleArn: 'arn:aws:iam::000000000000:role/BedrockBatchRole',
        inputDataConfig: { s3InputDataConfig: { s3Uri: inputUri } },
        outputDataConfig: { s3OutputDataConfig: { s3Uri: outputUri } },
      })
    );

    const jobArn = createRes.jobArn!;
    const jobId = jobArn.split('/').pop()!;

    // Poll until job reaches a terminal status
    const terminal = new Set(['Completed', 'Failed', 'Stopped', 'Expired', 'PartiallyCompleted']);
    const deadline = Date.now() + 30_000;
    let job = await bedrock.send(new GetModelInvocationJobCommand({ jobIdentifier: jobArn }));
    while (!terminal.has(job.status as string) && Date.now() < deadline) {
      await new Promise((r) => setTimeout(r, 500));
      job = await bedrock.send(new GetModelInvocationJobCommand({ jobIdentifier: jobArn }));
    }
    expect(job.status).toBe('Completed');

    // Verify output JSONL file in S3
    const outputObj = await s3.send(
      new GetObjectCommand({
        Bucket: bucketName,
        Key: `${outputPrefix}/${jobId}/${inputKey}.out`,
      })
    );
    const outputStr = await outputObj.Body!.transformToString('utf-8');
    const outputLines = outputStr.trim().split('\n');
    expect(outputLines.length).toBe(2);

    const outRecord1 = JSON.parse(outputLines[0]);
    expect(outRecord1.recordId).toBe('rec-1');
    expect(outRecord1.modelOutput).toBeDefined();

    // Verify manifest file in S3
    const manifestObj = await s3.send(
      new GetObjectCommand({
        Bucket: bucketName,
        Key: `${outputPrefix}/${jobId}/manifest.json.out`,
      })
    );
    const manifestStr = await manifestObj.Body!.transformToString('utf-8');
    const manifest = JSON.parse(manifestStr);

    expect(manifest['totalRecordCount']).toBe(2);
    expect(manifest['processedRecordCount']).toBe(2);
    expect(manifest['successRecordCount']).toBe(2);
    expect(manifest['errorRecordCount']).toBe(0);
  });
});

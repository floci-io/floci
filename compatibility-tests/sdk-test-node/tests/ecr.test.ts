/**
 * ECR control-plane compatibility tests.
 *
 * Test-first: this file is committed before the server-side ECR implementation
 * lands. With ECR unimplemented, every test below should fail.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ECRClient,
  CreateRepositoryCommand,
  DescribeRepositoriesCommand,
  DeleteRepositoryCommand,
  GetAuthorizationTokenCommand,
  ListImagesCommand,
  PutImageTagMutabilityCommand,
  PutLifecyclePolicyCommand,
  GetLifecyclePolicyCommand,
  SetRepositoryPolicyCommand,
  GetRepositoryPolicyCommand,
  ImageTagMutability,
  RepositoryAlreadyExistsException,
  RepositoryNotFoundException,
} from '@aws-sdk/client-ecr';
import { makeClient } from './setup';

const REPO_NAME = 'floci-it/app-node';

describe('ECR Repository Lifecycle', () => {
  let ecr: ECRClient;

  beforeAll(() => {
    ecr = makeClient(ECRClient);
  });

  afterAll(async () => {
    try {
      await ecr.send(new DeleteRepositoryCommand({ repositoryName: REPO_NAME, force: true }));
    } catch {
      // ignore
    }
  });

  it('CreateRepository returns a repositoryUri pointing at loopback', async () => {
    const resp = await ecr.send(new CreateRepositoryCommand({ repositoryName: REPO_NAME }));
    const repo = resp.repository!;
    expect(repo.repositoryName).toBe(REPO_NAME);
    expect(repo.repositoryArn).toMatch(new RegExp(`^arn:aws:ecr:.*:.*:repository/${REPO_NAME}$`));
    expect(repo.repositoryUri).toContain(`/${REPO_NAME}`);
    expect(repo.repositoryUri).toContain('localhost:');
  });

  it('CreateRepository on duplicate name throws RepositoryAlreadyExistsException', async () => {
    await expect(
      ecr.send(new CreateRepositoryCommand({ repositoryName: REPO_NAME })),
    ).rejects.toBeInstanceOf(RepositoryAlreadyExistsException);
  });

  it('DescribeRepositories returns the created repository', async () => {
    const resp = await ecr.send(
      new DescribeRepositoriesCommand({ repositoryNames: [REPO_NAME] }),
    );
    expect(resp.repositories?.[0]?.repositoryName).toBe(REPO_NAME);
  });

  it('GetAuthorizationToken returns a usable docker login token', async () => {
    const resp = await ecr.send(new GetAuthorizationTokenCommand({}));
    expect(resp.authorizationData).toBeTruthy();
    const data = resp.authorizationData![0];
    expect(data.authorizationToken).toBeTruthy();
    expect(data.proxyEndpoint).toMatch(/^https?:\/\//);
    expect(data.expiresAt).toBeDefined();
    const decoded = Buffer.from(data.authorizationToken!, 'base64').toString('utf-8');
    expect(decoded).toMatch(/^AWS:/);
  });

  it('ListImages on an empty repository returns no image identifiers', async () => {
    const resp = await ecr.send(new ListImagesCommand({ repositoryName: REPO_NAME }));
    expect(resp.imageIds ?? []).toEqual([]);
  });

  it('PutImageTagMutability round-trips IMMUTABLE', async () => {
    const resp = await ecr.send(
      new PutImageTagMutabilityCommand({
        repositoryName: REPO_NAME,
        imageTagMutability: ImageTagMutability.IMMUTABLE,
      }),
    );
    expect(resp.imageTagMutability).toBe(ImageTagMutability.IMMUTABLE);
  });

  it('PutLifecyclePolicy round-trips the policy text', async () => {
    const policy =
      '{"rules":[{"rulePriority":1,"selection":{"tagStatus":"untagged","countType":"imageCountMoreThan","countNumber":5},"action":{"type":"expire"}}]}';
    await ecr.send(
      new PutLifecyclePolicyCommand({ repositoryName: REPO_NAME, lifecyclePolicyText: policy }),
    );
    const got = await ecr.send(new GetLifecyclePolicyCommand({ repositoryName: REPO_NAME }));
    expect(got.lifecyclePolicyText).toBe(policy);
  });

  it('SetRepositoryPolicy round-trips the policy text', async () => {
    const policy =
      '{"Version":"2012-10-17","Statement":[{"Sid":"AllowAll","Effect":"Allow","Principal":"*","Action":"ecr:*"}]}';
    await ecr.send(
      new SetRepositoryPolicyCommand({ repositoryName: REPO_NAME, policyText: policy }),
    );
    const got = await ecr.send(new GetRepositoryPolicyCommand({ repositoryName: REPO_NAME }));
    expect(got.policyText).toBe(policy);
  });

  it('DeleteRepository force=true removes the repository', async () => {
    await ecr.send(new DeleteRepositoryCommand({ repositoryName: REPO_NAME, force: true }));
    await expect(
      ecr.send(new DescribeRepositoriesCommand({ repositoryNames: [REPO_NAME] })),
    ).rejects.toBeInstanceOf(RepositoryNotFoundException);
  });

  it('DescribeRepositories on missing name throws RepositoryNotFoundException', async () => {
    await expect(
      ecr.send(
        new DescribeRepositoriesCommand({ repositoryNames: ['does-not-exist-node'] }),
      ),
    ).rejects.toBeInstanceOf(RepositoryNotFoundException);
  });
});

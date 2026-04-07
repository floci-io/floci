/**
 * ACM integration tests.
 */

import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import {
  ACMClient,
  RequestCertificateCommand,
  DescribeCertificateCommand,
  GetCertificateCommand,
  ListCertificatesCommand,
  DeleteCertificateCommand,
  ImportCertificateCommand,
  ExportCertificateCommand,
  AddTagsToCertificateCommand,
  ListTagsForCertificateCommand,
  RemoveTagsFromCertificateCommand,
  PutAccountConfigurationCommand,
  GetAccountConfigurationCommand,
} from '@aws-sdk/client-acm';
import selfsigned from 'selfsigned';
import { makeClient, uniqueName, ACCOUNT, REGION } from './setup';

async function generateSelfSignedCert(): Promise<{ cert: Buffer; key: Buffer }> {
  const attrs = [{ name: 'commonName', value: 'test.example.com' }];
  const pems = await selfsigned.generate(attrs);
  return { cert: Buffer.from(pems.cert), key: Buffer.from(pems.private) };
}

describe('ACM Certificate Lifecycle', () => {
  let acm: ACMClient;
  let certificateArn: string;

  beforeAll(() => {
    acm = makeClient(ACMClient);
  });

  afterAll(async () => {
    try {
      if (certificateArn) {
        await acm.send(new DeleteCertificateCommand({ CertificateArn: certificateArn }));
      }
    } catch {
      // ignore
    }
  });

  it('should request a certificate', async () => {
    const domain = `${uniqueName('cert')}.example.com`;
    const response = await acm.send(
      new RequestCertificateCommand({ DomainName: domain })
    );
    certificateArn = response.CertificateArn!;
    expect(certificateArn).toMatch(
      new RegExp(`^arn:aws:acm:${REGION}:${ACCOUNT}:certificate/.+`)
    );
  });

  it('should describe a certificate', async () => {
    const response = await acm.send(
      new DescribeCertificateCommand({ CertificateArn: certificateArn })
    );
    expect(response.Certificate?.DomainName).toBeTruthy();
    expect(response.Certificate?.Status).toBe('ISSUED');
  });

  it('should get certificate', async () => {
    const response = await acm.send(
      new GetCertificateCommand({ CertificateArn: certificateArn })
    );
    expect(response.Certificate).toBeTruthy();
  });

  it('should list certificates', async () => {
    const response = await acm.send(new ListCertificatesCommand({}));
    expect(
      response.CertificateSummaryList?.some((c) => c.CertificateArn === certificateArn)
    ).toBe(true);
  });

  it('should delete a certificate', async () => {
    await acm.send(new DeleteCertificateCommand({ CertificateArn: certificateArn }));

    await expect(
      acm.send(new DescribeCertificateCommand({ CertificateArn: certificateArn }))
    ).rejects.toThrow();

    // Already deleted; prevent afterAll from trying again
    certificateArn = '';
  });
});

describe('ACM Import/Export', () => {
  let acm: ACMClient;
  const arnsToClean: string[] = [];

  beforeAll(() => {
    acm = makeClient(ACMClient);
  });

  afterAll(async () => {
    for (const arn of arnsToClean) {
      try {
        await acm.send(new DeleteCertificateCommand({ CertificateArn: arn }));
      } catch {
        // ignore
      }
    }
  });

  it('should import a certificate', async () => {
    const { cert, key } = await generateSelfSignedCert();
    const response = await acm.send(
      new ImportCertificateCommand({
        Certificate: cert,
        PrivateKey: key,
      })
    );
    const arn = response.CertificateArn!;
    arnsToClean.push(arn);
    expect(arn).toMatch(
      new RegExp(`^arn:aws:acm:${REGION}:${ACCOUNT}:certificate/.+`)
    );
  });

  it('should get imported certificate', async () => {
    const { cert, key } = await generateSelfSignedCert();
    const importResp = await acm.send(
      new ImportCertificateCommand({
        Certificate: cert,
        PrivateKey: key,
      })
    );
    const arn = importResp.CertificateArn!;
    arnsToClean.push(arn);

    const getResp = await acm.send(
      new GetCertificateCommand({ CertificateArn: arn })
    );
    expect(getResp.Certificate).toBeTruthy();
  });

  it('should export imported certificate', async () => {
    const { cert, key } = await generateSelfSignedCert();
    const importResp = await acm.send(
      new ImportCertificateCommand({
        Certificate: cert,
        PrivateKey: key,
      })
    );
    const arn = importResp.CertificateArn!;
    arnsToClean.push(arn);

    const passphrase = Buffer.from('test-passphrase');
    const exportResp = await acm.send(
      new ExportCertificateCommand({
        CertificateArn: arn,
        Passphrase: passphrase,
      })
    );
    expect(exportResp.Certificate).toBeTruthy();
    expect(exportResp.PrivateKey).toBeTruthy();
  });

  it('should fail to export requested certificate', async () => {
    const domain = `${uniqueName('exp')}.example.com`;
    const reqResp = await acm.send(
      new RequestCertificateCommand({ DomainName: domain })
    );
    const arn = reqResp.CertificateArn!;
    arnsToClean.push(arn);

    await expect(
      acm.send(
        new ExportCertificateCommand({
          CertificateArn: arn,
          Passphrase: Buffer.from('test-passphrase'),
        })
      )
    ).rejects.toThrow();
  });
});

describe('ACM Tagging', () => {
  let acm: ACMClient;
  let certificateArn: string;

  beforeAll(async () => {
    acm = makeClient(ACMClient);
    const domain = `${uniqueName('tag')}.example.com`;
    const response = await acm.send(
      new RequestCertificateCommand({ DomainName: domain })
    );
    certificateArn = response.CertificateArn!;
  });

  afterAll(async () => {
    try {
      if (certificateArn) {
        await acm.send(new DeleteCertificateCommand({ CertificateArn: certificateArn }));
      }
    } catch {
      // ignore
    }
  });

  it('should add and list tags', async () => {
    await acm.send(
      new AddTagsToCertificateCommand({
        CertificateArn: certificateArn,
        Tags: [
          { Key: 'Environment', Value: 'test' },
          { Key: 'Project', Value: 'floci' },
        ],
      })
    );

    const response = await acm.send(
      new ListTagsForCertificateCommand({ CertificateArn: certificateArn })
    );
    const tags = response.Tags ?? [];
    expect(tags.some((t) => t.Key === 'Environment' && t.Value === 'test')).toBe(true);
    expect(tags.some((t) => t.Key === 'Project' && t.Value === 'floci')).toBe(true);
  });

  it('should remove tags', async () => {
    // Ensure tags exist
    await acm.send(
      new AddTagsToCertificateCommand({
        CertificateArn: certificateArn,
        Tags: [{ Key: 'ToRemove', Value: 'yes' }],
      })
    );

    await acm.send(
      new RemoveTagsFromCertificateCommand({
        CertificateArn: certificateArn,
        Tags: [{ Key: 'ToRemove', Value: 'yes' }],
      })
    );

    const response = await acm.send(
      new ListTagsForCertificateCommand({ CertificateArn: certificateArn })
    );
    const tags = response.Tags ?? [];
    expect(tags.some((t) => t.Key === 'ToRemove')).toBe(false);
  });
});

describe('ACM Account Configuration', () => {
  let acm: ACMClient;

  beforeAll(() => {
    acm = makeClient(ACMClient);
  });

  it('should put and get account configuration', async () => {
    await acm.send(
      new PutAccountConfigurationCommand({
        ExpiryEvents: { DaysBeforeExpiry: 45 },
        IdempotencyToken: uniqueName('idem'),
      })
    );

    const response = await acm.send(new GetAccountConfigurationCommand({}));
    expect(response.ExpiryEvents?.DaysBeforeExpiry).toBe(45);
  });
});

describe('ACM Error Handling', () => {
  let acm: ACMClient;
  const arnsToClean: string[] = [];

  beforeAll(() => {
    acm = makeClient(ACMClient);
  });

  afterAll(async () => {
    for (const arn of arnsToClean) {
      try {
        await acm.send(new DeleteCertificateCommand({ CertificateArn: arn }));
      } catch {
        // ignore
      }
    }
  });

  it('should throw for non-existent certificate', async () => {
    const fakeArn = `arn:aws:acm:${REGION}:${ACCOUNT}:certificate/00000000-0000-0000-0000-000000000000`;
    await expect(
      acm.send(new DescribeCertificateCommand({ CertificateArn: fakeArn }))
    ).rejects.toThrow();
  });

  it('should handle request with SANs', async () => {
    const domain = `${uniqueName('san')}.example.com`;
    const san = `alt.${domain}`;
    const response = await acm.send(
      new RequestCertificateCommand({
        DomainName: domain,
        SubjectAlternativeNames: [domain, san],
      })
    );
    const arn = response.CertificateArn!;
    arnsToClean.push(arn);
    expect(arn).toBeTruthy();

    const descResp = await acm.send(
      new DescribeCertificateCommand({ CertificateArn: arn })
    );
    const sans = descResp.Certificate?.SubjectAlternativeNames ?? [];
    expect(sans).toContain(san);
  });

  it('should fail to import invalid PEM', async () => {
    await expect(
      acm.send(
        new ImportCertificateCommand({
          Certificate: Buffer.from('not-a-valid-pem'),
          PrivateKey: Buffer.from('also-not-valid'),
        })
      )
    ).rejects.toThrow();
  });
});

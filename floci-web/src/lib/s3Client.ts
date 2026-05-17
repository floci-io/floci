import { S3Client } from '@aws-sdk/client-s3';
import { FLOCI_ENDPOINT, FLOCI_REGION, FLOCI_CREDENTIALS } from './flociConfig';

export const s3 = new S3Client({
  endpoint: FLOCI_ENDPOINT,
  region: FLOCI_REGION,
  credentials: FLOCI_CREDENTIALS,
  forcePathStyle: true,
  requestChecksumCalculation: 'WHEN_REQUIRED',
  responseChecksumValidation: 'WHEN_REQUIRED',
});

if (import.meta.env.DEV) {
  // Guard against accidental virtual-hosted regression. The Vite proxy only
  // forwards /_s3 path-style requests; virtual-hosted style would hit a
  // bucket-subdomain that the dev server doesn't proxy.
  void (s3.config.forcePathStyle as unknown as Promise<boolean> | boolean);
}

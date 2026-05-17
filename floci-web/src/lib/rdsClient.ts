import { RDSClient } from '@aws-sdk/client-rds';
import { FLOCI_CREDENTIALS, FLOCI_ENDPOINT, FLOCI_REGION } from './flociConfig';

export const rds = new RDSClient({
  endpoint: FLOCI_ENDPOINT,
  region: FLOCI_REGION,
  credentials: FLOCI_CREDENTIALS,
});

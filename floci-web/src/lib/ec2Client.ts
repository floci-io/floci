import { EC2Client } from '@aws-sdk/client-ec2';
import { FLOCI_CREDENTIALS, FLOCI_ENDPOINT, FLOCI_REGION } from './flociConfig';

export const ec2 = new EC2Client({
  endpoint: FLOCI_ENDPOINT,
  region: FLOCI_REGION,
  credentials: FLOCI_CREDENTIALS,
});

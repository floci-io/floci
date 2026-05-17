import { LambdaClient } from '@aws-sdk/client-lambda';
import { FLOCI_CREDENTIALS, FLOCI_ENDPOINT, FLOCI_REGION } from './flociConfig';

export const lambda = new LambdaClient({
  endpoint: FLOCI_ENDPOINT,
  region: FLOCI_REGION,
  credentials: FLOCI_CREDENTIALS,
});

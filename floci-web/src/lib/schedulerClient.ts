import { SchedulerClient } from '@aws-sdk/client-scheduler';
import { FLOCI_CREDENTIALS, FLOCI_ENDPOINT, FLOCI_REGION } from './flociConfig';

export const scheduler = new SchedulerClient({
  endpoint: FLOCI_ENDPOINT,
  region: FLOCI_REGION,
  credentials: FLOCI_CREDENTIALS,
});

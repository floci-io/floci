import {
  DeleteBucketLifecycleCommand,
  GetBucketLifecycleConfigurationCommand,
  PutBucketLifecycleConfigurationCommand,
  type LifecycleRule,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

function isMissing(e: unknown): boolean {
  const err = e as { name?: string; Code?: string };
  return (
    err?.name === 'NoSuchLifecycleConfiguration' ||
    err?.Code === 'NoSuchLifecycleConfiguration'
  );
}

export function useLifecycle(bucket: string) {
  return useQuery({
    queryKey: qk.lifecycle(bucket),
    queryFn: async (): Promise<LifecycleRule[]> => {
      try {
        const r = await s3.send(
          new GetBucketLifecycleConfigurationCommand({ Bucket: bucket }),
        );
        return r.Rules ?? [];
      } catch (e) {
        if (isMissing(e)) return [];
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutLifecycle(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (rules: LifecycleRule[]) => {
      if (rules.length === 0) {
        await s3.send(new DeleteBucketLifecycleCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutBucketLifecycleConfigurationCommand({
          Bucket: bucket,
          LifecycleConfiguration: { Rules: rules },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.lifecycle(bucket) }),
  });
}

import {
  DeleteBucketPolicyCommand,
  GetBucketPolicyCommand,
  PutBucketPolicyCommand,
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
    err?.name === 'NoSuchBucketPolicy' || err?.Code === 'NoSuchBucketPolicy'
  );
}

export function usePolicy(bucket: string) {
  return useQuery({
    queryKey: qk.policy(bucket),
    queryFn: async (): Promise<string | null> => {
      try {
        const r = await s3.send(
          new GetBucketPolicyCommand({ Bucket: bucket }),
        );
        return r.Policy ?? null;
      } catch (e) {
        if (isMissing(e)) return null;
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutPolicy(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (policy: string | null) => {
      if (policy == null || policy.trim() === '') {
        await s3.send(new DeleteBucketPolicyCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutBucketPolicyCommand({ Bucket: bucket, Policy: policy }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.policy(bucket) }),
  });
}

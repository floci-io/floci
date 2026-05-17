import {
  DeleteBucketCorsCommand,
  GetBucketCorsCommand,
  PutBucketCorsCommand,
  type CORSRule,
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
    err?.name === 'NoSuchCORSConfiguration' ||
    err?.Code === 'NoSuchCORSConfiguration'
  );
}

export function useCors(bucket: string) {
  return useQuery({
    queryKey: qk.cors(bucket),
    queryFn: async (): Promise<CORSRule[]> => {
      try {
        const r = await s3.send(new GetBucketCorsCommand({ Bucket: bucket }));
        return r.CORSRules ?? [];
      } catch (e) {
        if (isMissing(e)) return [];
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutCors(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (rules: CORSRule[]) => {
      if (rules.length === 0) {
        await s3.send(new DeleteBucketCorsCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutBucketCorsCommand({
          Bucket: bucket,
          CORSConfiguration: { CORSRules: rules },
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.cors(bucket) }),
  });
}

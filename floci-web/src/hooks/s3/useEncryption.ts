import {
  DeleteBucketEncryptionCommand,
  GetBucketEncryptionCommand,
  PutBucketEncryptionCommand,
  type ServerSideEncryptionRule,
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
    err?.name === 'ServerSideEncryptionConfigurationNotFoundError' ||
    err?.Code === 'ServerSideEncryptionConfigurationNotFoundError'
  );
}

export function useEncryption(bucket: string) {
  return useQuery({
    queryKey: qk.encryption(bucket),
    queryFn: async (): Promise<ServerSideEncryptionRule[]> => {
      try {
        const r = await s3.send(
          new GetBucketEncryptionCommand({ Bucket: bucket }),
        );
        return r.ServerSideEncryptionConfiguration?.Rules ?? [];
      } catch (e) {
        if (isMissing(e)) return [];
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutEncryption(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (rules: ServerSideEncryptionRule[]) => {
      if (rules.length === 0) {
        await s3.send(new DeleteBucketEncryptionCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutBucketEncryptionCommand({
          Bucket: bucket,
          ServerSideEncryptionConfiguration: { Rules: rules },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.encryption(bucket) }),
  });
}

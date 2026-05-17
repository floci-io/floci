import {
  DeletePublicAccessBlockCommand,
  GetPublicAccessBlockCommand,
  PutPublicAccessBlockCommand,
  type PublicAccessBlockConfiguration,
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
    err?.name === 'NoSuchPublicAccessBlockConfiguration' ||
    err?.Code === 'NoSuchPublicAccessBlockConfiguration'
  );
}

export function usePublicAccessBlock(bucket: string) {
  return useQuery({
    queryKey: qk.publicAccessBlock(bucket),
    queryFn: async (): Promise<PublicAccessBlockConfiguration | null> => {
      try {
        const r = await s3.send(
          new GetPublicAccessBlockCommand({ Bucket: bucket }),
        );
        return r.PublicAccessBlockConfiguration ?? null;
      } catch (e) {
        if (isMissing(e)) return null;
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutPublicAccessBlock(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (cfg: PublicAccessBlockConfiguration | null) => {
      if (cfg == null) {
        await s3.send(new DeletePublicAccessBlockCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutPublicAccessBlockCommand({
          Bucket: bucket,
          PublicAccessBlockConfiguration: cfg,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.publicAccessBlock(bucket) }),
  });
}

import {
  CopyObjectCommand,
  DeleteObjectCommand,
  DeleteObjectsCommand,
  ListObjectsV2Command,
  type ListObjectsV2CommandOutput,
} from '@aws-sdk/client-s3';
import {
  useInfiniteQuery,
  useMutation,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useObjects(bucket: string, prefix: string) {
  return useInfiniteQuery({
    queryKey: qk.objects(bucket, prefix),
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) => {
      const r = await s3.send(
        new ListObjectsV2Command({
          Bucket: bucket,
          Prefix: prefix || undefined,
          Delimiter: '/',
          ContinuationToken: pageParam,
          MaxKeys: 200,
        }),
      );
      return r;
    },
    getNextPageParam: (last: ListObjectsV2CommandOutput) =>
      last.IsTruncated ? last.NextContinuationToken : undefined,
    enabled: !!bucket,
  });
}

export function useDeleteObject(bucket: string, prefix: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (key: string) => {
      await s3.send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objects(bucket, prefix) }),
  });
}

export function useDeleteObjects(bucket: string, prefix: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (keys: string[]) => {
      if (keys.length === 0) return { Deleted: [], Errors: [] };
      const r = await s3.send(
        new DeleteObjectsCommand({
          Bucket: bucket,
          Delete: { Objects: keys.map((Key) => ({ Key })), Quiet: false },
        }),
      );
      return r;
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objects(bucket, prefix) }),
  });
}

export function useCopyObject(bucket: string, prefix: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      sourceKey: string;
      destKey: string;
      metadataDirective?: 'COPY' | 'REPLACE';
      contentType?: string;
      metadata?: Record<string, string>;
    }) => {
      await s3.send(
        new CopyObjectCommand({
          Bucket: bucket,
          Key: input.destKey,
          CopySource: `${bucket}/${encodeURIComponent(input.sourceKey)}`,
          MetadataDirective: input.metadataDirective,
          ContentType: input.contentType,
          Metadata: input.metadata,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objects(bucket, prefix) }),
  });
}

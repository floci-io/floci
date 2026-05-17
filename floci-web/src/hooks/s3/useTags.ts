import {
  DeleteBucketTaggingCommand,
  DeleteObjectTaggingCommand,
  GetBucketTaggingCommand,
  GetObjectTaggingCommand,
  PutBucketTaggingCommand,
  PutObjectTaggingCommand,
  type Tag,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

const NO_TAGS_CODES = new Set(['NoSuchTagSet', 'NoSuchTagSetError']);

function isNoTagsError(e: unknown): boolean {
  const err = e as { name?: string; Code?: string };
  return !!err && (NO_TAGS_CODES.has(err.name ?? '') || NO_TAGS_CODES.has(err.Code ?? ''));
}

export function useBucketTags(bucket: string) {
  return useQuery({
    queryKey: qk.bucketTags(bucket),
    queryFn: async (): Promise<Tag[]> => {
      try {
        const r = await s3.send(
          new GetBucketTaggingCommand({ Bucket: bucket }),
        );
        return r.TagSet ?? [];
      } catch (e) {
        if (isNoTagsError(e)) return [];
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutBucketTags(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (tags: Tag[]) => {
      if (tags.length === 0) {
        await s3.send(new DeleteBucketTaggingCommand({ Bucket: bucket }));
        return;
      }
      await s3.send(
        new PutBucketTaggingCommand({
          Bucket: bucket,
          Tagging: { TagSet: tags },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.bucketTags(bucket) }),
  });
}

export function useObjectTags(bucket: string, key: string) {
  return useQuery({
    queryKey: qk.objectTags(bucket, key),
    queryFn: async (): Promise<Tag[]> => {
      const r = await s3.send(
        new GetObjectTaggingCommand({ Bucket: bucket, Key: key }),
      );
      return r.TagSet ?? [];
    },
    enabled: !!bucket && !!key,
  });
}

export function usePutObjectTags(bucket: string, key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (tags: Tag[]) => {
      if (tags.length === 0) {
        await s3.send(
          new DeleteObjectTaggingCommand({ Bucket: bucket, Key: key }),
        );
        return;
      }
      await s3.send(
        new PutObjectTaggingCommand({
          Bucket: bucket,
          Key: key,
          Tagging: { TagSet: tags },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objectTags(bucket, key) }),
  });
}

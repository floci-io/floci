import {
  BucketVersioningStatus,
  DeleteObjectCommand,
  GetBucketVersioningCommand,
  ListObjectVersionsCommand,
  PutBucketVersioningCommand,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useVersioning(bucket: string) {
  return useQuery({
    queryKey: qk.versioning(bucket),
    queryFn: async () => {
      const r = await s3.send(
        new GetBucketVersioningCommand({ Bucket: bucket }),
      );
      return r.Status ?? 'Disabled';
    },
    enabled: !!bucket,
  });
}

export function useSetVersioning(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (status: BucketVersioningStatus) => {
      await s3.send(
        new PutBucketVersioningCommand({
          Bucket: bucket,
          VersioningConfiguration: { Status: status },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.versioning(bucket) }),
  });
}

export function useObjectVersions(bucket: string, prefix: string) {
  return useQuery({
    queryKey: qk.versions(bucket, prefix),
    queryFn: async () => {
      const r = await s3.send(
        new ListObjectVersionsCommand({
          Bucket: bucket,
          Prefix: prefix || undefined,
        }),
      );
      return r;
    },
    enabled: !!bucket,
  });
}

export function useDeleteVersion(bucket: string, prefix: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { key: string; versionId: string }) => {
      await s3.send(
        new DeleteObjectCommand({
          Bucket: bucket,
          Key: input.key,
          VersionId: input.versionId,
        }),
      );
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.versions(bucket, prefix) });
      qc.invalidateQueries({ queryKey: qk.objects(bucket, prefix) });
    },
  });
}

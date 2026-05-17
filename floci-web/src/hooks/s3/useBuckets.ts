import {
  CreateBucketCommand,
  DeleteBucketCommand,
  GetBucketLocationCommand,
  ListBucketsCommand,
  ObjectOwnership,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useBuckets() {
  return useQuery({
    queryKey: qk.buckets(),
    queryFn: async () => {
      const r = await s3.send(new ListBucketsCommand({}));
      return r.Buckets ?? [];
    },
  });
}

export function useBucketLocation(bucket: string) {
  return useQuery({
    queryKey: qk.bucketLocation(bucket),
    queryFn: async () => {
      const r = await s3.send(
        new GetBucketLocationCommand({ Bucket: bucket }),
      );
      return r.LocationConstraint ?? 'us-east-1';
    },
    enabled: !!bucket,
  });
}

export function useCreateBucket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: {
      bucket: string;
      objectLockEnabled?: boolean;
    }) => {
      await s3.send(
        new CreateBucketCommand({
          Bucket: input.bucket,
          ObjectLockEnabledForBucket: input.objectLockEnabled,
          ObjectOwnership: ObjectOwnership.BucketOwnerEnforced,
        }),
      );
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.buckets() }),
  });
}

export function useDeleteBucket() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (bucket: string) => {
      await s3.send(new DeleteBucketCommand({ Bucket: bucket }));
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.buckets() }),
  });
}

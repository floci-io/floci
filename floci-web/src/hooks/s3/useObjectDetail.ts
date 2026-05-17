import {
  GetObjectAclCommand,
  GetObjectAttributesCommand,
  ObjectAttributes,
  PutObjectAclCommand,
  type ObjectCannedACL,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useObjectDetail(bucket: string, key: string) {
  return useQuery({
    queryKey: qk.objectDetail(bucket, key),
    queryFn: async () => {
      const r = await s3.send(
        new GetObjectAttributesCommand({
          Bucket: bucket,
          Key: key,
          ObjectAttributes: [
            ObjectAttributes.ETAG,
            ObjectAttributes.CHECKSUM,
            ObjectAttributes.OBJECT_PARTS,
            ObjectAttributes.STORAGE_CLASS,
            ObjectAttributes.OBJECT_SIZE,
          ],
        }),
      );
      return r;
    },
    enabled: !!bucket && !!key,
  });
}

export function useObjectAcl(bucket: string, key: string) {
  return useQuery({
    queryKey: qk.objectAcl(bucket, key),
    queryFn: async () => {
      const r = await s3.send(
        new GetObjectAclCommand({ Bucket: bucket, Key: key }),
      );
      return r;
    },
    enabled: !!bucket && !!key,
  });
}

export function usePutObjectAcl(bucket: string, key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (cannedAcl: ObjectCannedACL) => {
      await s3.send(
        new PutObjectAclCommand({
          Bucket: bucket,
          Key: key,
          ACL: cannedAcl,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objectAcl(bucket, key) }),
  });
}

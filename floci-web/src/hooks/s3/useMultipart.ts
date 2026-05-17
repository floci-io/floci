import {
  AbortMultipartUploadCommand,
  ListMultipartUploadsCommand,
} from '@aws-sdk/client-s3';
import {
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export function useMultipartUploads(bucket: string) {
  return useQuery({
    queryKey: qk.multipart(bucket),
    queryFn: async () => {
      const r = await s3.send(
        new ListMultipartUploadsCommand({ Bucket: bucket }),
      );
      return r.Uploads ?? [];
    },
    enabled: !!bucket,
  });
}

export function useAbortMultipart(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (input: { key: string; uploadId: string }) => {
      await s3.send(
        new AbortMultipartUploadCommand({
          Bucket: bucket,
          Key: input.key,
          UploadId: input.uploadId,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.multipart(bucket) }),
  });
}

import { Upload } from '@aws-sdk/lib-storage';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { s3 } from '@/lib/s3Client';
import { qk } from '@/lib/queryKeys';

export interface UploadProgress {
  key: string;
  loaded: number;
  total: number;
}

export function useUpload(bucket: string, prefix: string) {
  const qc = useQueryClient();
  const [progress, setProgress] = useState<Record<string, UploadProgress>>({});

  const mutation = useMutation({
    mutationFn: async (input: { key: string; file: File }) => {
      const { key, file } = input;
      setProgress((p) => ({
        ...p,
        [key]: { key, loaded: 0, total: file.size },
      }));
      const upload = new Upload({
        client: s3,
        params: {
          Bucket: bucket,
          Key: key,
          Body: file,
          ContentType: file.type || 'application/octet-stream',
        },
        partSize: 5 * 1024 * 1024,
        queueSize: 4,
      });
      upload.on('httpUploadProgress', (p) => {
        setProgress((cur) => ({
          ...cur,
          [key]: {
            key,
            loaded: p.loaded ?? 0,
            total: p.total ?? file.size,
          },
        }));
      });
      await upload.done();
      setProgress((cur) => {
        const next = { ...cur };
        delete next[key];
        return next;
      });
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objects(bucket, prefix) }),
  });

  return { ...mutation, progress };
}

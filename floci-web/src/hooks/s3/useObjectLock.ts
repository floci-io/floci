import {
  GetObjectLegalHoldCommand,
  GetObjectLockConfigurationCommand,
  GetObjectRetentionCommand,
  ObjectLockEnabled,
  PutObjectLegalHoldCommand,
  PutObjectLockConfigurationCommand,
  PutObjectRetentionCommand,
  type ObjectLockLegalHoldStatus,
  type ObjectLockRetention,
  type ObjectLockRule,
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
    err?.name === 'ObjectLockConfigurationNotFoundError' ||
    err?.Code === 'ObjectLockConfigurationNotFoundError'
  );
}

export interface ObjectLockState {
  enabled: boolean;
  rule?: ObjectLockRule;
}

export function useObjectLock(bucket: string) {
  return useQuery({
    queryKey: qk.objectLock(bucket),
    queryFn: async (): Promise<ObjectLockState> => {
      try {
        const r = await s3.send(
          new GetObjectLockConfigurationCommand({ Bucket: bucket }),
        );
        return {
          enabled:
            r.ObjectLockConfiguration?.ObjectLockEnabled ===
            ObjectLockEnabled.Enabled,
          rule: r.ObjectLockConfiguration?.Rule,
        };
      } catch (e) {
        if (isMissing(e)) return { enabled: false };
        throw e;
      }
    },
    enabled: !!bucket,
  });
}

export function usePutObjectLock(bucket: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (rule: ObjectLockRule | undefined) => {
      await s3.send(
        new PutObjectLockConfigurationCommand({
          Bucket: bucket,
          ObjectLockConfiguration: {
            ObjectLockEnabled: ObjectLockEnabled.Enabled,
            Rule: rule,
          },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.objectLock(bucket) }),
  });
}

export function useObjectRetention(bucket: string, key: string) {
  return useQuery({
    queryKey: qk.retention(bucket, key),
    queryFn: async (): Promise<ObjectLockRetention | null> => {
      try {
        const r = await s3.send(
          new GetObjectRetentionCommand({ Bucket: bucket, Key: key }),
        );
        return r.Retention ?? null;
      } catch {
        return null;
      }
    },
    enabled: !!bucket && !!key,
  });
}

export function usePutRetention(bucket: string, key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (retention: ObjectLockRetention) => {
      await s3.send(
        new PutObjectRetentionCommand({
          Bucket: bucket,
          Key: key,
          Retention: retention,
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.retention(bucket, key) }),
  });
}

export function useLegalHold(bucket: string, key: string) {
  return useQuery({
    queryKey: qk.legalHold(bucket, key),
    queryFn: async (): Promise<ObjectLockLegalHoldStatus> => {
      try {
        const r = await s3.send(
          new GetObjectLegalHoldCommand({ Bucket: bucket, Key: key }),
        );
        return r.LegalHold?.Status ?? 'OFF';
      } catch {
        return 'OFF';
      }
    },
    enabled: !!bucket && !!key,
  });
}

export function usePutLegalHold(bucket: string, key: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (status: ObjectLockLegalHoldStatus) => {
      await s3.send(
        new PutObjectLegalHoldCommand({
          Bucket: bucket,
          Key: key,
          LegalHold: { Status: status },
        }),
      );
    },
    onSuccess: () =>
      qc.invalidateQueries({ queryKey: qk.legalHold(bucket, key) }),
  });
}

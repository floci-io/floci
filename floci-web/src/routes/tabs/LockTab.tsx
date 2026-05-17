import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import type { ObjectLockRetentionMode } from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { useObjectLock, usePutObjectLock } from '@/hooks/s3/useObjectLock';

export default function LockTab() {
  const { bucket = '' } = useParams();
  const { data, isLoading } = useObjectLock(bucket);
  const putLock = usePutObjectLock(bucket);
  const [mode, setMode] = useState<ObjectLockRetentionMode | ''>('');
  const [days, setDays] = useState<number | ''>('');

  useEffect(() => {
    const def = data?.rule?.DefaultRetention;
    setMode((def?.Mode as ObjectLockRetentionMode) ?? '');
    setDays(def?.Days ?? '');
  }, [data]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;

  if (!data?.enabled) {
    return (
      <div className="rounded-md border border-zinc-200 bg-zinc-50 p-4 text-sm text-zinc-600">
        Object Lock is not enabled on this bucket. Object Lock must be enabled
        at bucket creation time and cannot be turned on later.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="rounded-md border border-zinc-200 bg-white p-4 space-y-3">
        <p className="font-medium">Default retention</p>
        <p className="text-sm text-zinc-500">
          Applied automatically to objects uploaded without explicit retention
          headers.
        </p>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <Label>Mode</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={mode}
              onChange={(e) =>
                setMode(e.target.value as ObjectLockRetentionMode | '')
              }
            >
              <option value="">(none)</option>
              <option value="GOVERNANCE">GOVERNANCE</option>
              <option value="COMPLIANCE">COMPLIANCE</option>
            </select>
          </div>
          <div>
            <Label>Days</Label>
            <Input
              type="number"
              value={days}
              onChange={(e) =>
                setDays(e.target.value === '' ? '' : Number(e.target.value))
              }
            />
          </div>
        </div>
        <Button
          disabled={putLock.isPending}
          onClick={async () => {
            try {
              await putLock.mutateAsync(
                mode === '' || days === ''
                  ? undefined
                  : {
                      DefaultRetention: {
                        Mode: mode as ObjectLockRetentionMode,
                        Days: Number(days),
                      },
                    },
              );
              toast.success('Saved');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Save
        </Button>
      </div>
      <p className="text-xs text-zinc-500">
        Set per-object retention or legal hold from each object's detail page.
      </p>
    </div>
  );
}

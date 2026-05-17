import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import type { ServerSideEncryption } from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { Label } from '@/components/ui/input';
import { useEncryption, usePutEncryption } from '@/hooks/s3/useEncryption';

export default function EncryptionTab() {
  const { bucket = '' } = useParams();
  const { data: rules } = useEncryption(bucket);
  const putEnc = usePutEncryption(bucket);
  const [enabled, setEnabled] = useState(false);
  const [algorithm, setAlgorithm] = useState<ServerSideEncryption>('AES256');
  const [bucketKey, setBucketKey] = useState(true);

  useEffect(() => {
    if (rules && rules.length > 0) {
      setEnabled(true);
      const r = rules[0];
      setAlgorithm(
        (r.ApplyServerSideEncryptionByDefault?.SSEAlgorithm as ServerSideEncryption) ?? 'AES256',
      );
      setBucketKey(r.BucketKeyEnabled ?? true);
    } else {
      setEnabled(false);
    }
  }, [rules]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Default server-side encryption applied to objects without explicit
        encryption headers.
      </p>
      <div className="rounded-md border border-zinc-200 bg-white p-4 space-y-3">
        <div className="flex items-center justify-between">
          <Label>Enable default encryption</Label>
          <Switch checked={enabled} onCheckedChange={setEnabled} />
        </div>
        {enabled && (
          <>
            <div>
              <Label>Algorithm</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={algorithm}
                onChange={(e) =>
                  setAlgorithm(e.target.value as ServerSideEncryption)
                }
              >
                <option value="AES256">SSE-S3 (AES256)</option>
              </select>
              <p className="text-xs text-zinc-500 mt-1">
                SSE-KMS is not enabled in this UI by default; verify Floci's
                KMS service before extending.
              </p>
            </div>
            <div className="flex items-center justify-between">
              <Label>Bucket key</Label>
              <Switch checked={bucketKey} onCheckedChange={setBucketKey} />
            </div>
          </>
        )}
      </div>
      <div className="flex gap-2">
        <Button
          disabled={putEnc.isPending}
          onClick={async () => {
            try {
              await putEnc.mutateAsync(
                enabled
                  ? [
                      {
                        ApplyServerSideEncryptionByDefault: {
                          SSEAlgorithm: algorithm,
                        },
                        BucketKeyEnabled: bucketKey,
                      },
                    ]
                  : [],
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
    </div>
  );
}

import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type {
  ExpirationStatus,
  LifecycleRule,
} from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { JsonEditor } from '@/components/JsonEditor';
import { useLifecycle, usePutLifecycle } from '@/hooks/s3/useLifecycle';

export default function LifecycleTab() {
  const { bucket = '' } = useParams();
  const { data: serverRules } = useLifecycle(bucket);
  const putLifecycle = usePutLifecycle(bucket);
  const [rules, setRules] = useState<LifecycleRule[]>([]);
  const [raw, setRaw] = useState('');

  useEffect(() => {
    setRules(serverRules ?? []);
    setRaw(JSON.stringify(serverRules ?? [], null, 2));
  }, [serverRules]);

  function update(i: number, patch: Partial<LifecycleRule>) {
    const next = [...rules];
    next[i] = { ...next[i], ...patch } as LifecycleRule;
    setRules(next);
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Configure object-expiration rules. Saving an empty list deletes the
        lifecycle configuration.
      </p>

      <div className="space-y-3">
        {rules.map((r, i) => (
          <div
            key={i}
            className="rounded-md border border-zinc-200 bg-white p-4 space-y-3"
          >
            <div className="grid grid-cols-3 gap-3">
              <div>
                <Label>ID</Label>
                <Input
                  value={r.ID ?? ''}
                  onChange={(e) => update(i, { ID: e.target.value })}
                />
              </div>
              <div>
                <Label>Status</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={r.Status ?? 'Enabled'}
                  onChange={(e) =>
                    update(i, {
                      Status: e.target.value as ExpirationStatus,
                    })
                  }
                >
                  <option value="Enabled">Enabled</option>
                  <option value="Disabled">Disabled</option>
                </select>
              </div>
              <div>
                <Label>Prefix filter</Label>
                <Input
                  value={r.Filter?.Prefix ?? ''}
                  onChange={(e) =>
                    update(i, {
                      Filter: { Prefix: e.target.value },
                    })
                  }
                />
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Expiration (days)</Label>
                <Input
                  type="number"
                  value={r.Expiration?.Days ?? ''}
                  onChange={(e) =>
                    update(i, {
                      Expiration: {
                        Days: Number(e.target.value) || undefined,
                      },
                    })
                  }
                />
              </div>
              <div>
                <Label>Abort incomplete multipart (days)</Label>
                <Input
                  type="number"
                  value={r.AbortIncompleteMultipartUpload?.DaysAfterInitiation ?? ''}
                  onChange={(e) =>
                    update(i, {
                      AbortIncompleteMultipartUpload: {
                        DaysAfterInitiation:
                          Number(e.target.value) || undefined,
                      },
                    })
                  }
                />
              </div>
            </div>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setRules(rules.filter((_, j) => j !== i))}
            >
              <Trash2 className="size-4" /> Remove rule
            </Button>
          </div>
        ))}
        <Button
          variant="outline"
          size="sm"
          onClick={() =>
            setRules([
              ...rules,
              {
                ID: `rule-${rules.length + 1}`,
                Status: 'Enabled',
                Filter: { Prefix: '' },
              },
            ])
          }
        >
          <Plus className="size-4" /> Add rule
        </Button>
      </div>

      <details className="rounded-md border border-zinc-200 bg-white p-4">
        <summary className="text-sm font-medium cursor-pointer">
          Raw JSON (read-only preview)
        </summary>
        <div className="mt-3">
          <JsonEditor value={raw} onChange={setRaw} readOnly />
        </div>
      </details>

      <div className="flex gap-2">
        <Button
          disabled={putLifecycle.isPending}
          onClick={async () => {
            try {
              await putLifecycle.mutateAsync(rules);
              toast.success('Lifecycle saved');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Save
        </Button>
        <Button variant="outline" onClick={() => setRules(serverRules ?? [])}>
          Reset
        </Button>
      </div>
    </div>
  );
}

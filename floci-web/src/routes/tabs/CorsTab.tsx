import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { CORSRule } from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { useCors, usePutCors } from '@/hooks/s3/useCors';

const METHODS = ['GET', 'PUT', 'POST', 'DELETE', 'HEAD'];

export default function CorsTab() {
  const { bucket = '' } = useParams();
  const { data: serverRules } = useCors(bucket);
  const putCors = usePutCors(bucket);
  const [rules, setRules] = useState<CORSRule[]>([]);

  useEffect(() => {
    setRules(serverRules ?? []);
  }, [serverRules]);

  function update(i: number, patch: Partial<CORSRule>) {
    const next = [...rules];
    next[i] = { ...next[i], ...patch };
    setRules(next);
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Per-bucket CORS rules. The dev frontend bypasses CORS via the Vite
        proxy, but production deployments need these rules.
      </p>

      <div className="space-y-3">
        {rules.map((r, i) => (
          <div
            key={i}
            className="rounded-md border border-zinc-200 bg-white p-4 space-y-3"
          >
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Allowed origins (one per line)</Label>
                <textarea
                  className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-sm min-h-[60px]"
                  value={(r.AllowedOrigins ?? []).join('\n')}
                  onChange={(e) =>
                    update(i, {
                      AllowedOrigins: e.target.value
                        .split('\n')
                        .map((s) => s.trim())
                        .filter(Boolean),
                    })
                  }
                />
              </div>
              <div>
                <Label>Exposed headers (one per line)</Label>
                <textarea
                  className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-sm min-h-[60px]"
                  value={(r.ExposeHeaders ?? []).join('\n')}
                  onChange={(e) =>
                    update(i, {
                      ExposeHeaders: e.target.value
                        .split('\n')
                        .map((s) => s.trim())
                        .filter(Boolean),
                    })
                  }
                />
              </div>
            </div>
            <div>
              <Label>Allowed methods</Label>
              <div className="flex gap-2 flex-wrap mt-1">
                {METHODS.map((m) => {
                  const active = r.AllowedMethods?.includes(m) ?? false;
                  return (
                    <label
                      key={m}
                      className="inline-flex items-center gap-1 text-sm cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={active}
                        onChange={(e) => {
                          const current = new Set(r.AllowedMethods ?? []);
                          if (e.target.checked) current.add(m);
                          else current.delete(m);
                          update(i, { AllowedMethods: [...current] });
                        }}
                      />
                      <span>{m}</span>
                    </label>
                  );
                })}
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Allowed headers (one per line)</Label>
                <textarea
                  className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-sm min-h-[60px]"
                  value={(r.AllowedHeaders ?? []).join('\n')}
                  onChange={(e) =>
                    update(i, {
                      AllowedHeaders: e.target.value
                        .split('\n')
                        .map((s) => s.trim())
                        .filter(Boolean),
                    })
                  }
                />
              </div>
              <div>
                <Label>Max age (seconds)</Label>
                <Input
                  type="number"
                  value={r.MaxAgeSeconds ?? ''}
                  onChange={(e) =>
                    update(i, {
                      MaxAgeSeconds: Number(e.target.value) || undefined,
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
                AllowedOrigins: ['*'],
                AllowedMethods: ['GET'],
                AllowedHeaders: ['*'],
                MaxAgeSeconds: 3000,
              },
            ])
          }
        >
          <Plus className="size-4" /> Add rule
        </Button>
      </div>

      <div className="flex gap-2">
        <Button
          disabled={putCors.isPending}
          onClick={async () => {
            try {
              await putCors.mutateAsync(rules);
              toast.success('CORS saved');
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

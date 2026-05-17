import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Plus, Trash2 } from 'lucide-react';
import { Architecture, TracingMode, type Runtime } from '@aws-sdk/client-lambda';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useFunction,
  useUpdateFunctionConfiguration,
} from '@/hooks/lambda/useFunctions';
import {
  LAMBDA_ARCHITECTURES,
  LAMBDA_RUNTIMES,
} from '../runtimes';

type EnvEntry = [string, string];

function envToEntries(env?: Record<string, string>): EnvEntry[] {
  return Object.entries(env ?? {});
}

export default function ConfigurationTab() {
  const { name = '' } = useParams();
  const { data, isLoading } = useFunction(name);
  const update = useUpdateFunctionConfiguration();
  const cfg = data?.Configuration;

  const [runtime, setRuntime] = useState('');
  const [handler, setHandler] = useState('');
  const [role, setRole] = useState('');
  const [memory, setMemory] = useState(128);
  const [timeout, setTimeout] = useState(3);
  const [arch, setArch] = useState<Architecture>(Architecture.x86_64);
  const [tracing, setTracing] = useState<TracingMode>(TracingMode.PassThrough);
  const [description, setDescription] = useState('');
  const [env, setEnv] = useState<EnvEntry[]>([]);

  useEffect(() => {
    setRuntime(cfg?.Runtime ?? '');
    setHandler(cfg?.Handler ?? '');
    setRole(cfg?.Role ?? '');
    setMemory(cfg?.MemorySize ?? 128);
    setTimeout(cfg?.Timeout ?? 3);
    setArch((cfg?.Architectures?.[0] as Architecture) ?? Architecture.x86_64);
    setTracing(
      (cfg?.TracingConfig?.Mode as TracingMode) ?? TracingMode.PassThrough,
    );
    setDescription(cfg?.Description ?? '');
    setEnv(envToEntries(cfg?.Environment?.Variables));
  }, [cfg]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;

  async function save() {
    const envObj: Record<string, string> = {};
    for (const [k, v] of env) {
      if (k) envObj[k] = v;
    }
    try {
      await update.mutateAsync({
        FunctionName: name,
        Runtime: runtime as Runtime,
        Handler: handler,
        Role: role,
        MemorySize: memory,
        Timeout: timeout,
        Description: description,
        TracingConfig: { Mode: tracing },
        Environment: { Variables: envObj },
      });
      toast.success('Configuration updated');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Runtime</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3">
          <div>
            <Label>Runtime</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={runtime}
              onChange={(e) => setRuntime(e.target.value)}
            >
              {LAMBDA_RUNTIMES.map((r) => (
                <option key={r} value={r}>
                  {r}
                </option>
              ))}
              {runtime && !LAMBDA_RUNTIMES.includes(runtime as never) && (
                <option value={runtime}>{runtime}</option>
              )}
            </select>
          </div>
          <div>
            <Label>Architecture (read-only)</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm bg-zinc-50"
              value={arch}
              disabled
            >
              {LAMBDA_ARCHITECTURES.map((a) => (
                <option key={a} value={a}>
                  {a}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Handler</Label>
            <Input value={handler} onChange={(e) => setHandler(e.target.value)} />
          </div>
          <div>
            <Label>Role ARN</Label>
            <Input value={role} onChange={(e) => setRole(e.target.value)} />
          </div>
          <div>
            <Label>Memory (MB)</Label>
            <Input
              type="number"
              value={memory}
              onChange={(e) => setMemory(Number(e.target.value) || 128)}
            />
          </div>
          <div>
            <Label>Timeout (s)</Label>
            <Input
              type="number"
              value={timeout}
              onChange={(e) => setTimeout(Number(e.target.value) || 3)}
            />
          </div>
          <div>
            <Label>Tracing</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={tracing}
              onChange={(e) => setTracing(e.target.value as TracingMode)}
            >
              <option value={TracingMode.PassThrough}>PassThrough</option>
              <option value={TracingMode.Active}>Active</option>
            </select>
          </div>
          <div>
            <Label>Description</Label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Environment variables</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {env.map(([k, v], i) => (
            <div key={i} className="flex gap-2">
              <Input
                placeholder="KEY"
                className="font-mono text-xs"
                value={k}
                onChange={(e) => {
                  const next = [...env];
                  next[i] = [e.target.value, next[i][1]];
                  setEnv(next);
                }}
              />
              <Input
                placeholder="value"
                className="font-mono text-xs"
                value={v}
                onChange={(e) => {
                  const next = [...env];
                  next[i] = [next[i][0], e.target.value];
                  setEnv(next);
                }}
              />
              <Button
                variant="ghost"
                size="icon"
                onClick={() => setEnv(env.filter((_, j) => j !== i))}
              >
                <Trash2 className="size-4" />
              </Button>
            </div>
          ))}
          <Button
            variant="outline"
            size="sm"
            onClick={() => setEnv([...env, ['', '']])}
          >
            <Plus className="size-4" /> Add variable
          </Button>
        </CardContent>
      </Card>

      <div className="flex gap-2">
        <Button onClick={save} disabled={update.isPending}>
          Save configuration
        </Button>
      </div>
    </div>
  );
}

import { useState } from 'react';
import { toast } from 'sonner';
import { Architecture, type FunctionCode, type Runtime } from '@aws-sdk/client-lambda';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { useCreateFunction } from '@/hooks/lambda/useFunctions';
import {
  LAMBDA_ARCHITECTURES,
  LAMBDA_RUNTIMES,
  fileToUint8Array,
} from './runtimes';

type CodeSource = 'zip' | 's3' | 'hot-reload';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
}

export function CreateFunctionDialog({ open, onOpenChange }: Props) {
  const create = useCreateFunction();
  const [name, setName] = useState('');
  const [runtime, setRuntime] = useState<string>('nodejs22.x');
  const [handler, setHandler] = useState('index.handler');
  const [role, setRole] = useState(
    'arn:aws:iam::000000000000:role/lambda-role',
  );
  const [memory, setMemory] = useState(128);
  const [timeout, setTimeout] = useState(3);
  const [architecture, setArchitecture] =
    useState<(typeof LAMBDA_ARCHITECTURES)[number]>('x86_64');
  const [codeSource, setCodeSource] = useState<CodeSource>('zip');
  const [zipFile, setZipFile] = useState<File | null>(null);
  const [s3Bucket, setS3Bucket] = useState('');
  const [s3Key, setS3Key] = useState('');
  const [hotPath, setHotPath] = useState('');
  const [envText, setEnvText] = useState('');

  function reset() {
    setName('');
    setHandler('index.handler');
    setMemory(128);
    setTimeout(3);
    setZipFile(null);
    setS3Bucket('');
    setS3Key('');
    setHotPath('');
    setEnvText('');
  }

  function parseEnv(): Record<string, string> | undefined {
    const lines = envText
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);
    if (lines.length === 0) return undefined;
    const out: Record<string, string> = {};
    for (const line of lines) {
      const idx = line.indexOf('=');
      if (idx === -1) continue;
      out[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
    }
    return Object.keys(out).length > 0 ? out : undefined;
  }

  const valid =
    name &&
    runtime &&
    handler &&
    role &&
    ((codeSource === 'zip' && zipFile) ||
      (codeSource === 's3' && s3Bucket && s3Key) ||
      (codeSource === 'hot-reload' && hotPath));

  async function submit() {
    if (!valid) return;
    let code: FunctionCode = {};
    if (codeSource === 'zip' && zipFile) {
      code = { ZipFile: await fileToUint8Array(zipFile) };
    } else if (codeSource === 's3') {
      code = { S3Bucket: s3Bucket, S3Key: s3Key };
    } else if (codeSource === 'hot-reload') {
      code = { S3Bucket: 'hot-reload', S3Key: hotPath };
    }
    const env = parseEnv();
    try {
      await create.mutateAsync({
        FunctionName: name,
        Runtime: runtime as Runtime,
        Handler: handler,
        Role: role,
        MemorySize: memory,
        Timeout: timeout,
        Architectures: [architecture as Architecture],
        Environment: env ? { Variables: env } : undefined,
        Code: code,
      });
      toast.success(`Created ${name}`);
      onOpenChange(false);
      reset();
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Create failed');
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Create function</DialogTitle>
          <DialogDescription>
            Floci runs the function in a real Docker container matching the
            runtime image.
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <div className="col-span-2">
            <Label>Function name</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="my-function"
            />
          </div>
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
            </select>
          </div>
          <div>
            <Label>Architecture</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={architecture}
              onChange={(e) =>
                setArchitecture(
                  e.target.value as (typeof LAMBDA_ARCHITECTURES)[number],
                )
              }
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
            <Input
              value={handler}
              onChange={(e) => setHandler(e.target.value)}
            />
          </div>
          <div>
            <Label>Role ARN</Label>
            <Input
              value={role}
              onChange={(e) => setRole(e.target.value)}
            />
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
        </div>

        <div className="space-y-2 rounded-md border border-zinc-200 p-3">
          <Label>Code source</Label>
          <div className="flex gap-3 text-sm">
            {(['zip', 's3', 'hot-reload'] as CodeSource[]).map((s) => (
              <label key={s} className="inline-flex items-center gap-1">
                <input
                  type="radio"
                  name="code-source"
                  value={s}
                  checked={codeSource === s}
                  onChange={() => setCodeSource(s)}
                />
                <span>
                  {s === 'zip'
                    ? 'Upload ZIP'
                    : s === 's3'
                      ? 'From S3'
                      : 'Hot-reload bind mount'}
                </span>
              </label>
            ))}
          </div>
          {codeSource === 'zip' && (
            <div>
              <Input
                type="file"
                accept=".zip,application/zip"
                onChange={(e) => setZipFile(e.target.files?.[0] ?? null)}
              />
              {zipFile && (
                <p className="text-xs text-zinc-500 mt-1">
                  {zipFile.name} · {(zipFile.size / 1024).toFixed(1)} KB
                </p>
              )}
            </div>
          )}
          {codeSource === 's3' && (
            <div className="grid grid-cols-2 gap-2">
              <Input
                placeholder="S3 bucket"
                value={s3Bucket}
                onChange={(e) => setS3Bucket(e.target.value)}
              />
              <Input
                placeholder="S3 key (e.g. function.zip)"
                value={s3Key}
                onChange={(e) => setS3Key(e.target.value)}
              />
              <p className="col-span-2 text-xs text-zinc-500">
                Floci hot-syncs the function whenever the S3 object changes.
              </p>
            </div>
          )}
          {codeSource === 'hot-reload' && (
            <div>
              <Input
                placeholder="/absolute/host/path/to/code"
                value={hotPath}
                onChange={(e) => setHotPath(e.target.value)}
              />
              <p className="text-xs text-zinc-500 mt-1">
                Requires <code>FLOCI_SERVICES_LAMBDA_HOT_RELOAD_ENABLED=true</code>.
                Path is interpreted by the Docker daemon — use the host path,
                not a path inside the Floci container.
              </p>
            </div>
          )}
        </div>

        <div>
          <Label>Environment variables (KEY=value, one per line)</Label>
          <textarea
            className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-sm min-h-[80px] font-mono"
            value={envText}
            onChange={(e) => setEnvText(e.target.value)}
            placeholder="LOG_LEVEL=info"
          />
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button disabled={!valid || create.isPending} onClick={submit}>
            {create.isPending ? 'Creating…' : 'Create'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Play } from 'lucide-react';
import { toast } from 'sonner';
import { InvocationType, LogType } from '@aws-sdk/client-lambda';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { JsonEditor } from '@/components/JsonEditor';
import { useInvoke } from '@/hooks/lambda/useInvoke';

export default function InvokeTab() {
  const { name = '' } = useParams();
  const invoke = useInvoke();
  const [payload, setPayload] = useState('{}');
  const [invocationType, setInvocationType] = useState<InvocationType>(
    InvocationType.RequestResponse,
  );
  const [logType, setLogType] = useState<LogType>(LogType.None);
  const [qualifier, setQualifier] = useState('');

  async function run() {
    if (payload.trim() !== '') {
      try {
        JSON.parse(payload);
      } catch {
        toast.error('Payload is not valid JSON');
        return;
      }
    }
    try {
      await invoke.mutateAsync({
        functionName: name,
        payload,
        invocationType,
        logType,
        qualifier: qualifier || undefined,
      });
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Invocation failed');
    }
  }

  const result = invoke.data;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Invoke</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label>Invocation type</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={invocationType}
                onChange={(e) =>
                  setInvocationType(e.target.value as InvocationType)
                }
              >
                <option value={InvocationType.RequestResponse}>
                  RequestResponse (sync)
                </option>
                <option value={InvocationType.Event}>Event (async)</option>
                <option value={InvocationType.DryRun}>DryRun</option>
              </select>
            </div>
            <div>
              <Label>Log type</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={logType}
                onChange={(e) => setLogType(e.target.value as LogType)}
              >
                <option value={LogType.None}>None</option>
                <option value={LogType.Tail}>Tail (last 4 KB)</option>
              </select>
            </div>
            <div>
              <Label>Qualifier (version / alias)</Label>
              <Input
                value={qualifier}
                onChange={(e) => setQualifier(e.target.value)}
                placeholder="$LATEST"
              />
            </div>
          </div>
          <div>
            <Label>Payload (JSON)</Label>
            <JsonEditor
              value={payload}
              onChange={setPayload}
              minHeight="200px"
            />
          </div>
          <Button onClick={run} disabled={invoke.isPending}>
            <Play className="size-4" />{' '}
            {invoke.isPending ? 'Invoking…' : 'Invoke'}
          </Button>
        </CardContent>
      </Card>

      {result && (
        <Card>
          <CardHeader>
            <CardTitle>Result</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="grid grid-cols-3 gap-3 text-sm">
              <div>
                <p className="text-zinc-500">Status code</p>
                <p>{result.statusCode ?? '—'}</p>
              </div>
              <div>
                <p className="text-zinc-500">Function error</p>
                <p>{result.functionError ?? '(none)'}</p>
              </div>
              <div>
                <p className="text-zinc-500">Executed version</p>
                <p>{result.executedVersion ?? '—'}</p>
              </div>
            </div>
            {result.payload && (
              <div>
                <Label>Response payload</Label>
                <pre className="mt-1 text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[320px]">
                  {result.payload}
                </pre>
              </div>
            )}
            {result.logTail && (
              <div>
                <Label>Log tail</Label>
                <pre className="mt-1 text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[240px] whitespace-pre-wrap">
                  {result.logTail}
                </pre>
              </div>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

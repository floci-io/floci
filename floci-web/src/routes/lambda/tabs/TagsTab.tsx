import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useFunction } from '@/hooks/lambda/useFunctions';
import {
  useFunctionTags,
  useSaveFunctionTags,
} from '@/hooks/lambda/useFunctionTags';

type Entry = [string, string];

function toEntries(t: Record<string, string> | undefined): Entry[] {
  return Object.entries(t ?? {});
}

export default function TagsTab() {
  const { name = '' } = useParams();
  const { data: fn } = useFunction(name);
  const arn = fn?.Configuration?.FunctionArn ?? '';

  const { data: serverTags } = useFunctionTags(arn);
  const save = useSaveFunctionTags(arn);
  const [tags, setTags] = useState<Entry[]>([]);

  useEffect(() => {
    setTags(toEntries(serverTags));
  }, [serverTags]);

  async function commit() {
    const original = serverTags ?? {};
    const desired = Object.fromEntries(tags.filter(([k]) => k));
    const removeKeys = Object.keys(original).filter((k) => !(k in desired));
    const add: Record<string, string> = {};
    for (const [k, v] of Object.entries(desired)) {
      if (original[k] !== v) add[k] = v;
    }
    try {
      await save.mutateAsync({ add, removeKeys });
      toast.success('Saved');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-zinc-500">
        Function-level tags. ARN: <code className="text-xs">{arn || '—'}</code>
      </p>
      {tags.map(([k, v], i) => (
        <div key={i} className="flex gap-2">
          <Input
            placeholder="Key"
            value={k}
            onChange={(e) => {
              const next = [...tags];
              next[i] = [e.target.value, next[i][1]];
              setTags(next);
            }}
          />
          <Input
            placeholder="Value"
            value={v}
            onChange={(e) => {
              const next = [...tags];
              next[i] = [next[i][0], e.target.value];
              setTags(next);
            }}
          />
          <Button
            variant="ghost"
            size="icon"
            onClick={() => setTags(tags.filter((_, j) => j !== i))}
          >
            <Trash2 className="size-4" />
          </Button>
        </div>
      ))}
      <Button
        variant="outline"
        size="sm"
        onClick={() => setTags([...tags, ['', '']])}
      >
        <Plus className="size-4" /> Add tag
      </Button>
      <div>
        <Button disabled={!arn || save.isPending} onClick={commit}>
          Save tags
        </Button>
      </div>
    </div>
  );
}

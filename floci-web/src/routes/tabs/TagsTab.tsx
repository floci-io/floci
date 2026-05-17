import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { Tag } from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { useBucketTags, usePutBucketTags } from '@/hooks/s3/useTags';

export default function TagsTab() {
  const { bucket = '' } = useParams();
  const { data: serverTags } = useBucketTags(bucket);
  const putTags = usePutBucketTags(bucket);
  const [tags, setTags] = useState<Tag[]>([]);

  useEffect(() => {
    setTags(serverTags ?? []);
  }, [serverTags]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Bucket-level tags. Saving an empty list deletes the tagging
        configuration.
      </p>
      <div className="space-y-2">
        {tags.map((t, i) => (
          <div key={i} className="flex gap-2">
            <Input
              placeholder="Key"
              value={t.Key ?? ''}
              onChange={(e) => {
                const next = [...tags];
                next[i] = { ...next[i], Key: e.target.value };
                setTags(next);
              }}
            />
            <Input
              placeholder="Value"
              value={t.Value ?? ''}
              onChange={(e) => {
                const next = [...tags];
                next[i] = { ...next[i], Value: e.target.value };
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
          onClick={() => setTags([...tags, { Key: '', Value: '' }])}
        >
          <Plus className="size-4" /> Add tag
        </Button>
      </div>
      <div className="flex gap-2">
        <Button
          disabled={putTags.isPending}
          onClick={async () => {
            try {
              await putTags.mutateAsync(
                tags.filter((t) => (t.Key ?? '') !== ''),
              );
              toast.success('Tags saved');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Save
        </Button>
        <Button variant="outline" onClick={() => setTags(serverTags ?? [])}>
          Reset
        </Button>
      </div>
    </div>
  );
}

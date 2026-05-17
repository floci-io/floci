import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { Tag } from '@aws-sdk/client-scheduler';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useDeleteScheduleGroup,
  useScheduleGroup,
} from '@/hooks/scheduler/useScheduleGroups';
import {
  useSaveScheduleGroupTags,
  useScheduleGroupTags,
} from '@/hooks/scheduler/useScheduleTags';
import { useSchedules } from '@/hooks/scheduler/useSchedules';
import { formatDate } from '@/lib/format';

export default function ScheduleGroupDetailPage() {
  const { name = '' } = useParams();
  const nav = useNavigate();
  const { data: group, isLoading } = useScheduleGroup(name);
  const arn = group?.Arn ?? '';
  const { data: serverTags = [] } = useScheduleGroupTags(arn);
  const saveTags = useSaveScheduleGroupTags(arn);
  const { data: schedules = [] } = useSchedules(name);
  const del = useDeleteScheduleGroup();

  const [tags, setTags] = useState<Tag[]>([]);

  useEffect(() => {
    setTags(serverTags);
  }, [serverTags]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!group) {
    return (
      <div className="space-y-4">
        <Link
          to="/scheduler/groups"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Schedule groups
        </Link>
        <p className="text-sm text-zinc-500">Group not found.</p>
      </div>
    );
  }

  async function commitTags() {
    const desired: Record<string, string> = {};
    for (const t of tags) {
      if (t.Key) desired[t.Key] = t.Value ?? '';
    }
    const original: Record<string, string> = {};
    for (const t of serverTags) {
      if (t.Key) original[t.Key] = t.Value ?? '';
    }
    const removeKeys = Object.keys(original).filter((k) => !(k in desired));
    const addOrUpdate: Tag[] = [];
    for (const [k, v] of Object.entries(desired)) {
      if (original[k] !== v) addOrUpdate.push({ Key: k, Value: v });
    }
    if (removeKeys.length === 0 && addOrUpdate.length === 0) {
      toast.message('No changes');
      return;
    }
    try {
      await saveTags.mutateAsync({ addOrUpdate, removeKeys });
      toast.success('Tags saved');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  return (
    <div className="space-y-4">
      <Link
        to="/scheduler/groups"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Schedule groups
      </Link>

      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{group.Name}</h1>
          <p className="text-xs text-zinc-500 mt-1">
            <span className="font-mono break-all">{group.Arn}</span>
          </p>
        </div>
        <Button
          variant="destructive"
          disabled={group.Name === 'default'}
          onClick={async () => {
            if (
              !confirm(
                `Delete group "${group.Name}"? Schedules inside it will also be deleted.`,
              )
            )
              return;
            try {
              await del.mutateAsync(group.Name!);
              toast.success('Deleted');
              nav('/scheduler/groups');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          <Trash2 className="size-4" /> Delete
        </Button>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-3 gap-3 text-sm">
          <div>
            <p className="text-zinc-500">State</p>
            <p>{group.State ?? '—'}</p>
          </div>
          <div>
            <p className="text-zinc-500">Created</p>
            <p>{formatDate(group.CreationDate)}</p>
          </div>
          <div>
            <p className="text-zinc-500">Modified</p>
            <p>{formatDate(group.LastModificationDate)}</p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Tags ({tags.length})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
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
          <div className="flex gap-2 pt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setTags([...tags, { Key: '', Value: '' }])}
            >
              <Plus className="size-4" /> Add tag
            </Button>
            <Button size="sm" onClick={commitTags} disabled={saveTags.isPending}>
              Save tags
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Schedules in this group ({schedules.length})</CardTitle>
        </CardHeader>
        <CardContent>
          {schedules.length === 0 ? (
            <p className="text-sm text-zinc-500">No schedules.</p>
          ) : (
            <ul className="space-y-1 text-sm">
              {schedules.map((s) => (
                <li key={s.Name}>
                  <Link
                    to={`/scheduler/schedules/${encodeURIComponent(name)}/${encodeURIComponent(s.Name!)}`}
                    className="font-medium hover:underline"
                  >
                    {s.Name}
                  </Link>{' '}
                  <span className="text-xs text-zinc-500">
                    · {s.State} · {s.Target?.Arn?.split(':').slice(-1)[0]}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

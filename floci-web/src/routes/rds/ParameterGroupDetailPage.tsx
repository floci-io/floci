import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Save } from 'lucide-react';
import { toast } from 'sonner';
import type { ApplyMethod, Parameter } from '@aws-sdk/client-rds';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useModifyParameters,
  useParameters,
} from '@/hooks/rds/useParameterGroups';

export default function ParameterGroupDetailPage() {
  const { name = '' } = useParams();
  const { data: serverParams, isLoading } = useParameters(name);
  const modify = useModifyParameters(name);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [filter, setFilter] = useState('');

  useEffect(() => {
    setEdits({});
  }, [serverParams]);

  const filtered = useMemo(() => {
    const all = serverParams ?? [];
    if (!filter) return all;
    const q = filter.toLowerCase();
    return all.filter(
      (p) =>
        p.ParameterName?.toLowerCase().includes(q) ||
        p.Description?.toLowerCase().includes(q),
    );
  }, [serverParams, filter]);

  function effectiveValue(p: Parameter): string {
    if (p.ParameterName && p.ParameterName in edits) {
      return edits[p.ParameterName];
    }
    return p.ParameterValue ?? '';
  }

  async function save() {
    const changes: Parameter[] = [];
    for (const [k, v] of Object.entries(edits)) {
      const original = serverParams?.find((p) => p.ParameterName === k);
      if (!original) continue;
      if ((original.ParameterValue ?? '') === v) continue;
      changes.push({
        ParameterName: k,
        ParameterValue: v,
        ApplyMethod:
          original.ApplyType === 'dynamic'
            ? ('immediate' as ApplyMethod)
            : ('pending-reboot' as ApplyMethod),
      });
    }

    if (changes.length === 0) {
      toast.message('No changes to save');
      return;
    }

    try {
      await modify.mutateAsync(changes);
      toast.success(`Saved ${changes.length} parameter(s)`);
      setEdits({});
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  const dirtyCount = Object.keys(edits).filter((k) => {
    const orig = serverParams?.find((p) => p.ParameterName === k);
    return orig && (orig.ParameterValue ?? '') !== edits[k];
  }).length;

  return (
    <div className="space-y-4">
      <Link
        to="/rds/parameter-groups"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Parameter groups
      </Link>
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-2xl font-semibold">{name}</h1>
        <div className="flex gap-2 items-center">
          <Input
            placeholder="Filter…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="w-56"
          />
          <Button
            onClick={save}
            disabled={dirtyCount === 0 || modify.isPending}
          >
            <Save className="size-4" /> Save ({dirtyCount})
          </Button>
        </div>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Parameter</TableHead>
            <TableHead>Value</TableHead>
            <TableHead>Apply type</TableHead>
            <TableHead>Data type</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                Loading parameters…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && filtered.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                No parameters match.
              </TableCell>
            </TableRow>
          )}
          {filtered.map((p) => {
            const modifiable = p.IsModifiable !== false;
            return (
              <TableRow key={p.ParameterName}>
                <TableCell>
                  <div className="font-medium">{p.ParameterName}</div>
                  {p.Description && (
                    <div className="text-xs text-zinc-500 mt-0.5">
                      {p.Description}
                    </div>
                  )}
                </TableCell>
                <TableCell>
                  <Input
                    className="font-mono text-xs"
                    disabled={!modifiable}
                    value={effectiveValue(p)}
                    onChange={(e) =>
                      setEdits({
                        ...edits,
                        [p.ParameterName!]: e.target.value,
                      })
                    }
                  />
                </TableCell>
                <TableCell className="text-xs text-zinc-600">
                  {p.ApplyType ?? '—'}
                </TableCell>
                <TableCell className="text-xs text-zinc-600">
                  {p.DataType ?? '—'}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>
    </div>
  );
}

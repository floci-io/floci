import { useMemo, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ChevronRight,
  Download,
  Folder,
  Link as LinkIcon,
  Trash2,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Uploader } from '@/components/Uploader';
import { PresignDialog } from '@/components/PresignDialog';
import { useDeleteObjects, useObjects } from '@/hooks/s3/useObjects';
import { presignGet } from '@/lib/presign';
import { formatBytes, formatDate, trimEtag } from '@/lib/format';

export default function ObjectsTab() {
  const { bucket = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const prefix = params.get('prefix') ?? '';
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [presignFor, setPresignFor] = useState<string | null>(null);

  const { data, isLoading, fetchNextPage, hasNextPage, isFetchingNextPage } =
    useObjects(bucket, prefix);
  const deleteObjects = useDeleteObjects(bucket, prefix);

  const objects = useMemo(
    () => data?.pages.flatMap((p) => p.Contents ?? []) ?? [],
    [data],
  );
  const folders = useMemo(
    () => data?.pages.flatMap((p) => p.CommonPrefixes ?? []) ?? [],
    [data],
  );

  function setPrefix(next: string) {
    setSelected(new Set());
    if (next) {
      setParams({ prefix: next });
    } else {
      setParams({});
    }
  }

  const allKeys = objects.map((o) => o.Key!).filter(Boolean);
  const allSelected = allKeys.length > 0 && allKeys.every((k) => selected.has(k));

  async function downloadKey(key: string) {
    try {
      const url = await presignGet(bucket, key, 300);
      window.open(url, '_blank');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Presign failed');
    }
  }

  async function bulkDelete() {
    const keys = [...selected];
    if (keys.length === 0) return;
    if (!confirm(`Delete ${keys.length} object(s)?`)) return;
    try {
      const r = await deleteObjects.mutateAsync(keys);
      const errCount = r.Errors?.length ?? 0;
      const okCount = r.Deleted?.length ?? 0;
      if (errCount > 0) {
        toast.warning(
          `Deleted ${okCount}, ${errCount} failed (${r.Errors?.[0]?.Message ?? ''})`,
        );
      } else {
        toast.success(`Deleted ${okCount}`);
      }
      setSelected(new Set());
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Delete failed');
    }
  }

  const crumbs = prefix.split('/').filter(Boolean);

  return (
    <div className="space-y-4">
      <Uploader bucket={bucket} prefix={prefix} />

      <div className="flex items-center justify-between gap-2 flex-wrap">
        <nav className="text-sm text-zinc-500 flex items-center gap-1 flex-wrap">
          <button
            className="hover:underline"
            onClick={() => setPrefix('')}
          >
            (root)
          </button>
          {crumbs.map((c, i) => {
            const next = crumbs.slice(0, i + 1).join('/') + '/';
            return (
              <span key={i} className="flex items-center gap-1">
                <ChevronRight className="size-3" />
                <button
                  className="hover:underline"
                  onClick={() => setPrefix(next)}
                >
                  {c}
                </button>
              </span>
            );
          })}
        </nav>
        <div className="flex gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={selected.size === 0 || deleteObjects.isPending}
            onClick={bulkDelete}
          >
            <Trash2 className="size-4" /> Delete ({selected.size})
          </Button>
        </div>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead className="w-8">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={(e) => {
                  if (e.target.checked) {
                    setSelected(new Set(allKeys));
                  } else {
                    setSelected(new Set());
                  }
                }}
              />
            </TableHead>
            <TableHead>Key</TableHead>
            <TableHead>Size</TableHead>
            <TableHead>Modified</TableHead>
            <TableHead>ETag</TableHead>
            <TableHead className="w-32" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-sm py-8 text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {folders.map((f) => (
            <TableRow key={f.Prefix}>
              <TableCell />
              <TableCell colSpan={4}>
                <button
                  className="inline-flex items-center gap-2 font-medium hover:underline"
                  onClick={() => setPrefix(f.Prefix!)}
                >
                  <Folder className="size-4 text-zinc-400" />
                  {f.Prefix?.slice(prefix.length)}
                </button>
              </TableCell>
              <TableCell />
            </TableRow>
          ))}
          {objects.map((o) => {
            const shortKey = o.Key!.slice(prefix.length);
            return (
              <TableRow key={o.Key}>
                <TableCell>
                  <input
                    type="checkbox"
                    checked={selected.has(o.Key!)}
                    onChange={(e) => {
                      const next = new Set(selected);
                      if (e.target.checked) next.add(o.Key!);
                      else next.delete(o.Key!);
                      setSelected(next);
                    }}
                  />
                </TableCell>
                <TableCell>
                  <Link
                    to={`/s3/b/${encodeURIComponent(bucket)}/object?key=${encodeURIComponent(o.Key!)}`}
                    className="font-medium hover:underline"
                  >
                    {shortKey}
                  </Link>
                </TableCell>
                <TableCell className="text-zinc-500">
                  {formatBytes(o.Size)}
                </TableCell>
                <TableCell className="text-zinc-500">
                  {formatDate(o.LastModified)}
                </TableCell>
                <TableCell className="font-mono text-xs text-zinc-500">
                  {trimEtag(o.ETag).slice(0, 8)}…
                </TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Download"
                    onClick={() => downloadKey(o.Key!)}
                  >
                    <Download className="size-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    title="Presign URL"
                    onClick={() => setPresignFor(o.Key!)}
                  >
                    <LinkIcon className="size-4" />
                  </Button>
                </TableCell>
              </TableRow>
            );
          })}
          {!isLoading && folders.length === 0 && objects.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-sm py-8 text-zinc-500">
                No objects under this prefix.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>

      {hasNextPage && (
        <div className="flex justify-center">
          <Button
            variant="outline"
            onClick={() => fetchNextPage()}
            disabled={isFetchingNextPage}
          >
            {isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      )}

      <PresignDialog
        bucket={bucket}
        initialKey={presignFor ?? ''}
        open={presignFor !== null}
        onOpenChange={(o) => !o && setPresignFor(null)}
      />
    </div>
  );
}

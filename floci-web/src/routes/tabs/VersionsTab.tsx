import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { BucketVersioningStatus } from '@aws-sdk/client-s3';
import { Switch } from '@/components/ui/switch';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useDeleteVersion,
  useObjectVersions,
  useSetVersioning,
  useVersioning,
} from '@/hooks/s3/useVersions';
import { formatBytes, formatDate } from '@/lib/format';

export default function VersionsTab() {
  const { bucket = '' } = useParams();
  const { data: status } = useVersioning(bucket);
  const setVersioning = useSetVersioning(bucket);
  const { data: versions } = useObjectVersions(bucket, '');
  const deleteVersion = useDeleteVersion(bucket, '');

  const enabled = status === 'Enabled';

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 rounded-md border border-zinc-200 bg-white p-4">
        <div className="flex-1">
          <p className="font-medium">Bucket versioning</p>
          <p className="text-sm text-zinc-500">
            Current status: <code>{status ?? 'Disabled'}</code>
          </p>
        </div>
        <Switch
          checked={enabled}
          onCheckedChange={async (v) => {
            try {
              await setVersioning.mutateAsync(
                v
                  ? BucketVersioningStatus.Enabled
                  : BucketVersioningStatus.Suspended,
              );
              toast.success(v ? 'Versioning enabled' : 'Versioning suspended');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        />
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Key</TableHead>
            <TableHead>Version ID</TableHead>
            <TableHead>Latest</TableHead>
            <TableHead>Size</TableHead>
            <TableHead>Modified</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {(versions?.Versions ?? []).map((v) => (
            <TableRow key={`${v.Key}@${v.VersionId}`}>
              <TableCell className="font-medium">{v.Key}</TableCell>
              <TableCell className="font-mono text-xs">
                {v.VersionId}
              </TableCell>
              <TableCell>{v.IsLatest ? 'yes' : ''}</TableCell>
              <TableCell className="text-zinc-500">
                {formatBytes(v.Size)}
              </TableCell>
              <TableCell className="text-zinc-500">
                {formatDate(v.LastModified)}
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={async () => {
                    if (!confirm(`Delete version ${v.VersionId} of ${v.Key}?`))
                      return;
                    try {
                      await deleteVersion.mutateAsync({
                        key: v.Key!,
                        versionId: v.VersionId!,
                      });
                      toast.success('Version deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  Delete
                </Button>
              </TableCell>
            </TableRow>
          ))}
          {(versions?.DeleteMarkers ?? []).map((m) => (
            <TableRow key={`marker-${m.Key}@${m.VersionId}`}>
              <TableCell className="font-medium">{m.Key}</TableCell>
              <TableCell className="font-mono text-xs">
                {m.VersionId}
              </TableCell>
              <TableCell className="text-zinc-500">
                delete marker {m.IsLatest ? '(latest)' : ''}
              </TableCell>
              <TableCell />
              <TableCell className="text-zinc-500">
                {formatDate(m.LastModified)}
              </TableCell>
              <TableCell />
            </TableRow>
          ))}
          {!versions?.Versions?.length && !versions?.DeleteMarkers?.length && (
            <TableRow>
              <TableCell colSpan={6} className="text-center text-sm py-8 text-zinc-500">
                No versions to show.
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
}

import { useParams } from 'react-router-dom';
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
import {
  useAbortMultipart,
  useMultipartUploads,
} from '@/hooks/s3/useMultipart';
import { formatDate } from '@/lib/format';

export default function MultipartTab() {
  const { bucket = '' } = useParams();
  const { data: uploads = [], isLoading } = useMultipartUploads(bucket);
  const abort = useAbortMultipart(bucket);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Multipart uploads created via{' '}
        <code>CreateMultipartUpload</code> that haven't been completed or
        aborted. Abandoned uploads can occupy storage indefinitely until
        cleaned up.
      </p>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Key</TableHead>
            <TableHead>Upload ID</TableHead>
            <TableHead>Initiated</TableHead>
            <TableHead className="w-32" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-zinc-500 text-sm">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && uploads.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-zinc-500 text-sm">
                No in-progress multipart uploads.
              </TableCell>
            </TableRow>
          )}
          {uploads.map((u) => (
            <TableRow key={`${u.Key}@${u.UploadId}`}>
              <TableCell className="font-medium">{u.Key}</TableCell>
              <TableCell className="font-mono text-xs">{u.UploadId}</TableCell>
              <TableCell className="text-zinc-500">
                {formatDate(u.Initiated)}
              </TableCell>
              <TableCell>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={async () => {
                    if (!confirm(`Abort multipart upload for ${u.Key}?`))
                      return;
                    try {
                      await abort.mutateAsync({
                        key: u.Key!,
                        uploadId: u.UploadId!,
                      });
                      toast.success('Aborted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  Abort
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

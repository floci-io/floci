import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import {
  useBuckets,
  useCreateBucket,
  useDeleteBucket,
} from '@/hooks/s3/useBuckets';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { formatDate } from '@/lib/format';

export default function BucketsPage() {
  const { data: buckets = [], isLoading, error } = useBuckets();
  const createBucket = useCreateBucket();
  const deleteBucket = useDeleteBucket();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [lock, setLock] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Buckets</h1>
          <p className="text-sm text-zinc-500">
            {buckets.length} bucket{buckets.length === 1 ? '' : 's'}
          </p>
        </div>
        <Dialog open={open} onOpenChange={setOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="size-4" /> New bucket
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Create bucket</DialogTitle>
              <DialogDescription>
                Bucket names are global within Floci.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-3">
              <div>
                <Label>Name</Label>
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="my-bucket"
                />
              </div>
              <div className="flex items-center justify-between">
                <div>
                  <Label>Enable Object Lock</Label>
                  <p className="text-xs text-zinc-500">
                    Must be set at creation. Cannot be changed later.
                  </p>
                </div>
                <Switch checked={lock} onCheckedChange={setLock} />
              </div>
            </div>
            <DialogFooter>
              <Button variant="outline" onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button
                disabled={!name || createBucket.isPending}
                onClick={async () => {
                  try {
                    await createBucket.mutateAsync({
                      bucket: name,
                      objectLockEnabled: lock,
                    });
                    toast.success(`Bucket "${name}" created`);
                    setOpen(false);
                    setName('');
                    setLock(false);
                  } catch (e) {
                    const err = e as { message?: string };
                    toast.error(err.message ?? 'Create failed');
                  }
                }}
              >
                Create
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>

      {isLoading && <p className="text-sm text-zinc-500">Loading…</p>}
      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {(error as Error).message}
        </div>
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Created</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {buckets.length === 0 && !isLoading && (
            <TableRow>
              <TableCell
                colSpan={3}
                className="text-center text-sm text-zinc-500 py-8"
              >
                No buckets yet.
              </TableCell>
            </TableRow>
          )}
          {buckets.map((b) => (
            <TableRow key={b.Name}>
              <TableCell>
                <Link
                  to={`/s3/b/${encodeURIComponent(b.Name!)}`}
                  className="font-medium text-zinc-900 hover:underline"
                >
                  {b.Name}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-500">
                {formatDate(b.CreationDate)}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete bucket "${b.Name}"?`)) return;
                    try {
                      await deleteBucket.mutateAsync(b.Name!);
                      toast.success('Deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Delete failed');
                    }
                  }}
                >
                  <Trash2 className="size-4" />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

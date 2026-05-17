import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, RotateCw, Trash2 } from 'lucide-react';
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
  useDBInstances,
  useDeleteDBInstance,
  useRebootDBInstance,
} from '@/hooks/rds/useInstances';
import { CreateInstanceDialog } from './CreateInstanceDialog';
import { StatusPill } from './StatusPill';

export default function InstancesPage() {
  const { data: instances = [], isLoading, error } = useDBInstances();
  const del = useDeleteDBInstance();
  const reboot = useRebootDBInstance();
  const [open, setOpen] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {instances.length} instance{instances.length === 1 ? '' : 's'}
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New instance
        </Button>
      </div>

      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {(error as Error).message}
        </div>
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Identifier</TableHead>
            <TableHead>Engine</TableHead>
            <TableHead>Class</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Endpoint</TableHead>
            <TableHead className="w-32" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell
                colSpan={6}
                className="text-center text-sm text-zinc-500 py-8"
              >
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && instances.length === 0 && (
            <TableRow>
              <TableCell
                colSpan={6}
                className="text-center text-sm text-zinc-500 py-8"
              >
                No instances yet.
              </TableCell>
            </TableRow>
          )}
          {instances.map((i) => (
            <TableRow key={i.DBInstanceIdentifier}>
              <TableCell>
                <Link
                  to={`/rds/instances/${encodeURIComponent(i.DBInstanceIdentifier!)}`}
                  className="font-medium hover:underline"
                >
                  {i.DBInstanceIdentifier}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">{i.Engine}</TableCell>
              <TableCell className="text-zinc-600">
                {i.DBInstanceClass}
              </TableCell>
              <TableCell>
                <StatusPill status={i.DBInstanceStatus} />
              </TableCell>
              <TableCell className="font-mono text-xs text-zinc-600">
                {i.Endpoint?.Address
                  ? `${i.Endpoint.Address}:${i.Endpoint.Port}`
                  : '—'}
              </TableCell>
              <TableCell className="text-right whitespace-nowrap">
                <Button
                  variant="ghost"
                  size="icon"
                  title="Reboot"
                  onClick={async () => {
                    try {
                      await reboot.mutateAsync(i.DBInstanceIdentifier!);
                      toast.success(`Rebooting ${i.DBInstanceIdentifier}`);
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Reboot failed');
                    }
                  }}
                >
                  <RotateCw className="size-4" />
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  title="Delete"
                  onClick={async () => {
                    if (!confirm(`Delete ${i.DBInstanceIdentifier}?`)) return;
                    try {
                      await del.mutateAsync({
                        identifier: i.DBInstanceIdentifier!,
                      });
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

      <CreateInstanceDialog open={open} onOpenChange={setOpen} />
    </div>
  );
}

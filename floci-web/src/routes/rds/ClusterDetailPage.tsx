import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ChevronLeft, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useDBCluster,
  useDeleteDBCluster,
} from '@/hooks/rds/useClusters';
import { useDBInstances } from '@/hooks/rds/useInstances';
import { StatusPill } from './StatusPill';
import { CreateInstanceDialog } from './CreateInstanceDialog';

export default function ClusterDetailPage() {
  const { identifier = '' } = useParams();
  const nav = useNavigate();
  const { data: cluster, isLoading } = useDBCluster(identifier);
  const del = useDeleteDBCluster();
  const { data: instances = [] } = useDBInstances();
  const [open, setOpen] = useState(false);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!cluster) {
    return (
      <div className="space-y-4">
        <Link
          to="/rds/clusters"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Clusters
        </Link>
        <p className="text-sm text-zinc-500">Cluster not found.</p>
      </div>
    );
  }

  const members = instances.filter(
    (i) => i.DBClusterIdentifier === identifier,
  );

  return (
    <div className="space-y-4">
      <Link
        to="/rds/clusters"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Clusters
      </Link>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{cluster.DBClusterIdentifier}</h1>
          <div className="flex items-center gap-2 mt-1 text-sm text-zinc-500">
            <span>{cluster.Engine}</span>
            <StatusPill status={cluster.Status} />
          </div>
        </div>
        <div className="flex gap-2">
          <Button onClick={() => setOpen(true)}>
            <Plus className="size-4" /> Add instance
          </Button>
          <Button
            variant="destructive"
            onClick={async () => {
              if (!confirm(`Delete cluster ${identifier}?`)) return;
              try {
                await del.mutateAsync(identifier);
                toast.success('Deletion started');
                nav('/rds/clusters');
              } catch (e) {
                const err = e as { message?: string };
                toast.error(err.message ?? 'Delete failed');
              }
            }}
          >
            <Trash2 className="size-4" /> Delete
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Connection</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-zinc-500">Writer endpoint</p>
            <p className="font-mono text-xs">
              {cluster.Endpoint ?? '—'}
              {cluster.Port ? `:${cluster.Port}` : ''}
            </p>
          </div>
          <div>
            <p className="text-zinc-500">Reader endpoint</p>
            <p className="font-mono text-xs">
              {cluster.ReaderEndpoint ?? '—'}
            </p>
          </div>
          <div>
            <p className="text-zinc-500">Master username</p>
            <p className="font-mono text-xs">{cluster.MasterUsername ?? '—'}</p>
          </div>
          <div>
            <p className="text-zinc-500">Engine version</p>
            <p className="font-mono text-xs">{cluster.EngineVersion ?? '—'}</p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Member instances ({members.length})</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Identifier</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Endpoint</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {members.length === 0 && (
                <TableRow>
                  <TableCell
                    colSpan={3}
                    className="text-center py-6 text-sm text-zinc-500"
                  >
                    No members yet. Add an instance to this cluster.
                  </TableCell>
                </TableRow>
              )}
              {members.map((m) => (
                <TableRow key={m.DBInstanceIdentifier}>
                  <TableCell>
                    <Link
                      to={`/rds/instances/${encodeURIComponent(m.DBInstanceIdentifier!)}`}
                      className="font-medium hover:underline"
                    >
                      {m.DBInstanceIdentifier}
                    </Link>
                  </TableCell>
                  <TableCell>
                    <StatusPill status={m.DBInstanceStatus} />
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    {m.Endpoint?.Address
                      ? `${m.Endpoint.Address}:${m.Endpoint.Port}`
                      : '—'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <CreateInstanceDialog
        open={open}
        onOpenChange={setOpen}
        defaultCluster={identifier}
      />
    </div>
  );
}

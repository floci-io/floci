import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateDBCluster,
  useDBClusters,
  useDeleteDBCluster,
} from '@/hooks/rds/useClusters';
import { StatusPill } from './StatusPill';

export default function ClustersPage() {
  const { data: clusters = [], isLoading } = useDBClusters();
  const create = useCreateDBCluster();
  const del = useDeleteDBCluster();
  const [open, setOpen] = useState(false);
  const [identifier, setIdentifier] = useState('');
  const [engine, setEngine] = useState('aurora-postgresql');
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {clusters.length} cluster{clusters.length === 1 ? '' : 's'}
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New cluster
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Identifier</TableHead>
            <TableHead>Engine</TableHead>
            <TableHead>Status</TableHead>
            <TableHead>Endpoint</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={5} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && clusters.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} className="text-center py-8 text-sm text-zinc-500">
                No clusters yet.
              </TableCell>
            </TableRow>
          )}
          {clusters.map((c) => (
            <TableRow key={c.DBClusterIdentifier}>
              <TableCell>
                <Link
                  to={`/rds/clusters/${encodeURIComponent(c.DBClusterIdentifier!)}`}
                  className="font-medium hover:underline"
                >
                  {c.DBClusterIdentifier}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">{c.Engine}</TableCell>
              <TableCell>
                <StatusPill status={c.Status} />
              </TableCell>
              <TableCell className="font-mono text-xs text-zinc-600">
                {c.Endpoint ?? '—'}
                {c.Port ? `:${c.Port}` : ''}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete cluster ${c.DBClusterIdentifier}?`))
                      return;
                    try {
                      await del.mutateAsync(c.DBClusterIdentifier!);
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

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create DB cluster</DialogTitle>
            <DialogDescription>
              Aurora-compatible cluster. Add instances afterwards to attach
              them.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Identifier</Label>
              <Input
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder="my-cluster"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Engine</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={engine}
                  onChange={(e) => setEngine(e.target.value)}
                >
                  <option value="aurora-postgresql">aurora-postgresql</option>
                  <option value="aurora-mysql">aurora-mysql</option>
                  <option value="postgres">postgres</option>
                  <option value="mysql">mysql</option>
                </select>
              </div>
              <div>
                <Label>Master username</Label>
                <Input
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                />
              </div>
            </div>
            <div>
              <Label>Master password</Label>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!identifier || !password || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    DBClusterIdentifier: identifier,
                    Engine: engine,
                    MasterUsername: username,
                    MasterUserPassword: password,
                  });
                  toast.success('Cluster created');
                  setOpen(false);
                  setIdentifier('');
                  setPassword('');
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
  );
}

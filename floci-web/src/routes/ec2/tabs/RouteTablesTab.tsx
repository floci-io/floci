import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/input';
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateRouteTable,
  useDeleteRouteTable,
  useRouteTables,
} from '@/hooks/ec2/useRouteTables';
import { useVpcs } from '@/hooks/ec2/useVpcs';

export default function RouteTablesTab() {
  const { data: tables = [], isLoading } = useRouteTables();
  const { data: vpcs = [] } = useVpcs();
  const create = useCreateRouteTable();
  const del = useDeleteRouteTable();
  const [open, setOpen] = useState(false);
  const [vpcId, setVpcId] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {tables.length} route table{tables.length === 1 ? '' : 's'}.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Create route table
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Route table ID</TableHead>
            <TableHead>VPC</TableHead>
            <TableHead>Routes</TableHead>
            <TableHead>Associations</TableHead>
            <TableHead className="w-12" />
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
          {!isLoading && tables.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} className="text-center py-8 text-sm text-zinc-500">
                No route tables.
              </TableCell>
            </TableRow>
          )}
          {tables.map((t) => (
            <TableRow key={t.RouteTableId}>
              <TableCell>
                <Link
                  to={`/ec2/route-tables/${encodeURIComponent(t.RouteTableId!)}`}
                  className="font-mono text-xs hover:underline"
                >
                  {t.RouteTableId}
                </Link>
              </TableCell>
              <TableCell className="font-mono text-xs">{t.VpcId}</TableCell>
              <TableCell>{(t.Routes ?? []).length}</TableCell>
              <TableCell>{(t.Associations ?? []).length}</TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${t.RouteTableId}?`)) return;
                    try {
                      await del.mutateAsync(t.RouteTableId!);
                      toast.success('Deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
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
            <DialogTitle>Create route table</DialogTitle>
          </DialogHeader>
          <div>
            <Label>VPC</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={vpcId}
              onChange={(e) => setVpcId(e.target.value)}
            >
              <option value="">(select)</option>
              {vpcs.map((v) => (
                <option key={v.VpcId} value={v.VpcId}>
                  {v.VpcId} · {v.CidrBlock}
                </option>
              ))}
            </select>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!vpcId || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync(vpcId);
                  toast.success('Created');
                  setOpen(false);
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
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

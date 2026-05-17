import { useState } from 'react';
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { useCreateVpc, useDeleteVpc, useVpcs } from '@/hooks/ec2/useVpcs';

export default function VpcsTab() {
  const { data: vpcs = [], isLoading } = useVpcs();
  const create = useCreateVpc();
  const del = useDeleteVpc();
  const [open, setOpen] = useState(false);
  const [cidr, setCidr] = useState('10.0.0.0/16');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {vpcs.length} VPC{vpcs.length === 1 ? '' : 's'}. Floci pre-seeds{' '}
          <code>vpc-default</code> at <code>172.31.0.0/16</code>.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Create VPC
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>VPC ID</TableHead>
            <TableHead>CIDR</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Default</TableHead>
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
          {!isLoading && vpcs.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} className="text-center py-8 text-sm text-zinc-500">
                No VPCs.
              </TableCell>
            </TableRow>
          )}
          {vpcs.map((v) => (
            <TableRow key={v.VpcId}>
              <TableCell className="font-mono text-xs">{v.VpcId}</TableCell>
              <TableCell className="font-mono text-xs">{v.CidrBlock}</TableCell>
              <TableCell>{v.State ?? '—'}</TableCell>
              <TableCell>{v.IsDefault ? 'yes' : ''}</TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={v.IsDefault}
                  onClick={async () => {
                    if (!confirm(`Delete ${v.VpcId}?`)) return;
                    try {
                      await del.mutateAsync(v.VpcId!);
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
            <DialogTitle>Create VPC</DialogTitle>
          </DialogHeader>
          <div>
            <Label>CIDR block</Label>
            <Input value={cidr} onChange={(e) => setCidr(e.target.value)} />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!cidr || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync(cidr);
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

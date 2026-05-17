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
import {
  useCreateSubnet,
  useDeleteSubnet,
  useSubnets,
} from '@/hooks/ec2/useSubnets';
import { useVpcs } from '@/hooks/ec2/useVpcs';
import { useAvailabilityZones } from '@/hooks/ec2/useAvailabilityZones';

export default function SubnetsTab() {
  const { data: subnets = [], isLoading } = useSubnets();
  const { data: vpcs = [] } = useVpcs();
  const { data: zones = [] } = useAvailabilityZones();
  const create = useCreateSubnet();
  const del = useDeleteSubnet();

  const [open, setOpen] = useState(false);
  const [vpcId, setVpcId] = useState('');
  const [cidr, setCidr] = useState('');
  const [az, setAz] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {subnets.length} subnet{subnets.length === 1 ? '' : 's'}.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Create subnet
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Subnet ID</TableHead>
            <TableHead>VPC</TableHead>
            <TableHead>CIDR</TableHead>
            <TableHead>AZ</TableHead>
            <TableHead>State</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && subnets.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                No subnets.
              </TableCell>
            </TableRow>
          )}
          {subnets.map((s) => (
            <TableRow key={s.SubnetId}>
              <TableCell className="font-mono text-xs">{s.SubnetId}</TableCell>
              <TableCell className="font-mono text-xs">{s.VpcId}</TableCell>
              <TableCell className="font-mono text-xs">{s.CidrBlock}</TableCell>
              <TableCell>{s.AvailabilityZone}</TableCell>
              <TableCell>{s.State ?? '—'}</TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${s.SubnetId}?`)) return;
                    try {
                      await del.mutateAsync(s.SubnetId!);
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
            <DialogTitle>Create subnet</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
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
            <div>
              <Label>CIDR block</Label>
              <Input
                value={cidr}
                onChange={(e) => setCidr(e.target.value)}
                placeholder="10.0.1.0/24"
              />
            </div>
            <div>
              <Label>Availability zone</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={az}
                onChange={(e) => setAz(e.target.value)}
              >
                <option value="">(auto)</option>
                {zones.map((z) => (
                  <option key={z.ZoneName} value={z.ZoneName}>
                    {z.ZoneName}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!vpcId || !cidr || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    vpcId,
                    cidr,
                    availabilityZone: az || undefined,
                  });
                  toast.success('Created');
                  setOpen(false);
                  setCidr('');
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

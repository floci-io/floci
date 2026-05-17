import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { VolumeType } from '@aws-sdk/client-ec2';
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
  useCreateVolume,
  useDeleteVolume,
  useVolumes,
} from '@/hooks/ec2/useVolumes';
import { useAvailabilityZones } from '@/hooks/ec2/useAvailabilityZones';
import { StatePill } from '../StatePill';

export default function VolumesTab() {
  const { data: volumes = [], isLoading } = useVolumes();
  const { data: zones = [] } = useAvailabilityZones();
  const create = useCreateVolume();
  const del = useDeleteVolume();

  const [open, setOpen] = useState(false);
  const [size, setSize] = useState(8);
  const [az, setAz] = useState('');
  const [type, setType] = useState<VolumeType>(VolumeType.gp3);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {volumes.length} volume{volumes.length === 1 ? '' : 's'}.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Create volume
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Volume ID</TableHead>
            <TableHead>Size</TableHead>
            <TableHead>Type</TableHead>
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
          {!isLoading && volumes.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                No volumes.
              </TableCell>
            </TableRow>
          )}
          {volumes.map((v) => (
            <TableRow key={v.VolumeId}>
              <TableCell className="font-mono text-xs">{v.VolumeId}</TableCell>
              <TableCell>{v.Size} GiB</TableCell>
              <TableCell>{v.VolumeType}</TableCell>
              <TableCell>{v.AvailabilityZone}</TableCell>
              <TableCell>
                <StatePill state={v.State} />
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${v.VolumeId}?`)) return;
                    try {
                      await del.mutateAsync(v.VolumeId!);
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
            <DialogTitle>Create volume</DialogTitle>
          </DialogHeader>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Size (GiB)</Label>
              <Input
                type="number"
                value={size}
                onChange={(e) => setSize(Number(e.target.value) || 8)}
              />
            </div>
            <div>
              <Label>Type</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={type}
                onChange={(e) => setType(e.target.value as VolumeType)}
              >
                <option value={VolumeType.gp3}>gp3</option>
                <option value={VolumeType.gp2}>gp2</option>
                <option value={VolumeType.io2}>io2</option>
                <option value={VolumeType.io1}>io1</option>
                <option value={VolumeType.st1}>st1</option>
                <option value={VolumeType.sc1}>sc1</option>
                <option value={VolumeType.standard}>standard</option>
              </select>
            </div>
            <div className="col-span-2">
              <Label>Availability zone</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={az}
                onChange={(e) => setAz(e.target.value)}
              >
                <option value="">(select)</option>
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
              disabled={!az || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    sizeGb: size,
                    availabilityZone: az,
                    type,
                  });
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

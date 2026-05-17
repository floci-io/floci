import { useState } from 'react';
import { Link as LinkIcon, Plus, Trash2, Unlink } from 'lucide-react';
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
  useAllocateAddress,
  useAssociateAddress,
  useDisassociateAddress,
  useElasticIps,
  useReleaseAddress,
} from '@/hooks/ec2/useElasticIps';
import { useInstances } from '@/hooks/ec2/useInstances';

export default function ElasticIpsTab() {
  const { data: addresses = [], isLoading } = useElasticIps();
  const { data: instances = [] } = useInstances();
  const allocate = useAllocateAddress();
  const associate = useAssociateAddress();
  const disassociate = useDisassociateAddress();
  const release = useReleaseAddress();

  const [associateFor, setAssociateFor] = useState<string | null>(null);
  const [instanceId, setInstanceId] = useState('');

  const runningInstances = instances.filter(
    (i) => i.State?.Name === 'running',
  );

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {addresses.length} elastic IP{addresses.length === 1 ? '' : 's'}.
        </p>
        <Button
          onClick={async () => {
            try {
              await allocate.mutateAsync();
              toast.success('Allocated');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          <Plus className="size-4" /> Allocate
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Public IP</TableHead>
            <TableHead>Allocation ID</TableHead>
            <TableHead>Associated instance</TableHead>
            <TableHead className="w-56 text-right" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && addresses.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                No elastic IPs.
              </TableCell>
            </TableRow>
          )}
          {addresses.map((a) => (
            <TableRow key={a.AllocationId}>
              <TableCell className="font-mono text-xs">{a.PublicIp}</TableCell>
              <TableCell className="font-mono text-xs">
                {a.AllocationId}
              </TableCell>
              <TableCell className="font-mono text-xs">
                {a.InstanceId ?? '—'}
              </TableCell>
              <TableCell className="text-right whitespace-nowrap">
                {a.AssociationId ? (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={async () => {
                      try {
                        await disassociate.mutateAsync(a.AssociationId!);
                        toast.success('Disassociated');
                      } catch (e) {
                        const err = e as { message?: string };
                        toast.error(err.message ?? 'Failed');
                      }
                    }}
                  >
                    <Unlink className="size-4" /> Disassociate
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => setAssociateFor(a.AllocationId!)}
                  >
                    <LinkIcon className="size-4" /> Associate
                  </Button>
                )}
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={!!a.AssociationId}
                  onClick={async () => {
                    if (!confirm(`Release ${a.PublicIp}?`)) return;
                    try {
                      await release.mutateAsync(a.AllocationId!);
                      toast.success('Released');
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

      <Dialog
        open={associateFor !== null}
        onOpenChange={(o) => {
          if (!o) {
            setAssociateFor(null);
            setInstanceId('');
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Associate elastic IP</DialogTitle>
          </DialogHeader>
          <div>
            <Label>Instance</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={instanceId}
              onChange={(e) => setInstanceId(e.target.value)}
            >
              <option value="">(select)</option>
              {runningInstances.map((i) => (
                <option key={i.InstanceId} value={i.InstanceId}>
                  {i.InstanceId} · {i.InstanceType}
                </option>
              ))}
            </select>
          </div>
          <DialogFooter>
            <Button
              variant="outline"
              onClick={() => setAssociateFor(null)}
            >
              Cancel
            </Button>
            <Button
              disabled={!instanceId || associate.isPending}
              onClick={async () => {
                try {
                  await associate.mutateAsync({
                    allocationId: associateFor!,
                    instanceId,
                  });
                  toast.success('Associated');
                  setAssociateFor(null);
                  setInstanceId('');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Associate
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

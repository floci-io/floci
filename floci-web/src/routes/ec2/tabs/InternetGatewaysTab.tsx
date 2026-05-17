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
  useAttachInternetGateway,
  useCreateInternetGateway,
  useDeleteInternetGateway,
  useDetachInternetGateway,
  useInternetGateways,
} from '@/hooks/ec2/useInternetGateways';
import { useVpcs } from '@/hooks/ec2/useVpcs';

export default function InternetGatewaysTab() {
  const { data: gws = [], isLoading } = useInternetGateways();
  const { data: vpcs = [] } = useVpcs();
  const create = useCreateInternetGateway();
  const attach = useAttachInternetGateway();
  const detach = useDetachInternetGateway();
  const del = useDeleteInternetGateway();

  const [attachOpen, setAttachOpen] = useState<string | null>(null);
  const [vpcId, setVpcId] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {gws.length} internet gateway{gws.length === 1 ? '' : 's'}.
        </p>
        <Button
          onClick={async () => {
            try {
              await create.mutateAsync();
              toast.success('Created');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          <Plus className="size-4" /> Create gateway
        </Button>
      </div>
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Gateway ID</TableHead>
            <TableHead>Attached VPC(s)</TableHead>
            <TableHead className="w-44" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={3} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && gws.length === 0 && (
            <TableRow>
              <TableCell colSpan={3} className="text-center py-8 text-sm text-zinc-500">
                No internet gateways.
              </TableCell>
            </TableRow>
          )}
          {gws.map((g) => {
            const attached = (g.Attachments ?? []).filter(
              (a) => a.State === 'attached' || a.State === 'attaching',
            );
            return (
              <TableRow key={g.InternetGatewayId}>
                <TableCell className="font-mono text-xs">
                  {g.InternetGatewayId}
                </TableCell>
                <TableCell className="font-mono text-xs">
                  {attached.map((a) => a.VpcId).join(', ') || '—'}
                </TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  {attached.length === 0 ? (
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={() => setAttachOpen(g.InternetGatewayId!)}
                    >
                      Attach
                    </Button>
                  ) : (
                    attached.map((a) => (
                      <Button
                        key={a.VpcId}
                        variant="outline"
                        size="sm"
                        onClick={async () => {
                          try {
                            await detach.mutateAsync({
                              gatewayId: g.InternetGatewayId!,
                              vpcId: a.VpcId!,
                            });
                            toast.success('Detached');
                          } catch (e) {
                            const err = e as { message?: string };
                            toast.error(err.message ?? 'Failed');
                          }
                        }}
                      >
                        Detach {a.VpcId}
                      </Button>
                    ))
                  )}
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={attached.length > 0}
                    onClick={async () => {
                      if (!confirm(`Delete ${g.InternetGatewayId}?`)) return;
                      try {
                        await del.mutateAsync(g.InternetGatewayId!);
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
            );
          })}
        </TableBody>
      </Table>

      <Dialog
        open={attachOpen !== null}
        onOpenChange={(o) => {
          if (!o) setAttachOpen(null);
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Attach to VPC</DialogTitle>
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
            <Button variant="outline" onClick={() => setAttachOpen(null)}>
              Cancel
            </Button>
            <Button
              disabled={!vpcId || attach.isPending}
              onClick={async () => {
                try {
                  await attach.mutateAsync({
                    gatewayId: attachOpen!,
                    vpcId,
                  });
                  toast.success('Attached');
                  setAttachOpen(null);
                  setVpcId('');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Attach
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

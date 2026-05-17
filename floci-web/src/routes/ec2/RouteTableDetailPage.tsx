import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
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
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateRoute,
  useDeleteRoute,
  useRouteTable,
} from '@/hooks/ec2/useRouteTables';
import { useInternetGateways } from '@/hooks/ec2/useInternetGateways';

type TargetType = 'gateway' | 'instance' | 'natgateway';

export default function RouteTableDetailPage() {
  const { id = '' } = useParams();
  const { data: rt, isLoading } = useRouteTable(id);
  const { data: gateways = [] } = useInternetGateways();
  const create = useCreateRoute(id);
  const del = useDeleteRoute(id);

  const [open, setOpen] = useState(false);
  const [cidr, setCidr] = useState('0.0.0.0/0');
  const [targetType, setTargetType] = useState<TargetType>('gateway');
  const [targetId, setTargetId] = useState('');

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!rt) {
    return (
      <div className="space-y-4">
        <Link
          to="/ec2/route-tables"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Route tables
        </Link>
        <p className="text-sm text-zinc-500">Route table not found.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <Link
        to="/ec2/route-tables"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Route tables
      </Link>

      <div>
        <h1 className="text-2xl font-semibold">{rt.RouteTableId}</h1>
        <p className="text-xs text-zinc-500 mt-1">
          VPC <span className="font-mono">{rt.VpcId}</span>
        </p>
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Routes</CardTitle>
            <Button size="sm" onClick={() => setOpen(true)}>
              <Plus className="size-4" /> Add route
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Destination</TableHead>
                <TableHead>Target</TableHead>
                <TableHead>State</TableHead>
                <TableHead>Origin</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {(rt.Routes ?? []).map((r, i) => {
                const dest =
                  r.DestinationCidrBlock ??
                  r.DestinationIpv6CidrBlock ??
                  r.DestinationPrefixListId ??
                  '—';
                const target =
                  r.GatewayId ??
                  r.InstanceId ??
                  r.NatGatewayId ??
                  r.NetworkInterfaceId ??
                  r.TransitGatewayId ??
                  r.VpcPeeringConnectionId ??
                  '—';
                const isLocal = target === 'local' || r.GatewayId === 'local';
                return (
                  <TableRow key={i}>
                    <TableCell className="font-mono text-xs">{dest}</TableCell>
                    <TableCell className="font-mono text-xs">{target}</TableCell>
                    <TableCell>{r.State ?? '—'}</TableCell>
                    <TableCell>{r.Origin ?? '—'}</TableCell>
                    <TableCell>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled={isLocal}
                        onClick={async () => {
                          if (!r.DestinationCidrBlock) return;
                          if (!confirm(`Delete route to ${dest}?`)) return;
                          try {
                            await del.mutateAsync(r.DestinationCidrBlock);
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
        </CardContent>
      </Card>

      {rt.Associations && rt.Associations.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Associations</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Association ID</TableHead>
                  <TableHead>Subnet</TableHead>
                  <TableHead>Main</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rt.Associations.map((a) => (
                  <TableRow key={a.RouteTableAssociationId}>
                    <TableCell className="font-mono text-xs">
                      {a.RouteTableAssociationId}
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {a.SubnetId ?? '—'}
                    </TableCell>
                    <TableCell>{a.Main ? 'yes' : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Add route</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Destination CIDR</Label>
              <Input value={cidr} onChange={(e) => setCidr(e.target.value)} />
            </div>
            <div>
              <Label>Target type</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={targetType}
                onChange={(e) => setTargetType(e.target.value as TargetType)}
              >
                <option value="gateway">Internet gateway</option>
                <option value="natgateway">NAT gateway</option>
                <option value="instance">EC2 instance</option>
              </select>
            </div>
            <div>
              <Label>Target ID</Label>
              {targetType === 'gateway' ? (
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={targetId}
                  onChange={(e) => setTargetId(e.target.value)}
                >
                  <option value="">(select)</option>
                  {gateways.map((g) => (
                    <option key={g.InternetGatewayId} value={g.InternetGatewayId}>
                      {g.InternetGatewayId}
                    </option>
                  ))}
                </select>
              ) : (
                <Input
                  value={targetId}
                  onChange={(e) => setTargetId(e.target.value)}
                  placeholder={
                    targetType === 'natgateway' ? 'nat-…' : 'i-…'
                  }
                />
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!cidr || !targetId || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    destinationCidr: cidr,
                    gatewayId: targetType === 'gateway' ? targetId : undefined,
                    natGatewayId:
                      targetType === 'natgateway' ? targetId : undefined,
                    instanceId:
                      targetType === 'instance' ? targetId : undefined,
                  });
                  toast.success('Route added');
                  setOpen(false);
                  setTargetId('');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Add
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

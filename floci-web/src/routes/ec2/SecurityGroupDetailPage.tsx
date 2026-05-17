import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import type { IpPermission } from '@aws-sdk/client-ec2';
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
  useAuthorizeRule,
  useRevokeRule,
  useSecurityGroup,
} from '@/hooks/ec2/useSecurityGroups';

function renderSource(rule: IpPermission): string {
  const parts: string[] = [];
  for (const r of rule.IpRanges ?? []) parts.push(r.CidrIp ?? '');
  for (const r of rule.Ipv6Ranges ?? []) parts.push(r.CidrIpv6 ?? '');
  for (const r of rule.UserIdGroupPairs ?? []) parts.push(r.GroupId ?? '');
  return parts.filter(Boolean).join(', ') || '—';
}

function renderPortRange(rule: IpPermission): string {
  if (rule.IpProtocol === '-1') return 'all';
  if (rule.FromPort === rule.ToPort) return `${rule.FromPort ?? '—'}`;
  return `${rule.FromPort ?? '—'} – ${rule.ToPort ?? '—'}`;
}

export default function SecurityGroupDetailPage() {
  const { id = '' } = useParams();
  const { data: sg, isLoading } = useSecurityGroup(id);
  const authorize = useAuthorizeRule('ingress');
  const authorizeEgress = useAuthorizeRule('egress');
  const revoke = useRevokeRule('ingress');
  const revokeEgress = useRevokeRule('egress');

  const [open, setOpen] = useState<'ingress' | 'egress' | null>(null);
  const [protocol, setProtocol] = useState('tcp');
  const [fromPort, setFromPort] = useState(22);
  const [toPort, setToPort] = useState(22);
  const [cidr, setCidr] = useState('0.0.0.0/0');

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!sg) {
    return (
      <div className="space-y-4">
        <Link
          to="/ec2/security-groups"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Security groups
        </Link>
        <p className="text-sm text-zinc-500">Security group not found.</p>
      </div>
    );
  }

  async function addRule() {
    const dir = open!;
    const rule: IpPermission = {
      IpProtocol: protocol,
      FromPort: protocol === '-1' ? undefined : fromPort,
      ToPort: protocol === '-1' ? undefined : toPort,
      IpRanges: [{ CidrIp: cidr }],
    };
    try {
      const mut = dir === 'ingress' ? authorize : authorizeEgress;
      await mut.mutateAsync({ groupId: id, rule });
      toast.success(`${dir} rule added`);
      setOpen(null);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  async function removeRule(direction: 'ingress' | 'egress', rule: IpPermission) {
    if (!confirm('Revoke this rule?')) return;
    try {
      const mut = direction === 'ingress' ? revoke : revokeEgress;
      await mut.mutateAsync({ groupId: id, rule });
      toast.success('Revoked');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  function rulesTable(rules: IpPermission[], direction: 'ingress' | 'egress') {
    return (
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Protocol</TableHead>
            <TableHead>Port range</TableHead>
            <TableHead>Source / Destination</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {rules.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-6 text-sm text-zinc-500">
                No rules.
              </TableCell>
            </TableRow>
          )}
          {rules.map((r, i) => (
            <TableRow key={`${direction}-${i}`}>
              <TableCell>{r.IpProtocol === '-1' ? 'all' : r.IpProtocol}</TableCell>
              <TableCell>{renderPortRange(r)}</TableCell>
              <TableCell className="font-mono text-xs">
                {renderSource(r)}
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={() => removeRule(direction, r)}
                >
                  <Trash2 className="size-4" />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    );
  }

  return (
    <div className="space-y-4">
      <Link
        to="/ec2/security-groups"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Security groups
      </Link>

      <div>
        <h1 className="text-2xl font-semibold">{sg.GroupName}</h1>
        <p className="text-xs text-zinc-500 mt-1">
          <span className="font-mono">{sg.GroupId}</span> ·{' '}
          <span className="font-mono">{sg.VpcId}</span>
        </p>
      </div>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Ingress rules</CardTitle>
            <Button size="sm" onClick={() => setOpen('ingress')}>
              <Plus className="size-4" /> Add rule
            </Button>
          </div>
        </CardHeader>
        <CardContent>{rulesTable(sg.IpPermissions ?? [], 'ingress')}</CardContent>
      </Card>

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <CardTitle>Egress rules</CardTitle>
            <Button size="sm" onClick={() => setOpen('egress')}>
              <Plus className="size-4" /> Add rule
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {rulesTable(sg.IpPermissionsEgress ?? [], 'egress')}
        </CardContent>
      </Card>

      <Dialog open={open !== null} onOpenChange={(o) => !o && setOpen(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              Add {open} rule
            </DialogTitle>
          </DialogHeader>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Protocol</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={protocol}
                onChange={(e) => setProtocol(e.target.value)}
              >
                <option value="tcp">TCP</option>
                <option value="udp">UDP</option>
                <option value="icmp">ICMP</option>
                <option value="-1">All</option>
              </select>
            </div>
            <div>
              <Label>CIDR</Label>
              <Input
                value={cidr}
                onChange={(e) => setCidr(e.target.value)}
                placeholder="0.0.0.0/0"
              />
            </div>
            {protocol !== '-1' && (
              <>
                <div>
                  <Label>From port</Label>
                  <Input
                    type="number"
                    value={fromPort}
                    onChange={(e) =>
                      setFromPort(Number(e.target.value) || 0)
                    }
                  />
                </div>
                <div>
                  <Label>To port</Label>
                  <Input
                    type="number"
                    value={toPort}
                    onChange={(e) => setToPort(Number(e.target.value) || 0)}
                  />
                </div>
              </>
            )}
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(null)}>
              Cancel
            </Button>
            <Button onClick={addRule}>Add</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

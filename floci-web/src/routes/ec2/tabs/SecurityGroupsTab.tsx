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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateSecurityGroup,
  useDeleteSecurityGroup,
  useSecurityGroups,
} from '@/hooks/ec2/useSecurityGroups';
import { useVpcs } from '@/hooks/ec2/useVpcs';

export default function SecurityGroupsTab() {
  const { data: groups = [], isLoading } = useSecurityGroups();
  const { data: vpcs = [] } = useVpcs();
  const create = useCreateSecurityGroup();
  const del = useDeleteSecurityGroup();

  const [open, setOpen] = useState(false);
  const [groupName, setGroupName] = useState('');
  const [description, setDescription] = useState('');
  const [vpcId, setVpcId] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {groups.length} security group{groups.length === 1 ? '' : 's'}.
          Rules are stored and returned but not enforced at the network layer —
          Docker bridge networking handles routing.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New security group
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Group ID</TableHead>
            <TableHead>Name</TableHead>
            <TableHead>VPC</TableHead>
            <TableHead>Description</TableHead>
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
          {!isLoading && groups.length === 0 && (
            <TableRow>
              <TableCell colSpan={5} className="text-center py-8 text-sm text-zinc-500">
                No security groups.
              </TableCell>
            </TableRow>
          )}
          {groups.map((g) => (
            <TableRow key={g.GroupId}>
              <TableCell>
                <Link
                  to={`/ec2/security-groups/${encodeURIComponent(g.GroupId!)}`}
                  className="font-mono text-xs hover:underline"
                >
                  {g.GroupId}
                </Link>
              </TableCell>
              <TableCell className="font-medium">{g.GroupName}</TableCell>
              <TableCell className="font-mono text-xs">{g.VpcId ?? '—'}</TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {g.Description ?? '—'}
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={g.GroupName === 'default'}
                  onClick={async () => {
                    if (!confirm(`Delete ${g.GroupId}?`)) return;
                    try {
                      await del.mutateAsync(g.GroupId!);
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
            <DialogTitle>Create security group</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Group name</Label>
              <Input
                value={groupName}
                onChange={(e) => setGroupName(e.target.value)}
              />
            </div>
            <div>
              <Label>Description</Label>
              <Input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
            <div>
              <Label>VPC</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={vpcId}
                onChange={(e) => setVpcId(e.target.value)}
              >
                <option value="">(default VPC)</option>
                {vpcs.map((v) => (
                  <option key={v.VpcId} value={v.VpcId}>
                    {v.VpcId} · {v.CidrBlock}
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
              disabled={!groupName || !description || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    groupName,
                    description,
                    vpcId: vpcId || undefined,
                  });
                  toast.success('Created');
                  setOpen(false);
                  setGroupName('');
                  setDescription('');
                  setVpcId('');
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

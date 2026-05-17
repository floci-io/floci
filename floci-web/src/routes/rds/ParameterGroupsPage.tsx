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
  useCreateParameterGroup,
  useDeleteParameterGroup,
  useParameterGroups,
} from '@/hooks/rds/useParameterGroups';

export default function ParameterGroupsPage() {
  const { data: groups = [], isLoading } = useParameterGroups();
  const create = useCreateParameterGroup();
  const del = useDeleteParameterGroup();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [family, setFamily] = useState('postgres16');
  const [description, setDescription] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {groups.length} parameter group{groups.length === 1 ? '' : 's'}
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New parameter group
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Family</TableHead>
            <TableHead>Description</TableHead>
            <TableHead className="w-24" />
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
          {!isLoading && groups.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                No parameter groups.
              </TableCell>
            </TableRow>
          )}
          {groups.map((g) => (
            <TableRow key={g.DBParameterGroupName}>
              <TableCell>
                <Link
                  to={`/rds/parameter-groups/${encodeURIComponent(g.DBParameterGroupName!)}`}
                  className="font-medium hover:underline"
                >
                  {g.DBParameterGroupName}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">
                {g.DBParameterGroupFamily}
              </TableCell>
              <TableCell className="text-zinc-600">
                {g.Description ?? '—'}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${g.DBParameterGroupName}?`)) return;
                    try {
                      await del.mutateAsync(g.DBParameterGroupName!);
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
            <DialogTitle>Create parameter group</DialogTitle>
            <DialogDescription>
              Override engine parameters and attach the group to instances.
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Name</Label>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="my-pg-params"
              />
            </div>
            <div>
              <Label>Family</Label>
              <Input
                value={family}
                onChange={(e) => setFamily(e.target.value)}
                placeholder="postgres16 / mysql8.0 / mariadb11"
              />
            </div>
            <div>
              <Label>Description</Label>
              <Input
                value={description}
                onChange={(e) => setDescription(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!name || !family || !description || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({ name, family, description });
                  toast.success('Created');
                  setOpen(false);
                  setName('');
                  setDescription('');
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

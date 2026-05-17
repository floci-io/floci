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
  useCreateScheduleGroup,
  useDeleteScheduleGroup,
  useScheduleGroups,
} from '@/hooks/scheduler/useScheduleGroups';
import { formatDate } from '@/lib/format';

export default function ScheduleGroupsTab() {
  const { data: groups = [], isLoading } = useScheduleGroups();
  const create = useCreateScheduleGroup();
  const del = useDeleteScheduleGroup();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {groups.length} group{groups.length === 1 ? '' : 's'}. The{' '}
          <code>default</code> group is created automatically and cannot be
          deleted.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New group
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Created</TableHead>
            <TableHead>Modified</TableHead>
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
                No groups.
              </TableCell>
            </TableRow>
          )}
          {groups.map((g) => (
            <TableRow key={g.Name}>
              <TableCell>
                <Link
                  to={`/scheduler/groups/${encodeURIComponent(g.Name!)}`}
                  className="font-medium hover:underline"
                >
                  {g.Name}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">{g.State ?? '—'}</TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {formatDate(g.CreationDate)}
              </TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {formatDate(g.LastModificationDate)}
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={g.Name === 'default'}
                  onClick={async () => {
                    if (
                      !confirm(
                        `Delete group "${g.Name}"? Schedules inside it will also be deleted.`,
                      )
                    )
                      return;
                    try {
                      await del.mutateAsync(g.Name!);
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
            <DialogTitle>Create schedule group</DialogTitle>
          </DialogHeader>
          <div>
            <Label>Name</Label>
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="my-group"
            />
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!name || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({ name });
                  toast.success('Created');
                  setOpen(false);
                  setName('');
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

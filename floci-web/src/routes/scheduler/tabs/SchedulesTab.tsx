import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Pencil, Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { ScheduleState } from '@aws-sdk/client-scheduler';
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
  useDeleteSchedule,
  useSchedules,
} from '@/hooks/scheduler/useSchedules';
import { useScheduleGroups } from '@/hooks/scheduler/useScheduleGroups';
import { ScheduleDialog } from '../ScheduleDialog';
import { cn } from '@/lib/cn';
import { formatDate } from '@/lib/format';

function StatePill({ state }: { state?: string }) {
  const enabled = state === ScheduleState.ENABLED;
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
        enabled
          ? 'bg-green-100 text-green-800'
          : 'bg-zinc-100 text-zinc-700',
      )}
    >
      {state ?? '—'}
    </span>
  );
}

export default function SchedulesTab() {
  const [groupFilter, setGroupFilter] = useState('');
  const { data: schedules = [], isLoading } = useSchedules(
    groupFilter || undefined,
  );
  const { data: groups = [] } = useScheduleGroups();
  const del = useDeleteSchedule();
  const [open, setOpen] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between flex-wrap gap-3">
        <div className="flex items-center gap-3">
          <div>
            <Label>Group filter</Label>
            <select
              className="flex h-9 w-56 rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={groupFilter}
              onChange={(e) => setGroupFilter(e.target.value)}
            >
              <option value="">(all groups)</option>
              {groups.map((g) => (
                <option key={g.Name} value={g.Name}>
                  {g.Name}
                </option>
              ))}
            </select>
          </div>
          <p className="text-sm text-zinc-500 self-end pb-2">
            {schedules.length} schedule{schedules.length === 1 ? '' : 's'}
          </p>
        </div>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New schedule
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Group</TableHead>
            <TableHead>Expression</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Target</TableHead>
            <TableHead>Modified</TableHead>
            <TableHead className="w-24" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={7} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && schedules.length === 0 && (
            <TableRow>
              <TableCell colSpan={7} className="text-center py-8 text-sm text-zinc-500">
                No schedules.
              </TableCell>
            </TableRow>
          )}
          {schedules.map((s) => (
            <TableRow key={`${s.GroupName}/${s.Name}`}>
              <TableCell>
                <Link
                  to={`/scheduler/schedules/${encodeURIComponent(s.GroupName ?? 'default')}/${encodeURIComponent(s.Name!)}`}
                  className="font-medium hover:underline"
                >
                  {s.Name}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">{s.GroupName}</TableCell>
              <TableCell className="font-mono text-xs">
                {/* ScheduleSummary doesn't include the expression — show target type only */}
                —
              </TableCell>
              <TableCell>
                <StatePill state={s.State} />
              </TableCell>
              <TableCell className="font-mono text-xs">
                {s.Target?.Arn?.split(':').slice(-1)[0] ?? '—'}
              </TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {formatDate(s.LastModificationDate)}
              </TableCell>
              <TableCell className="text-right whitespace-nowrap">
                <Button
                  variant="ghost"
                  size="icon"
                  asChild
                  title="Edit"
                >
                  <Link
                    to={`/scheduler/schedules/${encodeURIComponent(s.GroupName ?? 'default')}/${encodeURIComponent(s.Name!)}?edit=1`}
                  >
                    <Pencil className="size-4" />
                  </Link>
                </Button>
                <Button
                  variant="ghost"
                  size="icon"
                  title="Delete"
                  onClick={async () => {
                    if (!confirm(`Delete ${s.Name}?`)) return;
                    try {
                      await del.mutateAsync({
                        name: s.Name!,
                        groupName: s.GroupName ?? 'default',
                      });
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

      <ScheduleDialog
        open={open}
        onOpenChange={setOpen}
        mode={{ kind: 'create' }}
      />
    </div>
  );
}

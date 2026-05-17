import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ChevronLeft, Pencil, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import {
  ActionAfterCompletion,
  FlexibleTimeWindowMode,
  ScheduleState,
} from '@aws-sdk/client-scheduler';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useDeleteSchedule,
  useSchedule,
} from '@/hooks/scheduler/useSchedules';
import { ScheduleDialog } from './ScheduleDialog';
import { formatDate } from '@/lib/format';
import { cn } from '@/lib/cn';

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

export default function ScheduleDetailPage() {
  const { groupName = '', name = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const nav = useNavigate();
  const { data: schedule, isLoading } = useSchedule(name, groupName);
  const del = useDeleteSchedule();
  const [editOpen, setEditOpen] = useState(params.get('edit') === '1');

  useEffect(() => {
    if (params.get('edit') === '1' && !editOpen) setEditOpen(true);
  }, [params, editOpen]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!schedule) {
    return (
      <div className="space-y-4">
        <Link
          to="/scheduler/schedules"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Schedules
        </Link>
        <p className="text-sm text-zinc-500">Schedule not found.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <Link
        to="/scheduler/schedules"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Schedules
      </Link>

      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{schedule.Name}</h1>
          <p className="text-xs text-zinc-500 mt-1 flex items-center gap-2 flex-wrap">
            <span className="font-mono">{schedule.GroupName}</span>
            <span>·</span>
            <StatePill state={schedule.State} />
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setEditOpen(true)}>
            <Pencil className="size-4" /> Edit
          </Button>
          <Button
            variant="destructive"
            onClick={async () => {
              if (!confirm(`Delete ${schedule.Name}?`)) return;
              try {
                await del.mutateAsync({
                  name: schedule.Name!,
                  groupName: schedule.GroupName ?? 'default',
                });
                toast.success('Deleted');
                nav('/scheduler/schedules');
              } catch (e) {
                const err = e as { message?: string };
                toast.error(err.message ?? 'Failed');
              }
            }}
          >
            <Trash2 className="size-4" /> Delete
          </Button>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <Card>
          <CardHeader>
            <CardTitle>Expression</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 text-sm">
            <div className="col-span-2">
              <p className="text-zinc-500">Schedule expression</p>
              <p className="font-mono text-xs">
                {schedule.ScheduleExpression}
              </p>
            </div>
            <div>
              <p className="text-zinc-500">Timezone</p>
              <p>{schedule.ScheduleExpressionTimezone ?? 'UTC'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Action after completion</p>
              <p>{schedule.ActionAfterCompletion ?? 'NONE'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Start date</p>
              <p className="text-xs">{formatDate(schedule.StartDate)}</p>
            </div>
            <div>
              <p className="text-zinc-500">End date</p>
              <p className="text-xs">{formatDate(schedule.EndDate)}</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Flexible time window</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-zinc-500">Mode</p>
              <p>{schedule.FlexibleTimeWindow?.Mode}</p>
            </div>
            <div>
              <p className="text-zinc-500">Max window (min)</p>
              <p>
                {schedule.FlexibleTimeWindow?.MaximumWindowInMinutes ?? '—'}
              </p>
            </div>
            <p className="col-span-2 text-xs text-zinc-500">
              Floci stores the window but fires deterministically — no jitter
              is applied yet.
            </p>
          </CardContent>
        </Card>

        <Card className="col-span-2">
          <CardHeader>
            <CardTitle>Target</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="grid grid-cols-2 gap-3">
              <div>
                <p className="text-zinc-500">Target ARN</p>
                <p className="font-mono text-xs break-all">
                  {schedule.Target?.Arn ?? '—'}
                </p>
              </div>
              <div>
                <p className="text-zinc-500">Role ARN</p>
                <p className="font-mono text-xs break-all">
                  {schedule.Target?.RoleArn ?? '—'}
                </p>
              </div>
            </div>
            {schedule.Target?.Input && (
              <div>
                <p className="text-zinc-500">Input payload</p>
                <pre className="mt-1 text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[240px]">
                  {schedule.Target.Input}
                </pre>
              </div>
            )}
            {(schedule.Target?.RetryPolicy ||
              schedule.Target?.DeadLetterConfig) && (
              <div className="text-xs text-zinc-500 rounded-md bg-amber-50 border border-amber-200 p-2">
                Note: RetryPolicy and DeadLetterConfig are stored but not
                honored by Floci's dispatcher yet.
              </div>
            )}
          </CardContent>
        </Card>

        {schedule.Description && (
          <Card className="col-span-2">
            <CardHeader>
              <CardTitle>Description</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm">{schedule.Description}</p>
            </CardContent>
          </Card>
        )}
      </div>

      <ScheduleDialog
        open={editOpen}
        onOpenChange={(o) => {
          setEditOpen(o);
          if (!o && params.get('edit') === '1') {
            params.delete('edit');
            setParams(params);
          }
        }}
        mode={{
          kind: 'edit',
          name: schedule.Name!,
          groupName: schedule.GroupName ?? 'default',
          expression: schedule.ScheduleExpression!,
          timezone: schedule.ScheduleExpressionTimezone,
          state: schedule.State ?? ScheduleState.ENABLED,
          flexMode:
            schedule.FlexibleTimeWindow?.Mode ?? FlexibleTimeWindowMode.OFF,
          flexMinutes: schedule.FlexibleTimeWindow?.MaximumWindowInMinutes,
          startDate: schedule.StartDate,
          endDate: schedule.EndDate,
          target: schedule.Target!,
          actionAfterCompletion:
            (schedule.ActionAfterCompletion as ActionAfterCompletion) ?? undefined,
          description: schedule.Description,
        }}
      />
    </div>
  );
}

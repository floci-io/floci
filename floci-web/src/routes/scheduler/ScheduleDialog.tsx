import { useState } from 'react';
import { toast } from 'sonner';
import {
  ActionAfterCompletion,
  FlexibleTimeWindowMode,
  ScheduleState,
  type Target,
} from '@aws-sdk/client-scheduler';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  useCreateSchedule,
  useUpdateSchedule,
} from '@/hooks/scheduler/useSchedules';
import { useScheduleGroups } from '@/hooks/scheduler/useScheduleGroups';
import { ExpressionBuilder } from './ExpressionBuilder';

export type ScheduleDialogMode =
  | { kind: 'create' }
  | {
      kind: 'edit';
      name: string;
      groupName: string;
      expression: string;
      timezone?: string;
      state: ScheduleState;
      flexMode: FlexibleTimeWindowMode;
      flexMinutes?: number;
      startDate?: Date;
      endDate?: Date;
      target: Target;
      actionAfterCompletion?: ActionAfterCompletion;
      description?: string;
    };

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  mode: ScheduleDialogMode;
}

function toLocalIsoForInput(d: Date | undefined): string {
  if (!d) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function fromLocalIsoInput(s: string): Date | undefined {
  if (!s) return undefined;
  const d = new Date(s);
  return isNaN(d.getTime()) ? undefined : d;
}

export function ScheduleDialog({ open, onOpenChange, mode }: Props) {
  const { data: groups = [] } = useScheduleGroups();
  const create = useCreateSchedule();
  const update = useUpdateSchedule();
  const isEdit = mode.kind === 'edit';

  const [name, setName] = useState(isEdit ? mode.name : '');
  const [groupName, setGroupName] = useState(isEdit ? mode.groupName : 'default');
  const [expression, setExpression] = useState(
    isEdit ? mode.expression : 'rate(1 hour)',
  );
  const [timezone, setTimezone] = useState(isEdit ? mode.timezone ?? '' : '');
  const [enabled, setEnabled] = useState(
    isEdit ? mode.state === ScheduleState.ENABLED : true,
  );
  const [description, setDescription] = useState(
    isEdit ? mode.description ?? '' : '',
  );
  const [startDate, setStartDate] = useState(
    isEdit ? toLocalIsoForInput(mode.startDate) : '',
  );
  const [endDate, setEndDate] = useState(
    isEdit ? toLocalIsoForInput(mode.endDate) : '',
  );
  const [flexMode, setFlexMode] = useState<FlexibleTimeWindowMode>(
    isEdit ? mode.flexMode : FlexibleTimeWindowMode.OFF,
  );
  const [flexMinutes, setFlexMinutes] = useState<number | ''>(
    isEdit ? mode.flexMinutes ?? '' : '',
  );
  const [actionAfter, setActionAfter] = useState<ActionAfterCompletion>(
    isEdit
      ? (mode.actionAfterCompletion ?? ActionAfterCompletion.NONE)
      : ActionAfterCompletion.NONE,
  );

  const [targetArn, setTargetArn] = useState(isEdit ? mode.target.Arn ?? '' : '');
  const [targetRoleArn, setTargetRoleArn] = useState(
    isEdit ? mode.target.RoleArn ?? '' : 'arn:aws:iam::000000000000:role/scheduler-role',
  );
  const [targetInput, setTargetInput] = useState(
    isEdit ? mode.target.Input ?? '' : '',
  );

  const valid = name && expression && targetArn && targetRoleArn && groupName;

  async function submit() {
    const target: Target = {
      Arn: targetArn,
      RoleArn: targetRoleArn,
      Input: targetInput || undefined,
    };
    if (targetInput) {
      try {
        JSON.parse(targetInput);
      } catch {
        toast.error('Target input is not valid JSON');
        return;
      }
    }
    const payload = {
      Name: name,
      GroupName: groupName,
      ScheduleExpression: expression,
      ScheduleExpressionTimezone: timezone || undefined,
      Description: description || undefined,
      State: enabled ? ScheduleState.ENABLED : ScheduleState.DISABLED,
      StartDate: fromLocalIsoInput(startDate),
      EndDate: fromLocalIsoInput(endDate),
      FlexibleTimeWindow: {
        Mode: flexMode,
        MaximumWindowInMinutes:
          flexMode === FlexibleTimeWindowMode.FLEXIBLE &&
          flexMinutes !== '' &&
          Number(flexMinutes) > 0
            ? Number(flexMinutes)
            : undefined,
      },
      Target: target,
      ActionAfterCompletion:
        expression.startsWith('at(') ? actionAfter : undefined,
    };
    try {
      if (isEdit) {
        await update.mutateAsync(payload);
        toast.success('Updated');
      } else {
        await create.mutateAsync(payload);
        toast.success('Created');
      }
      onOpenChange(false);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Failed');
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>
            {isEdit ? `Edit ${name}` : 'Create schedule'}
          </DialogTitle>
          <DialogDescription>
            Targets: SQS, Lambda, SNS, or EventBridge <code>PutEvents</code>.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Name</Label>
              <Input
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={isEdit}
              />
            </div>
            <div>
              <Label>Group</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={groupName}
                onChange={(e) => setGroupName(e.target.value)}
                disabled={isEdit}
              >
                <option value="default">default</option>
                {groups
                  .filter((g) => g.Name && g.Name !== 'default')
                  .map((g) => (
                    <option key={g.Name} value={g.Name}>
                      {g.Name}
                    </option>
                  ))}
              </select>
            </div>
          </div>
          <div>
            <Label>Description</Label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
            />
          </div>

          <div>
            <Label>Schedule expression</Label>
            <ExpressionBuilder value={expression} onChange={setExpression} />
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label>Timezone</Label>
              <Input
                value={timezone}
                onChange={(e) => setTimezone(e.target.value)}
                placeholder="UTC"
              />
            </div>
            <div>
              <Label>Start date (local)</Label>
              <Input
                type="datetime-local"
                value={startDate}
                onChange={(e) => setStartDate(e.target.value)}
              />
            </div>
            <div>
              <Label>End date (local)</Label>
              <Input
                type="datetime-local"
                value={endDate}
                onChange={(e) => setEndDate(e.target.value)}
              />
            </div>
          </div>

          <div className="rounded-md border border-zinc-200 p-3 space-y-3">
            <div className="flex items-center justify-between">
              <Label>Flexible time window</Label>
              <Switch
                checked={flexMode === FlexibleTimeWindowMode.FLEXIBLE}
                onCheckedChange={(v) =>
                  setFlexMode(
                    v
                      ? FlexibleTimeWindowMode.FLEXIBLE
                      : FlexibleTimeWindowMode.OFF,
                  )
                }
              />
            </div>
            {flexMode === FlexibleTimeWindowMode.FLEXIBLE && (
              <div>
                <Label>Maximum window (minutes)</Label>
                <Input
                  type="number"
                  value={flexMinutes}
                  onChange={(e) =>
                    setFlexMinutes(
                      e.target.value === '' ? '' : Number(e.target.value),
                    )
                  }
                  placeholder="1-1440"
                />
                <p className="text-xs text-zinc-500 mt-1">
                  Floci stores this but fires deterministically — no jitter is
                  applied yet.
                </p>
              </div>
            )}
          </div>

          {expression.startsWith('at(') && (
            <div className="rounded-md border border-zinc-200 p-3 space-y-2">
              <Label>Action after completion (one-time only)</Label>
              <div className="flex gap-4 text-sm">
                <label className="inline-flex items-center gap-1">
                  <input
                    type="radio"
                    checked={actionAfter === ActionAfterCompletion.NONE}
                    onChange={() => setActionAfter(ActionAfterCompletion.NONE)}
                  />
                  <span>NONE</span>
                </label>
                <label className="inline-flex items-center gap-1">
                  <input
                    type="radio"
                    checked={actionAfter === ActionAfterCompletion.DELETE}
                    onChange={() =>
                      setActionAfter(ActionAfterCompletion.DELETE)
                    }
                  />
                  <span>DELETE</span>
                </label>
              </div>
            </div>
          )}

          <div className="rounded-md border border-zinc-200 p-3 space-y-3">
            <Label className="text-base">Target</Label>
            <div>
              <Label>Target ARN</Label>
              <Input
                value={targetArn}
                onChange={(e) => setTargetArn(e.target.value)}
                placeholder="arn:aws:lambda:us-east-1:000000000000:function:my-func"
              />
            </div>
            <div>
              <Label>Role ARN</Label>
              <Input
                value={targetRoleArn}
                onChange={(e) => setTargetRoleArn(e.target.value)}
              />
            </div>
            <div>
              <Label>Input (JSON payload)</Label>
              <textarea
                className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-xs min-h-[80px] font-mono"
                value={targetInput}
                onChange={(e) => setTargetInput(e.target.value)}
                placeholder='{"event":"my-event"}'
              />
            </div>
          </div>

          <div className="flex items-center justify-between rounded-md border border-zinc-200 p-3">
            <div>
              <Label>State</Label>
              <p className="text-xs text-zinc-500">
                Disabled schedules are skipped by the dispatcher.
              </p>
            </div>
            <Switch checked={enabled} onCheckedChange={setEnabled} />
          </div>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            disabled={!valid || create.isPending || update.isPending}
            onClick={submit}
          >
            {isEdit ? 'Save' : 'Create'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

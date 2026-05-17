import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Play, Plus, Power, RotateCw, Square, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  useInstances,
  useRebootInstances,
  useStartInstances,
  useStopInstances,
  useTerminateInstances,
} from '@/hooks/ec2/useInstances';
import { LaunchInstanceDialog } from '../LaunchInstanceDialog';
import { StatePill, tagsValue } from '../StatePill';

export default function InstancesTab() {
  const { data: instances = [], isLoading } = useInstances();
  const start = useStartInstances();
  const stop = useStopInstances();
  const reboot = useRebootInstances();
  const terminate = useTerminateInstances();
  const [open, setOpen] = useState(false);

  async function runAction(
    label: string,
    op: { mutateAsync: (ids: string[]) => Promise<void> },
    id: string,
  ) {
    try {
      await op.mutateAsync([id]);
      toast.success(`${label} ${id}`);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? `${label} failed`);
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {instances.length} instance{instances.length === 1 ? '' : 's'}
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Launch instance
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Instance ID</TableHead>
            <TableHead>Name</TableHead>
            <TableHead>Type</TableHead>
            <TableHead>State</TableHead>
            <TableHead>AMI</TableHead>
            <TableHead>Private IP</TableHead>
            <TableHead className="w-44 text-right" />
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
          {!isLoading && instances.length === 0 && (
            <TableRow>
              <TableCell colSpan={7} className="text-center py-8 text-sm text-zinc-500">
                No instances.
              </TableCell>
            </TableRow>
          )}
          {instances.map((i) => {
            const name = tagsValue(i.Tags, 'Name');
            const state = i.State?.Name;
            const isRunning = state === 'running';
            const isStopped = state === 'stopped';
            const isTerminated = state === 'terminated';
            return (
              <TableRow key={i.InstanceId}>
                <TableCell>
                  <Link
                    to={`/ec2/instances/${encodeURIComponent(i.InstanceId!)}`}
                    className="font-mono text-xs hover:underline"
                  >
                    {i.InstanceId}
                  </Link>
                </TableCell>
                <TableCell className="font-medium">{name ?? '—'}</TableCell>
                <TableCell className="text-zinc-600">
                  {i.InstanceType}
                </TableCell>
                <TableCell>
                  <StatePill state={state} />
                </TableCell>
                <TableCell className="font-mono text-xs text-zinc-500">
                  {i.ImageId}
                </TableCell>
                <TableCell className="font-mono text-xs text-zinc-500">
                  {i.PrivateIpAddress ?? '—'}
                </TableCell>
                <TableCell className="text-right whitespace-nowrap">
                  {isStopped && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Start"
                      onClick={() => runAction('Started', start, i.InstanceId!)}
                    >
                      <Play className="size-4" />
                    </Button>
                  )}
                  {isRunning && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Stop"
                      onClick={() => runAction('Stopped', stop, i.InstanceId!)}
                    >
                      <Square className="size-4" />
                    </Button>
                  )}
                  {isRunning && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Reboot"
                      onClick={() => runAction('Rebooting', reboot, i.InstanceId!)}
                    >
                      <RotateCw className="size-4" />
                    </Button>
                  )}
                  {!isTerminated && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="Terminate"
                      onClick={() => {
                        if (!confirm(`Terminate ${i.InstanceId}?`)) return;
                        runAction('Terminating', terminate, i.InstanceId!);
                      }}
                    >
                      <Power className="size-4" />
                    </Button>
                  )}
                  {isTerminated && (
                    <Button
                      variant="ghost"
                      size="icon"
                      title="(terminated)"
                      disabled
                    >
                      <Trash2 className="size-4" />
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            );
          })}
        </TableBody>
      </Table>

      <LaunchInstanceDialog open={open} onOpenChange={setOpen} />
    </div>
  );
}

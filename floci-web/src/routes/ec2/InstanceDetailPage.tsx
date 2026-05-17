import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Play, Power, RotateCw, Square } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useInstance,
  useRebootInstances,
  useStartInstances,
  useStopInstances,
  useTerminateInstances,
} from '@/hooks/ec2/useInstances';
import { StatePill, tagsValue } from './StatePill';
import { formatDate } from '@/lib/format';

function decodeBase64(s: string | undefined): string {
  if (!s) return '';
  try {
    return atob(s);
  } catch {
    return s;
  }
}

export default function InstanceDetailPage() {
  const { id = '' } = useParams();
  const { data: inst, isLoading } = useInstance(id);
  const start = useStartInstances();
  const stop = useStopInstances();
  const reboot = useRebootInstances();
  const terminate = useTerminateInstances();

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!inst) {
    return (
      <div className="space-y-4">
        <Link
          to="/ec2/instances"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Instances
        </Link>
        <p className="text-sm text-zinc-500">Instance not found.</p>
      </div>
    );
  }

  const state = inst.State?.Name;
  const name = tagsValue(inst.Tags, 'Name');

  async function runAction(
    label: string,
    op: { mutateAsync: (ids: string[]) => Promise<void> },
  ) {
    try {
      await op.mutateAsync([id]);
      toast.success(label);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? `${label} failed`);
    }
  }

  return (
    <div className="space-y-4">
      <Link
        to="/ec2/instances"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Instances
      </Link>

      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{name ?? inst.InstanceId}</h1>
          <p className="text-xs text-zinc-500 mt-1 flex items-center gap-2 flex-wrap">
            <span className="font-mono">{inst.InstanceId}</span>
            <span>·</span>
            <span>{inst.InstanceType}</span>
            <span>·</span>
            <StatePill state={state} />
          </p>
        </div>
        <div className="flex gap-2">
          {state === 'stopped' && (
            <Button
              variant="outline"
              onClick={() => runAction('Started', start)}
            >
              <Play className="size-4" /> Start
            </Button>
          )}
          {state === 'running' && (
            <Button
              variant="outline"
              onClick={() => runAction('Stopped', stop)}
            >
              <Square className="size-4" /> Stop
            </Button>
          )}
          {state === 'running' && (
            <Button
              variant="outline"
              onClick={() => runAction('Rebooting', reboot)}
            >
              <RotateCw className="size-4" /> Reboot
            </Button>
          )}
          {state !== 'terminated' && (
            <Button
              variant="destructive"
              onClick={() => {
                if (!confirm(`Terminate ${id}?`)) return;
                runAction('Terminating', terminate);
              }}
            >
              <Power className="size-4" /> Terminate
            </Button>
          )}
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <Card>
          <CardHeader>
            <CardTitle>Network</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-zinc-500">Private IP</p>
              <p className="font-mono text-xs">
                {inst.PrivateIpAddress ?? '—'}
              </p>
            </div>
            <div>
              <p className="text-zinc-500">Public IP</p>
              <p className="font-mono text-xs">
                {inst.PublicIpAddress ?? '—'}
              </p>
            </div>
            <div>
              <p className="text-zinc-500">VPC</p>
              <p className="font-mono text-xs">{inst.VpcId ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Subnet</p>
              <p className="font-mono text-xs">{inst.SubnetId ?? '—'}</p>
            </div>
            <div className="col-span-2">
              <p className="text-zinc-500">Security groups</p>
              <p className="font-mono text-xs">
                {(inst.SecurityGroups ?? [])
                  .map((g) => `${g.GroupId} (${g.GroupName})`)
                  .join(', ') || '—'}
              </p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Access</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-2 gap-3 text-sm">
            <div>
              <p className="text-zinc-500">Key pair</p>
              <p className="font-mono text-xs">{inst.KeyName ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Architecture</p>
              <p>{inst.Architecture ?? '—'}</p>
            </div>
            <div className="col-span-2">
              <p className="text-zinc-500">IAM instance profile</p>
              <p className="font-mono text-xs break-all">
                {inst.IamInstanceProfile?.Arn ?? '—'}
              </p>
            </div>
            <div className="col-span-2">
              <p className="text-zinc-500">IMDS endpoint</p>
              <p className="font-mono text-xs">
                http://169.254.169.254/latest/meta-data/
              </p>
              <p className="text-xs text-zinc-500 mt-1">
                Floci injects <code>AWS_EC2_METADATA_SERVICE_ENDPOINT</code>{' '}
                pointing at host port 9169.
              </p>
            </div>
          </CardContent>
        </Card>

        <Card className="col-span-2">
          <CardHeader>
            <CardTitle>Instance details</CardTitle>
          </CardHeader>
          <CardContent className="grid grid-cols-3 gap-3 text-sm">
            <div>
              <p className="text-zinc-500">AMI</p>
              <p className="font-mono text-xs">{inst.ImageId}</p>
            </div>
            <div>
              <p className="text-zinc-500">Availability zone</p>
              <p>{inst.Placement?.AvailabilityZone ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Launched</p>
              <p className="text-xs">{formatDate(inst.LaunchTime)}</p>
            </div>
            <div>
              <p className="text-zinc-500">State transition</p>
              <p className="text-xs">
                {inst.StateTransitionReason || '—'}
              </p>
            </div>
            <div>
              <p className="text-zinc-500">Hypervisor</p>
              <p>{inst.Hypervisor ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Virtualization</p>
              <p>{inst.VirtualizationType ?? '—'}</p>
            </div>
          </CardContent>
        </Card>

        {inst.Tags && inst.Tags.length > 0 && (
          <Card className="col-span-2">
            <CardHeader>
              <CardTitle>Tags</CardTitle>
            </CardHeader>
            <CardContent>
              <div className="flex flex-wrap gap-2">
                {inst.Tags.map((t) => (
                  <span
                    key={t.Key}
                    className="inline-flex items-center gap-1 rounded-full bg-zinc-100 px-3 py-1 text-xs"
                  >
                    <span className="font-medium">{t.Key}</span>
                    <span className="text-zinc-400">=</span>
                    <span>{t.Value}</span>
                  </span>
                ))}
              </div>
            </CardContent>
          </Card>
        )}

        {inst.ClientToken && (
          <Card className="col-span-2">
            <CardHeader>
              <CardTitle>User data (decoded)</CardTitle>
            </CardHeader>
            <CardContent>
              <pre className="text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[240px] whitespace-pre-wrap">
                {decodeBase64(inst.ClientToken) ||
                  '(user data is not returned by DescribeInstances)'}
              </pre>
              <p className="text-xs text-zinc-500 mt-2">
                Floci does not return UserData on{' '}
                <code>DescribeInstances</code>; to inspect it, use{' '}
                <code>aws ec2 describe-instance-attribute --attribute userData</code>.
              </p>
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}

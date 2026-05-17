import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Copy, RotateCw, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input, Label } from '@/components/ui/input';
import { Switch } from '@/components/ui/switch';
import {
  useDBInstance,
  useDeleteDBInstance,
  useModifyDBInstance,
  useRebootDBInstance,
} from '@/hooks/rds/useInstances';
import { useNavigate } from 'react-router-dom';
import { StatusPill } from './StatusPill';
import { formatDate } from '@/lib/format';

function connectionString(
  engine: string | undefined,
  endpoint: { Address?: string; Port?: number } | undefined,
  user: string | undefined,
): string {
  if (!endpoint?.Address) return '';
  const host = endpoint.Address;
  const port = endpoint.Port ?? '';
  const u = user ?? 'admin';
  if (engine?.startsWith('postgres') || engine === 'aurora-postgresql') {
    return `psql -h ${host} -p ${port} -U ${u}`;
  }
  if (engine?.startsWith('mysql') || engine === 'aurora-mysql') {
    return `mysql -h ${host} -P ${port} -u ${u} -p`;
  }
  if (engine === 'mariadb') {
    return `mariadb -h ${host} -P ${port} -u ${u} -p`;
  }
  return `${host}:${port}`;
}

export default function InstanceDetailPage() {
  const { identifier = '' } = useParams();
  const nav = useNavigate();
  const { data: inst, isLoading } = useDBInstance(identifier);
  const reboot = useRebootDBInstance();
  const del = useDeleteDBInstance();
  const modify = useModifyDBInstance();

  const [instanceClass, setInstanceClass] = useState('');
  const [allocatedStorage, setAllocatedStorage] = useState<number | ''>('');
  const [iam, setIam] = useState(false);

  useEffect(() => {
    setInstanceClass(inst?.DBInstanceClass ?? '');
    setAllocatedStorage(inst?.AllocatedStorage ?? '');
    setIam(inst?.IAMDatabaseAuthenticationEnabled ?? false);
  }, [inst]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;
  if (!inst) {
    return (
      <div className="space-y-4">
        <Link
          to="/rds/instances"
          className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
        >
          <ChevronLeft className="size-4" /> Instances
        </Link>
        <p className="text-sm text-zinc-500">Instance not found.</p>
      </div>
    );
  }

  const conn = connectionString(inst.Engine, inst.Endpoint, inst.MasterUsername);

  return (
    <div className="space-y-4">
      <Link
        to="/rds/instances"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Instances
      </Link>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{inst.DBInstanceIdentifier}</h1>
          <div className="flex items-center gap-2 mt-1 text-sm text-zinc-500">
            <span>{inst.Engine}</span>
            <StatusPill status={inst.DBInstanceStatus} />
          </div>
        </div>
        <div className="flex gap-2">
          <Button
            variant="outline"
            onClick={async () => {
              try {
                await reboot.mutateAsync(identifier);
                toast.success('Reboot triggered');
              } catch (e) {
                const err = e as { message?: string };
                toast.error(err.message ?? 'Reboot failed');
              }
            }}
          >
            <RotateCw className="size-4" /> Reboot
          </Button>
          <Button
            variant="destructive"
            onClick={async () => {
              if (!confirm(`Delete ${identifier}?`)) return;
              try {
                await del.mutateAsync({ identifier });
                toast.success('Deletion started');
                nav('/rds/instances');
              } catch (e) {
                const err = e as { message?: string };
                toast.error(err.message ?? 'Delete failed');
              }
            }}
          >
            <Trash2 className="size-4" /> Delete
          </Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Connection</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-zinc-500">Endpoint</p>
              <p className="font-mono text-xs">
                {inst.Endpoint?.Address ?? '—'}
              </p>
            </div>
            <div>
              <p className="text-zinc-500">Port</p>
              <p className="font-mono text-xs">{inst.Endpoint?.Port ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">Master username</p>
              <p className="font-mono text-xs">{inst.MasterUsername ?? '—'}</p>
            </div>
            <div>
              <p className="text-zinc-500">IAM auth</p>
              <p className="text-xs">
                {inst.IAMDatabaseAuthenticationEnabled ? 'enabled' : 'disabled'}
              </p>
            </div>
          </div>
          {conn && (
            <div>
              <Label>Connection command</Label>
              <div className="mt-1 flex gap-2">
                <Input
                  value={conn}
                  readOnly
                  className="font-mono text-xs"
                />
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => {
                    void navigator.clipboard.writeText(conn);
                    toast.success('Copied');
                  }}
                >
                  <Copy className="size-4" />
                </Button>
              </div>
            </div>
          )}
          {inst.IAMDatabaseAuthenticationEnabled && (
            <p className="text-xs text-zinc-500">
              IAM auth is enabled — generate a token with{' '}
              <code>aws rds generate-db-auth-token</code> and use it as the
              password.
            </p>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Modify</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Instance class</Label>
              <Input
                value={instanceClass}
                onChange={(e) => setInstanceClass(e.target.value)}
              />
            </div>
            <div>
              <Label>Allocated storage (GiB)</Label>
              <Input
                type="number"
                value={allocatedStorage}
                onChange={(e) =>
                  setAllocatedStorage(
                    e.target.value === '' ? '' : Number(e.target.value),
                  )
                }
              />
            </div>
          </div>
          <div className="flex items-center justify-between rounded-md border border-zinc-200 p-3">
            <Label>IAM database authentication</Label>
            <Switch checked={iam} onCheckedChange={setIam} />
          </div>
          <Button
            disabled={modify.isPending}
            onClick={async () => {
              try {
                await modify.mutateAsync({
                  DBInstanceIdentifier: identifier,
                  DBInstanceClass:
                    instanceClass !== inst.DBInstanceClass
                      ? instanceClass
                      : undefined,
                  AllocatedStorage:
                    allocatedStorage !== '' &&
                    allocatedStorage !== inst.AllocatedStorage
                      ? Number(allocatedStorage)
                      : undefined,
                  EnableIAMDatabaseAuthentication:
                    iam !== inst.IAMDatabaseAuthenticationEnabled
                      ? iam
                      : undefined,
                  ApplyImmediately: true,
                });
                toast.success('Modify requested');
              } catch (e) {
                const err = e as { message?: string };
                toast.error(err.message ?? 'Modify failed');
              }
            }}
          >
            Apply
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-zinc-500">Allocated storage</p>
            <p>{inst.AllocatedStorage ?? '—'} GiB</p>
          </div>
          <div>
            <p className="text-zinc-500">Engine version</p>
            <p className="font-mono text-xs">{inst.EngineVersion ?? '—'}</p>
          </div>
          <div>
            <p className="text-zinc-500">DB cluster</p>
            <p className="font-mono text-xs">
              {inst.DBClusterIdentifier ? (
                <Link
                  to={`/rds/clusters/${encodeURIComponent(inst.DBClusterIdentifier)}`}
                  className="hover:underline"
                >
                  {inst.DBClusterIdentifier}
                </Link>
              ) : (
                '—'
              )}
            </p>
          </div>
          <div>
            <p className="text-zinc-500">Created</p>
            <p>{formatDate(inst.InstanceCreateTime)}</p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

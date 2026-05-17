import { useState } from 'react';
import { toast } from 'sonner';
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
import { useCreateDBInstance } from '@/hooks/rds/useInstances';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
  defaultCluster?: string;
}

const ENGINES = ['postgres', 'mysql', 'mariadb', 'aurora-mysql', 'aurora-postgresql'];

export function CreateInstanceDialog({
  open,
  onOpenChange,
  defaultCluster,
}: Props) {
  const create = useCreateDBInstance();
  const [identifier, setIdentifier] = useState('');
  const [engine, setEngine] = useState('postgres');
  const [instanceClass, setInstanceClass] = useState('db.t3.micro');
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [allocatedStorage, setAllocatedStorage] = useState(20);
  const [iam, setIam] = useState(false);
  const [cluster, setCluster] = useState(defaultCluster ?? '');

  function reset() {
    setIdentifier('');
    setEngine('postgres');
    setInstanceClass('db.t3.micro');
    setUsername('admin');
    setPassword('');
    setAllocatedStorage(20);
    setIam(false);
    setCluster(defaultCluster ?? '');
  }

  const isAurora = engine.startsWith('aurora');

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Create DB instance</DialogTitle>
          <DialogDescription>
            Floci will start a real Docker container for this engine and proxy
            TCP traffic to it.
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <div className="col-span-2">
            <Label>Identifier</Label>
            <Input
              value={identifier}
              onChange={(e) => setIdentifier(e.target.value)}
              placeholder="mypostgres"
            />
          </div>
          <div>
            <Label>Engine</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={engine}
              onChange={(e) => setEngine(e.target.value)}
            >
              {ENGINES.map((eng) => (
                <option key={eng} value={eng}>
                  {eng}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Instance class</Label>
            <Input
              value={instanceClass}
              onChange={(e) => setInstanceClass(e.target.value)}
            />
          </div>
          <div>
            <Label>Master username</Label>
            <Input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={!!cluster}
            />
          </div>
          <div>
            <Label>Master password</Label>
            <Input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder={cluster ? '(inherited from cluster)' : '••••••••'}
              disabled={!!cluster}
            />
          </div>
          <div>
            <Label>Allocated storage (GiB)</Label>
            <Input
              type="number"
              value={allocatedStorage}
              onChange={(e) =>
                setAllocatedStorage(Number(e.target.value) || 20)
              }
            />
          </div>
          <div>
            <Label>Cluster identifier (optional)</Label>
            <Input
              value={cluster}
              onChange={(e) => setCluster(e.target.value)}
              placeholder={isAurora ? 'required for Aurora' : '(none)'}
            />
          </div>
          <div className="col-span-2 flex items-center justify-between rounded-md border border-zinc-200 p-3">
            <div>
              <Label>Enable IAM database authentication</Label>
              <p className="text-xs text-zinc-500">
                Generate tokens with{' '}
                <code>aws rds generate-db-auth-token</code>.
              </p>
            </div>
            <Switch checked={iam} onCheckedChange={setIam} />
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            disabled={
              !identifier ||
              (!password && !cluster) ||
              create.isPending
            }
            onClick={async () => {
              try {
                await create.mutateAsync({
                  DBInstanceIdentifier: identifier,
                  Engine: engine,
                  DBInstanceClass: instanceClass,
                  MasterUsername: cluster ? undefined : username,
                  MasterUserPassword: cluster ? undefined : password,
                  AllocatedStorage: allocatedStorage,
                  EnableIAMDatabaseAuthentication: iam,
                  DBClusterIdentifier: cluster || undefined,
                });
                toast.success(`Created ${identifier}`);
                onOpenChange(false);
                reset();
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
  );
}

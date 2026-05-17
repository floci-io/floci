import { useState } from 'react';
import { toast } from 'sonner';
import type { _InstanceType } from '@aws-sdk/client-ec2';
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
import { useRunInstances } from '@/hooks/ec2/useInstances';
import { useKeyPairs } from '@/hooks/ec2/useKeyPairs';
import { useSubnets } from '@/hooks/ec2/useSubnets';
import { useSecurityGroups } from '@/hooks/ec2/useSecurityGroups';

interface Props {
  open: boolean;
  onOpenChange: (o: boolean) => void;
}

const FLOCI_AMIS = [
  'ami-amazonlinux2023',
  'ami-amazonlinux2',
  'ami-ubuntu2204',
  'ami-ubuntu2004',
  'ami-debian12',
  'ami-alpine',
];

const INSTANCE_TYPES = [
  't2.micro',
  't2.small',
  't2.medium',
  't3.micro',
  't3.small',
  't3.medium',
];

export function LaunchInstanceDialog({ open, onOpenChange }: Props) {
  const run = useRunInstances();
  const { data: keyPairs = [] } = useKeyPairs();
  const { data: subnets = [] } = useSubnets();
  const { data: securityGroups = [] } = useSecurityGroups();

  const [amiId, setAmiId] = useState('ami-amazonlinux2023');
  const [instanceType, setInstanceType] = useState('t2.micro');
  const [keyName, setKeyName] = useState('');
  const [subnetId, setSubnetId] = useState('');
  const [securityGroupId, setSecurityGroupId] = useState('');
  const [iamProfile, setIamProfile] = useState('');
  const [userData, setUserData] = useState('');
  const [tagName, setTagName] = useState('');

  function reset() {
    setKeyName('');
    setSubnetId('');
    setSecurityGroupId('');
    setIamProfile('');
    setUserData('');
    setTagName('');
  }

  async function launch() {
    try {
      const tagSpec = tagName
        ? [
            {
              ResourceType: 'instance' as const,
              Tags: [{ Key: 'Name', Value: tagName }],
            },
          ]
        : undefined;
      await run.mutateAsync({
        ImageId: amiId,
        InstanceType: instanceType as _InstanceType,
        MinCount: 1,
        MaxCount: 1,
        KeyName: keyName || undefined,
        SubnetId: subnetId || undefined,
        SecurityGroupIds: securityGroupId ? [securityGroupId] : undefined,
        IamInstanceProfile: iamProfile ? { Arn: iamProfile } : undefined,
        UserData: userData ? btoa(userData) : undefined,
        TagSpecifications: tagSpec,
      });
      toast.success('Instance launched');
      onOpenChange(false);
      reset();
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Launch failed');
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Launch instance</DialogTitle>
          <DialogDescription>
            Floci will start a real Docker container mapped from the AMI.
            UserData is executed after SSH key injection.
          </DialogDescription>
        </DialogHeader>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <Label>AMI</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={amiId}
              onChange={(e) => setAmiId(e.target.value)}
            >
              {FLOCI_AMIS.map((id) => (
                <option key={id} value={id}>
                  {id}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Instance type</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={instanceType}
              onChange={(e) => setInstanceType(e.target.value)}
            >
              {INSTANCE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Name tag</Label>
            <Input
              value={tagName}
              onChange={(e) => setTagName(e.target.value)}
              placeholder="my-instance"
            />
          </div>
          <div>
            <Label>Key pair</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={keyName}
              onChange={(e) => setKeyName(e.target.value)}
            >
              <option value="">(none)</option>
              {keyPairs.map((k) => (
                <option key={k.KeyPairId} value={k.KeyName}>
                  {k.KeyName}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Subnet</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={subnetId}
              onChange={(e) => setSubnetId(e.target.value)}
            >
              <option value="">(default)</option>
              {subnets.map((s) => (
                <option key={s.SubnetId} value={s.SubnetId}>
                  {s.SubnetId} · {s.CidrBlock} · {s.AvailabilityZone}
                </option>
              ))}
            </select>
          </div>
          <div>
            <Label>Security group</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={securityGroupId}
              onChange={(e) => setSecurityGroupId(e.target.value)}
            >
              <option value="">(default)</option>
              {securityGroups.map((g) => (
                <option key={g.GroupId} value={g.GroupId}>
                  {g.GroupId} · {g.GroupName}
                </option>
              ))}
            </select>
          </div>
          <div className="col-span-2">
            <Label>IAM instance profile ARN</Label>
            <Input
              value={iamProfile}
              onChange={(e) => setIamProfile(e.target.value)}
              placeholder="arn:aws:iam::000000000000:instance-profile/my-app-role"
            />
            <p className="text-xs text-zinc-500 mt-1">
              When set, IMDS serves temporary credentials for SDK clients
              inside the container.
            </p>
          </div>
          <div className="col-span-2">
            <Label>User data</Label>
            <textarea
              className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-sm min-h-[120px] font-mono"
              value={userData}
              onChange={(e) => setUserData(e.target.value)}
              placeholder="#!/bin/bash&#10;yum install -y nginx&#10;systemctl start nginx"
            />
            <p className="text-xs text-zinc-500 mt-1">
              Floci base64-encodes this before sending. Output is captured
              into the container's logs.
            </p>
          </div>
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={launch} disabled={run.isPending}>
            {run.isPending ? 'Launching…' : 'Launch'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

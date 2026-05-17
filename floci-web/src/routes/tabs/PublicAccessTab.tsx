import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Switch } from '@/components/ui/switch';
import { Label } from '@/components/ui/input';
import {
  usePublicAccessBlock,
  usePutPublicAccessBlock,
} from '@/hooks/s3/usePublicAccessBlock';

const FIELDS: Array<{
  key: 'BlockPublicAcls' | 'IgnorePublicAcls' | 'BlockPublicPolicy' | 'RestrictPublicBuckets';
  label: string;
  description: string;
}> = [
  {
    key: 'BlockPublicAcls',
    label: 'Block public ACLs',
    description: 'Reject PUT calls that grant any public ACL.',
  },
  {
    key: 'IgnorePublicAcls',
    label: 'Ignore public ACLs',
    description: 'Ignore existing public ACLs when serving objects.',
  },
  {
    key: 'BlockPublicPolicy',
    label: 'Block public policy',
    description: 'Reject bucket policies that grant public access.',
  },
  {
    key: 'RestrictPublicBuckets',
    label: 'Restrict public buckets',
    description: 'Only allow access from AWS principals and authorized users.',
  },
];

export default function PublicAccessTab() {
  const { bucket = '' } = useParams();
  const { data: server } = usePublicAccessBlock(bucket);
  const putPAB = usePutPublicAccessBlock(bucket);
  const [cfg, setCfg] = useState({
    BlockPublicAcls: false,
    IgnorePublicAcls: false,
    BlockPublicPolicy: false,
    RestrictPublicBuckets: false,
  });

  useEffect(() => {
    setCfg({
      BlockPublicAcls: server?.BlockPublicAcls ?? false,
      IgnorePublicAcls: server?.IgnorePublicAcls ?? false,
      BlockPublicPolicy: server?.BlockPublicPolicy ?? false,
      RestrictPublicBuckets: server?.RestrictPublicBuckets ?? false,
    });
  }, [server]);

  return (
    <div className="space-y-4">
      <p className="text-sm text-zinc-500">
        Block Public Access settings gate ACLs and policies before they take
        effect. Save with all toggles off (and Delete) to remove the
        configuration entirely.
      </p>
      <div className="rounded-md border border-zinc-200 bg-white p-4 space-y-3">
        {FIELDS.map((f) => (
          <div
            key={f.key}
            className="flex items-start justify-between gap-4"
          >
            <div>
              <Label>{f.label}</Label>
              <p className="text-xs text-zinc-500">{f.description}</p>
            </div>
            <Switch
              checked={cfg[f.key]}
              onCheckedChange={(v) => setCfg({ ...cfg, [f.key]: v })}
            />
          </div>
        ))}
      </div>
      <div className="flex gap-2">
        <Button
          disabled={putPAB.isPending}
          onClick={async () => {
            try {
              await putPAB.mutateAsync(cfg);
              toast.success('Saved');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Save
        </Button>
        <Button
          variant="outline"
          disabled={putPAB.isPending}
          onClick={async () => {
            if (!confirm('Delete Public Access Block configuration?')) return;
            try {
              await putPAB.mutateAsync(null);
              toast.success('Deleted');
            } catch (e) {
              const err = e as { message?: string };
              toast.error(err.message ?? 'Failed');
            }
          }}
        >
          Delete configuration
        </Button>
      </div>
    </div>
  );
}

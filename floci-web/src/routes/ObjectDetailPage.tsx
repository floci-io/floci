import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { ChevronLeft, Download } from 'lucide-react';
import { toast } from 'sonner';
import type {
  ObjectCannedACL,
  ObjectLockLegalHoldStatus,
  ObjectLockRetentionMode,
  Tag,
} from '@aws-sdk/client-s3';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PresignDialog } from '@/components/PresignDialog';
import { useObjectDetail, useObjectAcl, usePutObjectAcl } from '@/hooks/s3/useObjectDetail';
import { useObjectTags, usePutObjectTags } from '@/hooks/s3/useTags';
import {
  useLegalHold,
  useObjectLock,
  useObjectRetention,
  usePutLegalHold,
  usePutRetention,
} from '@/hooks/s3/useObjectLock';
import { presignGet } from '@/lib/presign';
import { formatBytes, formatDate, trimEtag } from '@/lib/format';

export default function ObjectDetailPage() {
  const { bucket = '' } = useParams();
  const [params] = useSearchParams();
  const key = params.get('key') ?? '';

  const { data: attrs } = useObjectDetail(bucket, key);
  const { data: acl } = useObjectAcl(bucket, key);
  const putAcl = usePutObjectAcl(bucket, key);
  const { data: tags } = useObjectTags(bucket, key);
  const putTags = usePutObjectTags(bucket, key);
  const { data: lock } = useObjectLock(bucket);
  const { data: retention } = useObjectRetention(bucket, key);
  const putRetention = usePutRetention(bucket, key);
  const { data: legalHold } = useLegalHold(bucket, key);
  const putLegalHold = usePutLegalHold(bucket, key);

  const [tagsDraft, setTagsDraft] = useState<Tag[]>([]);
  const [presignOpen, setPresignOpen] = useState(false);

  useEffect(() => {
    setTagsDraft(tags ?? []);
  }, [tags]);

  async function download() {
    try {
      const url = await presignGet(bucket, key, 300);
      window.open(url, '_blank');
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Presign failed');
    }
  }

  return (
    <div className="space-y-4">
      <Link
        to={`/s3/b/${encodeURIComponent(bucket)}`}
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> {bucket}
      </Link>
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold break-all">{key}</h1>
          <p className="text-xs text-zinc-500">in bucket {bucket}</p>
        </div>
        <div className="flex gap-2">
          <Button onClick={download} variant="outline">
            <Download className="size-4" /> Download
          </Button>
          <Button onClick={() => setPresignOpen(true)}>Presign URL</Button>
        </div>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Overview</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-zinc-500">Size</p>
            <p>{formatBytes(attrs?.ObjectSize)}</p>
          </div>
          <div>
            <p className="text-zinc-500">Last modified</p>
            <p>{formatDate(attrs?.LastModified)}</p>
          </div>
          <div>
            <p className="text-zinc-500">ETag</p>
            <p className="font-mono text-xs">{trimEtag(attrs?.ETag)}</p>
          </div>
          <div>
            <p className="text-zinc-500">Storage class</p>
            <p>{attrs?.StorageClass ?? 'STANDARD'}</p>
          </div>
          {attrs?.Checksum?.ChecksumSHA256 && (
            <div className="col-span-2">
              <p className="text-zinc-500">SHA-256</p>
              <p className="font-mono text-xs break-all">
                {attrs.Checksum.ChecksumSHA256}
              </p>
            </div>
          )}
          {attrs?.ObjectParts && (
            <div className="col-span-2">
              <p className="text-zinc-500">Multipart parts</p>
              <p>{attrs.ObjectParts.TotalPartsCount ?? 0}</p>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Tags</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2">
          {tagsDraft.map((t, i) => (
            <div key={i} className="flex gap-2">
              <Input
                placeholder="Key"
                value={t.Key ?? ''}
                onChange={(e) => {
                  const next = [...tagsDraft];
                  next[i] = { ...next[i], Key: e.target.value };
                  setTagsDraft(next);
                }}
              />
              <Input
                placeholder="Value"
                value={t.Value ?? ''}
                onChange={(e) => {
                  const next = [...tagsDraft];
                  next[i] = { ...next[i], Value: e.target.value };
                  setTagsDraft(next);
                }}
              />
              <Button
                variant="ghost"
                size="sm"
                onClick={() =>
                  setTagsDraft(tagsDraft.filter((_, j) => j !== i))
                }
              >
                Remove
              </Button>
            </div>
          ))}
          <div className="flex gap-2 pt-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                setTagsDraft([...tagsDraft, { Key: '', Value: '' }])
              }
            >
              Add tag
            </Button>
            <Button
              size="sm"
              disabled={putTags.isPending}
              onClick={async () => {
                try {
                  await putTags.mutateAsync(
                    tagsDraft.filter((t) => (t.Key ?? '') !== ''),
                  );
                  toast.success('Saved');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Save tags
            </Button>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>ACL</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          <p className="text-zinc-500">
            Owner: <code>{acl?.Owner?.ID ?? '—'}</code>. Only canned ACLs are
            supported.
          </p>
          <div className="flex gap-2 flex-wrap">
            {(['private', 'public-read', 'public-read-write', 'authenticated-read', 'bucket-owner-read', 'bucket-owner-full-control'] as ObjectCannedACL[]).map(
              (c) => (
                <Button
                  key={c}
                  variant="outline"
                  size="sm"
                  onClick={async () => {
                    try {
                      await putAcl.mutateAsync(c);
                      toast.success(`ACL set: ${c}`);
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  {c}
                </Button>
              ),
            )}
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Object Lock</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {!lock?.enabled && (
            <p className="text-zinc-500">
              Object Lock is not enabled on this bucket. Retention and legal
              hold cannot be applied.
            </p>
          )}
          {lock?.enabled && (
            <>
              <div className="grid grid-cols-3 gap-3">
                <div>
                  <Label>Retention mode</Label>
                  <select
                    className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                    defaultValue={retention?.Mode ?? ''}
                    onChange={(e) => {
                      (e.target as HTMLSelectElement).dataset.mode =
                        e.target.value;
                    }}
                    id="ret-mode"
                  >
                    <option value="">(none)</option>
                    <option value="GOVERNANCE">GOVERNANCE</option>
                    <option value="COMPLIANCE">COMPLIANCE</option>
                  </select>
                </div>
                <div>
                  <Label>Retain until (ISO date)</Label>
                  <Input
                    defaultValue={
                      retention?.RetainUntilDate
                        ? new Date(retention.RetainUntilDate)
                            .toISOString()
                            .slice(0, 10)
                        : ''
                    }
                    placeholder="2026-12-31"
                    id="ret-date"
                  />
                </div>
                <div className="flex items-end">
                  <Button
                    size="sm"
                    onClick={async () => {
                      const mode = (
                        document.getElementById('ret-mode') as HTMLSelectElement
                      ).value;
                      const date = (
                        document.getElementById('ret-date') as HTMLInputElement
                      ).value;
                      if (!mode || !date) {
                        toast.error('Mode and date are required');
                        return;
                      }
                      try {
                        await putRetention.mutateAsync({
                          Mode: mode as ObjectLockRetentionMode,
                          RetainUntilDate: new Date(date),
                        });
                        toast.success('Retention set');
                      } catch (e) {
                        const err = e as { message?: string };
                        toast.error(err.message ?? 'Failed');
                      }
                    }}
                  >
                    Set retention
                  </Button>
                </div>
              </div>
              <div className="flex items-center gap-3 pt-2 border-t border-zinc-200">
                <div className="flex-1">
                  <Label>Legal hold</Label>
                  <p className="text-xs text-zinc-500">
                    Current: <code>{legalHold ?? 'OFF'}</code>
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={async () => {
                    const next: ObjectLockLegalHoldStatus =
                      legalHold === 'ON' ? 'OFF' : 'ON';
                    try {
                      await putLegalHold.mutateAsync(next);
                      toast.success(`Legal hold ${next}`);
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  Toggle to {legalHold === 'ON' ? 'OFF' : 'ON'}
                </Button>
              </div>
            </>
          )}
        </CardContent>
      </Card>

      <PresignDialog
        bucket={bucket}
        initialKey={key}
        open={presignOpen}
        onOpenChange={setPresignOpen}
      />
    </div>
  );
}

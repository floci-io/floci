import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Download, ExternalLink, Upload } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useFunction,
  useUpdateFunctionCode,
} from '@/hooks/lambda/useFunctions';
import { fileToUint8Array } from '../runtimes';

type Source = 'zip' | 's3';

export default function CodeTab() {
  const { name = '' } = useParams();
  const { data, isLoading } = useFunction(name);
  const update = useUpdateFunctionCode();

  const [source, setSource] = useState<Source>('zip');
  const [zipFile, setZipFile] = useState<File | null>(null);
  const [s3Bucket, setS3Bucket] = useState('');
  const [s3Key, setS3Key] = useState('');

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;

  const code = data?.Code;
  const cfg = data?.Configuration;

  async function submit() {
    try {
      if (source === 'zip') {
        if (!zipFile) {
          toast.error('Pick a ZIP file');
          return;
        }
        await update.mutateAsync({
          FunctionName: name,
          ZipFile: await fileToUint8Array(zipFile),
        });
      } else {
        if (!s3Bucket || !s3Key) {
          toast.error('Bucket and key required');
          return;
        }
        await update.mutateAsync({
          FunctionName: name,
          S3Bucket: s3Bucket,
          S3Key: s3Key,
        });
      }
      toast.success('Code updated');
      setZipFile(null);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Update failed');
    }
  }

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Current code</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-2 gap-3 text-sm">
          <div>
            <p className="text-zinc-500">Package type</p>
            <p>{cfg?.PackageType ?? '—'}</p>
          </div>
          <div>
            <p className="text-zinc-500">Code size</p>
            <p>{cfg?.CodeSize ?? 0} bytes</p>
          </div>
          <div className="col-span-2">
            <p className="text-zinc-500">Code SHA-256</p>
            <p className="font-mono text-xs break-all">
              {cfg?.CodeSha256 ?? '—'}
            </p>
          </div>
          {code?.RepositoryType && (
            <div>
              <p className="text-zinc-500">Repository type</p>
              <p>{code.RepositoryType}</p>
            </div>
          )}
          {code?.Location && (
            <div className="col-span-2">
              <p className="text-zinc-500">Code download URL</p>
              <a
                href={code.Location}
                target="_blank"
                rel="noreferrer"
                className="inline-flex items-center gap-1 text-zinc-900 hover:underline text-xs font-mono break-all"
              >
                <Download className="size-3" /> {code.Location}
                <ExternalLink className="size-3" />
              </a>
            </div>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Upload new code</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex gap-4 text-sm">
            <label className="inline-flex items-center gap-1">
              <input
                type="radio"
                checked={source === 'zip'}
                onChange={() => setSource('zip')}
              />
              <span>Upload ZIP</span>
            </label>
            <label className="inline-flex items-center gap-1">
              <input
                type="radio"
                checked={source === 's3'}
                onChange={() => setSource('s3')}
              />
              <span>From S3</span>
            </label>
          </div>
          {source === 'zip' && (
            <div>
              <Input
                type="file"
                accept=".zip,application/zip"
                onChange={(e) => setZipFile(e.target.files?.[0] ?? null)}
              />
              {zipFile && (
                <p className="text-xs text-zinc-500 mt-1">
                  {zipFile.name} · {(zipFile.size / 1024).toFixed(1)} KB
                </p>
              )}
            </div>
          )}
          {source === 's3' && (
            <div className="grid grid-cols-2 gap-2">
              <div>
                <Label>S3 bucket</Label>
                <Input
                  value={s3Bucket}
                  onChange={(e) => setS3Bucket(e.target.value)}
                />
              </div>
              <div>
                <Label>S3 key</Label>
                <Input
                  value={s3Key}
                  onChange={(e) => setS3Key(e.target.value)}
                />
              </div>
              <p className="col-span-2 text-xs text-zinc-500">
                Updates to this S3 object will reactively re-sync the function
                code without further API calls.
              </p>
            </div>
          )}
          <Button onClick={submit} disabled={update.isPending}>
            <Upload className="size-4" /> Update code
          </Button>
        </CardContent>
      </Card>
    </div>
  );
}

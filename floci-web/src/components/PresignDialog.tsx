import { useState } from 'react';
import { Copy } from 'lucide-react';
import { toast } from 'sonner';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from './ui/dialog';
import { Button } from './ui/button';
import { Input, Label } from './ui/input';
import { presignGet, presignPut } from '@/lib/presign';

interface Props {
  bucket: string;
  initialKey?: string;
  open: boolean;
  onOpenChange: (o: boolean) => void;
}

export function PresignDialog({
  bucket,
  initialKey = '',
  open,
  onOpenChange,
}: Props) {
  const [key, setKey] = useState(initialKey);
  const [method, setMethod] = useState<'GET' | 'PUT'>('GET');
  const [expiresIn, setExpiresIn] = useState(3600);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);

  async function generate() {
    setBusy(true);
    try {
      const u =
        method === 'GET'
          ? await presignGet(bucket, key, expiresIn)
          : await presignPut(bucket, key, expiresIn);
      setUrl(u);
    } catch (e) {
      const err = e as { message?: string };
      toast.error(`Failed: ${err.message}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <DialogTitle>Generate pre-signed URL</DialogTitle>
          <DialogDescription>
            URLs are signed with the local <code>test/test</code> credentials.
            Floci validates them server-side against{' '}
            <code>FLOCI_AUTH_PRESIGN_SECRET</code>.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div>
            <Label>Object key</Label>
            <Input value={key} onChange={(e) => setKey(e.target.value)} />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label>Method</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={method}
                onChange={(e) =>
                  setMethod(e.target.value as 'GET' | 'PUT')
                }
              >
                <option value="GET">GET (download)</option>
                <option value="PUT">PUT (upload)</option>
              </select>
            </div>
            <div>
              <Label>Expires in (seconds)</Label>
              <Input
                type="number"
                value={expiresIn}
                onChange={(e) =>
                  setExpiresIn(Number(e.target.value) || 3600)
                }
                min={60}
              />
            </div>
          </div>
          {url && (
            <div>
              <Label>URL</Label>
              <div className="mt-1 flex gap-2">
                <Input value={url} readOnly className="font-mono text-xs" />
                <Button
                  variant="outline"
                  size="icon"
                  onClick={() => {
                    void navigator.clipboard.writeText(url);
                    toast.success('Copied');
                  }}
                >
                  <Copy className="size-4" />
                </Button>
              </div>
            </div>
          )}
        </div>
        <DialogFooter>
          <Button
            variant="outline"
            onClick={() => onOpenChange(false)}
          >
            Close
          </Button>
          <Button onClick={generate} disabled={!key || busy}>
            {busy ? 'Generating…' : 'Generate'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

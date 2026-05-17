import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateKeyPair,
  useDeleteKeyPair,
  useImportKeyPair,
  useKeyPairs,
} from '@/hooks/ec2/useKeyPairs';

export default function KeyPairsTab() {
  const { data: keys = [], isLoading } = useKeyPairs();
  const create = useCreateKeyPair();
  const importKey = useImportKeyPair();
  const del = useDeleteKeyPair();

  const [mode, setMode] = useState<'create' | 'import'>('import');
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [publicKey, setPublicKey] = useState('');
  const [createdMaterial, setCreatedMaterial] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {keys.length} key pair{keys.length === 1 ? '' : 's'}. Floci's{' '}
          <code>CreateKeyPair</code> returns a dummy PEM — for real SSH access
          use <code>ImportKeyPair</code> with your existing public key.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Add key pair
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Fingerprint</TableHead>
            <TableHead>Key ID</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && keys.length === 0 && (
            <TableRow>
              <TableCell colSpan={4} className="text-center py-8 text-sm text-zinc-500">
                No key pairs.
              </TableCell>
            </TableRow>
          )}
          {keys.map((k) => (
            <TableRow key={k.KeyPairId}>
              <TableCell className="font-medium">{k.KeyName}</TableCell>
              <TableCell className="font-mono text-xs">
                {k.KeyFingerprint}
              </TableCell>
              <TableCell className="font-mono text-xs">{k.KeyPairId}</TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${k.KeyName}?`)) return;
                    try {
                      await del.mutateAsync(k.KeyName!);
                      toast.success('Deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  <Trash2 className="size-4" />
                </Button>
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>

      <Dialog
        open={open}
        onOpenChange={(o) => {
          setOpen(o);
          if (!o) {
            setName('');
            setPublicKey('');
            setCreatedMaterial(null);
          }
        }}
      >
        <DialogContent>
          <DialogHeader>
            <DialogTitle>
              {createdMaterial ? 'Created key pair' : 'Add key pair'}
            </DialogTitle>
            <DialogDescription>
              {createdMaterial
                ? 'Save the private key now — Floci will not show it again.'
                : 'Pick "Import" to paste an existing public key for working SSH.'}
            </DialogDescription>
          </DialogHeader>
          {createdMaterial ? (
            <pre className="text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[320px] whitespace-pre-wrap">
              {createdMaterial}
            </pre>
          ) : (
            <div className="space-y-3">
              <div className="flex gap-3 text-sm">
                <label className="inline-flex items-center gap-1">
                  <input
                    type="radio"
                    checked={mode === 'import'}
                    onChange={() => setMode('import')}
                  />
                  <span>Import existing public key</span>
                </label>
                <label className="inline-flex items-center gap-1">
                  <input
                    type="radio"
                    checked={mode === 'create'}
                    onChange={() => setMode('create')}
                  />
                  <span>Create new (dummy PEM)</span>
                </label>
              </div>
              <div>
                <Label>Key name</Label>
                <Input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="my-key"
                />
              </div>
              {mode === 'import' && (
                <div>
                  <Label>Public key material</Label>
                  <textarea
                    className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-1 text-xs min-h-[120px] font-mono"
                    value={publicKey}
                    onChange={(e) => setPublicKey(e.target.value)}
                    placeholder="ssh-rsa AAAAB3NzaC1y…"
                  />
                </div>
              )}
            </div>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              {createdMaterial ? 'Done' : 'Cancel'}
            </Button>
            {!createdMaterial && (
              <Button
                disabled={
                  !name ||
                  (mode === 'import' && !publicKey) ||
                  create.isPending ||
                  importKey.isPending
                }
                onClick={async () => {
                  try {
                    if (mode === 'create') {
                      const r = await create.mutateAsync(name);
                      setCreatedMaterial(r.KeyMaterial ?? '(no material)');
                    } else {
                      await importKey.mutateAsync({ name, publicKey });
                      toast.success('Imported');
                      setOpen(false);
                    }
                  } catch (e) {
                    const err = e as { message?: string };
                    toast.error(err.message ?? 'Failed');
                  }
                }}
              >
                {mode === 'create' ? 'Create' : 'Import'}
              </Button>
            )}
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

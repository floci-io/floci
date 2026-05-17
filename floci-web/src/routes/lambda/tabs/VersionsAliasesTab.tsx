import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  usePublishVersion,
  useVersions,
} from '@/hooks/lambda/useVersions';
import {
  useAliases,
  useCreateAlias,
  useDeleteAlias,
} from '@/hooks/lambda/useAliases';
import { formatDate } from '@/lib/format';

export default function VersionsAliasesTab() {
  const { name = '' } = useParams();
  const { data: versions = [] } = useVersions(name);
  const publish = usePublishVersion(name);
  const { data: aliases = [] } = useAliases(name);
  const createAlias = useCreateAlias(name);
  const deleteAlias = useDeleteAlias(name);

  const [open, setOpen] = useState(false);
  const [aliasName, setAliasName] = useState('');
  const [aliasVersion, setAliasVersion] = useState('');
  const [aliasDescription, setAliasDescription] = useState('');

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Versions</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <Button
              size="sm"
              disabled={publish.isPending}
              onClick={async () => {
                try {
                  await publish.mutateAsync(undefined);
                  toast.success('Version published');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Publish new version
            </Button>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Version</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Code SHA-256</TableHead>
                <TableHead>Last modified</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {versions.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center py-6 text-sm text-zinc-500">
                    No versions.
                  </TableCell>
                </TableRow>
              )}
              {versions.map((v) => (
                <TableRow key={v.Version}>
                  <TableCell className="font-medium">{v.Version}</TableCell>
                  <TableCell className="text-zinc-500">
                    {v.Description ?? '—'}
                  </TableCell>
                  <TableCell className="font-mono text-xs text-zinc-500">
                    {(v.CodeSha256 ?? '').slice(0, 16)}…
                  </TableCell>
                  <TableCell className="text-zinc-500 text-xs">
                    {formatDate(v.LastModified)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Aliases</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <Button size="sm" onClick={() => setOpen(true)}>
              <Plus className="size-4" /> Create alias
            </Button>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Version</TableHead>
                <TableHead>Description</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {aliases.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center py-6 text-sm text-zinc-500">
                    No aliases.
                  </TableCell>
                </TableRow>
              )}
              {aliases.map((a) => (
                <TableRow key={a.AliasArn}>
                  <TableCell className="font-medium">{a.Name}</TableCell>
                  <TableCell className="font-mono text-xs">
                    {a.FunctionVersion}
                  </TableCell>
                  <TableCell className="text-zinc-500">
                    {a.Description ?? '—'}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      onClick={async () => {
                        if (!confirm(`Delete alias ${a.Name}?`)) return;
                        try {
                          await deleteAlias.mutateAsync(a.Name!);
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
        </CardContent>
      </Card>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create alias</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Alias name</Label>
              <Input
                value={aliasName}
                onChange={(e) => setAliasName(e.target.value)}
              />
            </div>
            <div>
              <Label>Function version</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={aliasVersion}
                onChange={(e) => setAliasVersion(e.target.value)}
              >
                <option value="">(select)</option>
                {versions
                  .filter((v) => v.Version && v.Version !== '$LATEST')
                  .map((v) => (
                    <option key={v.Version} value={v.Version}>
                      {v.Version}
                    </option>
                  ))}
              </select>
            </div>
            <div>
              <Label>Description</Label>
              <Input
                value={aliasDescription}
                onChange={(e) => setAliasDescription(e.target.value)}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!aliasName || !aliasVersion || createAlias.isPending}
              onClick={async () => {
                try {
                  await createAlias.mutateAsync({
                    aliasName,
                    functionVersion: aliasVersion,
                    description: aliasDescription || undefined,
                  });
                  toast.success('Created');
                  setOpen(false);
                  setAliasName('');
                  setAliasVersion('');
                  setAliasDescription('');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Create
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

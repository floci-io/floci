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
import { JsonEditor } from '@/components/JsonEditor';
import {
  useAddPermission,
  usePolicy,
  useRemovePermission,
} from '@/hooks/lambda/usePermissions';

interface Statement {
  Sid?: string;
  Action?: string | string[];
  Principal?: string | { Service?: string; AWS?: string };
  Effect?: string;
  Condition?: unknown;
}

function parseStatements(policy: string | null | undefined): Statement[] {
  if (!policy) return [];
  try {
    const doc = JSON.parse(policy);
    return Array.isArray(doc.Statement) ? doc.Statement : [];
  } catch {
    return [];
  }
}

export default function PermissionsTab() {
  const { name = '' } = useParams();
  const { data: policy, isLoading } = usePolicy(name);
  const add = useAddPermission(name);
  const remove = useRemovePermission(name);

  const [open, setOpen] = useState(false);
  const [sid, setSid] = useState('');
  const [action, setAction] = useState('lambda:InvokeFunction');
  const [principal, setPrincipal] = useState('s3.amazonaws.com');
  const [sourceArn, setSourceArn] = useState('');

  const statements = parseStatements(policy);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Resource policy statements</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="flex justify-end">
            <Button size="sm" onClick={() => setOpen(true)}>
              <Plus className="size-4" /> Add permission
            </Button>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Sid</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>Principal</TableHead>
                <TableHead className="w-12" />
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center py-6 text-sm text-zinc-500">
                    Loading…
                  </TableCell>
                </TableRow>
              )}
              {!isLoading && statements.length === 0 && (
                <TableRow>
                  <TableCell colSpan={4} className="text-center py-6 text-sm text-zinc-500">
                    No statements.
                  </TableCell>
                </TableRow>
              )}
              {statements.map((s) => (
                <TableRow key={s.Sid ?? Math.random()}>
                  <TableCell className="font-mono text-xs">
                    {s.Sid ?? '—'}
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    {Array.isArray(s.Action) ? s.Action.join(', ') : s.Action}
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    {typeof s.Principal === 'string'
                      ? s.Principal
                      : JSON.stringify(s.Principal)}
                  </TableCell>
                  <TableCell>
                    <Button
                      variant="ghost"
                      size="icon"
                      disabled={!s.Sid}
                      onClick={async () => {
                        if (!s.Sid) return;
                        if (!confirm(`Remove permission ${s.Sid}?`)) return;
                        try {
                          await remove.mutateAsync(s.Sid);
                          toast.success('Removed');
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

      {policy && (
        <Card>
          <CardHeader>
            <CardTitle>Raw policy document</CardTitle>
          </CardHeader>
          <CardContent>
            <JsonEditor
              value={JSON.stringify(JSON.parse(policy), null, 2)}
              onChange={() => {}}
              readOnly
              minHeight="240px"
            />
          </CardContent>
        </Card>
      )}

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Add permission</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Statement ID</Label>
              <Input
                value={sid}
                onChange={(e) => setSid(e.target.value)}
                placeholder="allow-s3"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Action</Label>
                <Input
                  value={action}
                  onChange={(e) => setAction(e.target.value)}
                />
              </div>
              <div>
                <Label>Principal</Label>
                <Input
                  value={principal}
                  onChange={(e) => setPrincipal(e.target.value)}
                  placeholder="s3.amazonaws.com"
                />
              </div>
            </div>
            <div>
              <Label>Source ARN (optional)</Label>
              <Input
                value={sourceArn}
                onChange={(e) => setSourceArn(e.target.value)}
                placeholder="arn:aws:s3:::my-bucket"
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!sid || !action || !principal || add.isPending}
              onClick={async () => {
                try {
                  await add.mutateAsync({
                    statementId: sid,
                    action,
                    principal,
                    sourceArn: sourceArn || undefined,
                  });
                  toast.success('Added');
                  setOpen(false);
                  setSid('');
                  setSourceArn('');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Add
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

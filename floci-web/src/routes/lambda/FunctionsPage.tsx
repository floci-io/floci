import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, Trash2 } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { useDeleteFunction, useFunctions } from '@/hooks/lambda/useFunctions';
import { CreateFunctionDialog } from './CreateFunctionDialog';
import { formatBytes, formatDate } from '@/lib/format';

export default function FunctionsPage() {
  const { data: fns = [], isLoading, error } = useFunctions();
  const del = useDeleteFunction();
  const [open, setOpen] = useState(false);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Lambda</h1>
          <p className="text-sm text-zinc-500">
            {fns.length} function{fns.length === 1 ? '' : 's'}
          </p>
        </div>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> New function
        </Button>
      </div>

      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {(error as Error).message}
        </div>
      )}

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Name</TableHead>
            <TableHead>Runtime</TableHead>
            <TableHead>Handler</TableHead>
            <TableHead>Memory</TableHead>
            <TableHead>Timeout</TableHead>
            <TableHead>Last modified</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {isLoading && (
            <TableRow>
              <TableCell colSpan={7} className="text-center text-sm text-zinc-500 py-8">
                Loading…
              </TableCell>
            </TableRow>
          )}
          {!isLoading && fns.length === 0 && (
            <TableRow>
              <TableCell colSpan={7} className="text-center text-sm text-zinc-500 py-8">
                No functions yet.
              </TableCell>
            </TableRow>
          )}
          {fns.map((f) => (
            <TableRow key={f.FunctionArn}>
              <TableCell>
                <Link
                  to={`/lambda/functions/${encodeURIComponent(f.FunctionName!)}`}
                  className="font-medium hover:underline"
                >
                  {f.FunctionName}
                </Link>
              </TableCell>
              <TableCell className="text-zinc-600">{f.Runtime ?? '—'}</TableCell>
              <TableCell className="text-zinc-600 font-mono text-xs">
                {f.Handler ?? '—'}
              </TableCell>
              <TableCell className="text-zinc-600">
                {formatBytes((f.MemorySize ?? 0) * 1024 * 1024)}
              </TableCell>
              <TableCell className="text-zinc-600">{f.Timeout ?? 0}s</TableCell>
              <TableCell className="text-zinc-500 text-xs">
                {formatDate(f.LastModified)}
              </TableCell>
              <TableCell className="text-right">
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm(`Delete ${f.FunctionName}?`)) return;
                    try {
                      await del.mutateAsync(f.FunctionName!);
                      toast.success('Deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Delete failed');
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

      <CreateFunctionDialog open={open} onOpenChange={setOpen} />
    </div>
  );
}

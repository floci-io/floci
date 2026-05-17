import { useState } from 'react';
import { useParams } from 'react-router-dom';
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
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import {
  useCreateEventSourceMapping,
  useDeleteEventSourceMapping,
  useEventSourceMappings,
} from '@/hooks/lambda/useEventSourceMappings';

const STARTING_POSITIONS = ['LATEST', 'TRIM_HORIZON'] as const;

export default function EventSourcesTab() {
  const { name = '' } = useParams();
  const { data: mappings = [] } = useEventSourceMappings(name);
  const create = useCreateEventSourceMapping();
  const del = useDeleteEventSourceMapping();

  const [open, setOpen] = useState(false);
  const [eventSourceArn, setEventSourceArn] = useState('');
  const [batchSize, setBatchSize] = useState(10);
  const [startingPosition, setStartingPosition] =
    useState<(typeof STARTING_POSITIONS)[number]>('LATEST');
  const [maxConcurrency, setMaxConcurrency] = useState<number | ''>('');

  const isStreamArn = /:kinesis:|:dynamodb:/i.test(eventSourceArn);
  const isSqsArn = /:sqs:/i.test(eventSourceArn);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-zinc-500">
          {mappings.length} mapping{mappings.length === 1 ? '' : 's'} attached
          to this function.
        </p>
        <Button onClick={() => setOpen(true)}>
          <Plus className="size-4" /> Add mapping
        </Button>
      </div>

      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>UUID</TableHead>
            <TableHead>Event source ARN</TableHead>
            <TableHead>State</TableHead>
            <TableHead>Batch</TableHead>
            <TableHead>Max concurrency</TableHead>
            <TableHead className="w-12" />
          </TableRow>
        </TableHeader>
        <TableBody>
          {mappings.length === 0 && (
            <TableRow>
              <TableCell colSpan={6} className="text-center py-8 text-sm text-zinc-500">
                No event source mappings.
              </TableCell>
            </TableRow>
          )}
          {mappings.map((m) => (
            <TableRow key={m.UUID}>
              <TableCell className="font-mono text-xs">{m.UUID}</TableCell>
              <TableCell className="font-mono text-xs">
                {m.EventSourceArn}
              </TableCell>
              <TableCell>{m.State ?? '—'}</TableCell>
              <TableCell>{m.BatchSize ?? '—'}</TableCell>
              <TableCell>
                {m.ScalingConfig?.MaximumConcurrency ?? '—'}
              </TableCell>
              <TableCell>
                <Button
                  variant="ghost"
                  size="icon"
                  onClick={async () => {
                    if (!confirm('Delete event source mapping?')) return;
                    try {
                      await del.mutateAsync(m.UUID!);
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

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Create event source mapping</DialogTitle>
          </DialogHeader>
          <div className="space-y-3">
            <div>
              <Label>Event source ARN</Label>
              <Input
                value={eventSourceArn}
                onChange={(e) => setEventSourceArn(e.target.value)}
                placeholder="arn:aws:sqs:us-east-1:000000000000:my-queue"
              />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div>
                <Label>Batch size</Label>
                <Input
                  type="number"
                  value={batchSize}
                  onChange={(e) =>
                    setBatchSize(Number(e.target.value) || 10)
                  }
                />
              </div>
              {isStreamArn && (
                <div>
                  <Label>Starting position</Label>
                  <select
                    className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                    value={startingPosition}
                    onChange={(e) =>
                      setStartingPosition(
                        e.target.value as (typeof STARTING_POSITIONS)[number],
                      )
                    }
                  >
                    {STARTING_POSITIONS.map((p) => (
                      <option key={p} value={p}>
                        {p}
                      </option>
                    ))}
                  </select>
                </div>
              )}
              {isSqsArn && (
                <div>
                  <Label>Max concurrency (2–1000)</Label>
                  <Input
                    type="number"
                    min={2}
                    max={1000}
                    value={maxConcurrency}
                    onChange={(e) =>
                      setMaxConcurrency(
                        e.target.value === ''
                          ? ''
                          : Number(e.target.value),
                      )
                    }
                  />
                </div>
              )}
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button
              disabled={!eventSourceArn || create.isPending}
              onClick={async () => {
                try {
                  await create.mutateAsync({
                    FunctionName: name,
                    EventSourceArn: eventSourceArn,
                    BatchSize: batchSize,
                    StartingPosition: isStreamArn ? startingPosition : undefined,
                    ScalingConfig:
                      isSqsArn && maxConcurrency !== ''
                        ? { MaximumConcurrency: Number(maxConcurrency) }
                        : undefined,
                  });
                  toast.success('Mapping created');
                  setOpen(false);
                  setEventSourceArn('');
                  setMaxConcurrency('');
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

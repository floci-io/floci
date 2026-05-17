import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useConcurrency,
  useDeleteConcurrency,
  usePutConcurrency,
} from '@/hooks/lambda/useConcurrency';

export default function ConcurrencyTab() {
  const { name = '' } = useParams();
  const { data, isLoading } = useConcurrency(name);
  const put = usePutConcurrency(name);
  const del = useDeleteConcurrency(name);
  const [value, setValue] = useState<number | ''>('');

  useEffect(() => {
    setValue(data ?? '');
  }, [data]);

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Reserved concurrent executions</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <p className="text-sm text-zinc-500">
            Floci enforces this value: invocations beyond it return
            HTTP 429 (TooManyRequestsException). Setting a value reduces the
            shared per-region pool — Floci requires at least{' '}
            <code>unreserved-concurrency-min</code> (default 100) to remain
            available.
          </p>
          <div>
            <Label>Reserved concurrent executions</Label>
            <Input
              type="number"
              value={value}
              onChange={(e) =>
                setValue(e.target.value === '' ? '' : Number(e.target.value))
              }
              placeholder={isLoading ? 'Loading…' : 'unset'}
            />
          </div>
          <div className="flex gap-2">
            <Button
              disabled={value === '' || put.isPending}
              onClick={async () => {
                if (value === '') return;
                try {
                  await put.mutateAsync(Number(value));
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
              disabled={del.isPending}
              onClick={async () => {
                if (!confirm('Clear reserved concurrency?')) return;
                try {
                  await del.mutateAsync();
                  toast.success('Cleared');
                } catch (e) {
                  const err = e as { message?: string };
                  toast.error(err.message ?? 'Failed');
                }
              }}
            >
              Clear
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

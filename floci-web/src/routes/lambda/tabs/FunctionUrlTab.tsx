import { useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import { Copy } from 'lucide-react';
import { toast } from 'sonner';
import { FunctionUrlAuthType } from '@aws-sdk/client-lambda';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useCreateFunctionUrl,
  useDeleteFunctionUrl,
  useFunctionUrl,
  useUpdateFunctionUrl,
} from '@/hooks/lambda/useFunctionUrl';

export default function FunctionUrlTab() {
  const { name = '' } = useParams();
  const { data, isLoading } = useFunctionUrl(name);
  const create = useCreateFunctionUrl(name);
  const update = useUpdateFunctionUrl(name);
  const del = useDeleteFunctionUrl(name);

  const [authType, setAuthType] = useState<FunctionUrlAuthType>(
    FunctionUrlAuthType.NONE,
  );

  useEffect(() => {
    setAuthType(data?.authType ?? FunctionUrlAuthType.NONE);
  }, [data]);

  if (isLoading) return <p className="text-sm text-zinc-500">Loading…</p>;

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>Function URL</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {data ? (
            <>
              <div>
                <Label>URL</Label>
                <div className="flex gap-2 mt-1">
                  <Input
                    value={data.url}
                    readOnly
                    className="font-mono text-xs"
                  />
                  <Button
                    variant="outline"
                    size="icon"
                    onClick={() => {
                      void navigator.clipboard.writeText(data.url);
                      toast.success('Copied');
                    }}
                  >
                    <Copy className="size-4" />
                  </Button>
                </div>
              </div>
              <div>
                <Label>Auth type</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={authType}
                  onChange={(e) =>
                    setAuthType(e.target.value as FunctionUrlAuthType)
                  }
                >
                  <option value={FunctionUrlAuthType.NONE}>NONE (public)</option>
                  <option value={FunctionUrlAuthType.AWS_IAM}>AWS_IAM</option>
                </select>
              </div>
              <div className="flex gap-2">
                <Button
                  disabled={update.isPending}
                  onClick={async () => {
                    try {
                      await update.mutateAsync({ authType });
                      toast.success('Updated');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  Save
                </Button>
                <Button
                  variant="destructive"
                  disabled={del.isPending}
                  onClick={async () => {
                    if (!confirm('Delete the function URL?')) return;
                    try {
                      await del.mutateAsync();
                      toast.success('Deleted');
                    } catch (e) {
                      const err = e as { message?: string };
                      toast.error(err.message ?? 'Failed');
                    }
                  }}
                >
                  Delete URL
                </Button>
              </div>
            </>
          ) : (
            <>
              <p className="text-sm text-zinc-500">
                No function URL is configured.
              </p>
              <div>
                <Label>Auth type</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={authType}
                  onChange={(e) =>
                    setAuthType(e.target.value as FunctionUrlAuthType)
                  }
                >
                  <option value={FunctionUrlAuthType.NONE}>NONE (public)</option>
                  <option value={FunctionUrlAuthType.AWS_IAM}>AWS_IAM</option>
                </select>
              </div>
              <Button
                disabled={create.isPending}
                onClick={async () => {
                  try {
                    await create.mutateAsync({ authType });
                    toast.success('URL created');
                  } catch (e) {
                    const err = e as { message?: string };
                    toast.error(err.message ?? 'Failed');
                  }
                }}
              >
                Create URL
              </Button>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

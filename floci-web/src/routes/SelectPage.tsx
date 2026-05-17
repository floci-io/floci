import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ChevronLeft, Play } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Input, Label } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  useSelect,
  type SelectInputFormat,
  type SelectOutputFormat,
} from '@/hooks/s3/useSelect';
import { formatBytes } from '@/lib/format';

export default function SelectPage() {
  const { bucket = '' } = useParams();
  const select = useSelect();

  const [key, setKey] = useState('');
  const [expression, setExpression] = useState(
    'SELECT * FROM S3Object LIMIT 100',
  );
  const [inputFormat, setInputFormat] = useState<SelectInputFormat>('CSV');
  const [outputFormat, setOutputFormat] = useState<SelectOutputFormat>('CSV');
  const [fileHeader, setFileHeader] = useState<'USE' | 'IGNORE' | 'NONE'>('USE');
  const [jsonType, setJsonType] = useState<'LINES' | 'DOCUMENT'>('LINES');

  async function run() {
    try {
      await select.mutateAsync({
        bucket,
        key,
        expression,
        inputFormat,
        outputFormat,
        csvInput:
          inputFormat === 'CSV'
            ? { FileHeaderInfo: fileHeader }
            : undefined,
        jsonInput:
          inputFormat === 'JSON' ? { Type: jsonType } : undefined,
      });
    } catch (e) {
      const err = e as { message?: string };
      toast.error(err.message ?? 'Select failed');
    }
  }

  const isParquetWarning =
    select.error &&
    inputFormat === 'Parquet' &&
    /duck|parquet/i.test((select.error as Error).message ?? '');

  return (
    <div className="space-y-4">
      <Link
        to={`/s3/b/${encodeURIComponent(bucket)}`}
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> {bucket}
      </Link>
      <h1 className="text-2xl font-semibold">S3 Select</h1>
      <p className="text-sm text-zinc-500">
        Run SQL against a single object. CSV and JSON use Floci's Java
        evaluator when the <code>floci-duck</code> sidecar is unavailable;
        Parquet always requires <code>floci-duck</code>.
      </p>

      <Card>
        <CardHeader>
          <CardTitle>Query</CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          <div>
            <Label>Object key</Label>
            <Input
              value={key}
              onChange={(e) => setKey(e.target.value)}
              placeholder="path/to/data.csv"
            />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label>Input format</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={inputFormat}
                onChange={(e) =>
                  setInputFormat(e.target.value as SelectInputFormat)
                }
              >
                <option value="CSV">CSV</option>
                <option value="JSON">JSON</option>
                <option value="Parquet">Parquet</option>
              </select>
            </div>
            <div>
              <Label>Output format</Label>
              <select
                className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                value={outputFormat}
                onChange={(e) =>
                  setOutputFormat(e.target.value as SelectOutputFormat)
                }
              >
                <option value="CSV">CSV</option>
                <option value="JSON">JSON</option>
              </select>
            </div>
            {inputFormat === 'CSV' && (
              <div>
                <Label>FileHeaderInfo</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={fileHeader}
                  onChange={(e) =>
                    setFileHeader(e.target.value as 'USE' | 'IGNORE' | 'NONE')
                  }
                >
                  <option value="USE">USE</option>
                  <option value="IGNORE">IGNORE</option>
                  <option value="NONE">NONE</option>
                </select>
              </div>
            )}
            {inputFormat === 'JSON' && (
              <div>
                <Label>JSON type</Label>
                <select
                  className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
                  value={jsonType}
                  onChange={(e) =>
                    setJsonType(e.target.value as 'LINES' | 'DOCUMENT')
                  }
                >
                  <option value="LINES">LINES</option>
                  <option value="DOCUMENT">DOCUMENT</option>
                </select>
              </div>
            )}
          </div>
          <div>
            <Label>SQL</Label>
            <textarea
              className="flex w-full rounded-md border border-zinc-300 bg-white px-3 py-2 text-sm font-mono min-h-[120px]"
              value={expression}
              onChange={(e) => setExpression(e.target.value)}
            />
          </div>
          <Button onClick={run} disabled={!key || select.isPending}>
            <Play className="size-4" />{' '}
            {select.isPending ? 'Running…' : 'Run'}
          </Button>
        </CardContent>
      </Card>

      {isParquetWarning && (
        <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
          Parquet decoding requires the <code>floci-duck</code> sidecar. Start
          it (or run any Athena query first to trigger lazy startup) and try
          again.
        </div>
      )}

      {select.data && (
        <Card>
          <CardHeader>
            <CardTitle>Result</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <p className="text-xs text-zinc-500">
              Bytes scanned: {formatBytes(select.data.bytesScanned)} ·
              returned: {formatBytes(select.data.bytesReturned)}
            </p>
            <pre className="text-xs font-mono bg-zinc-50 border border-zinc-200 rounded-md p-3 overflow-auto max-h-[480px]">
              {select.data.text || '(empty)'}
            </pre>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

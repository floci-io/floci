import { useRef, useState } from 'react';
import { Upload as UploadIcon } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from './ui/button';
import { useUpload } from '@/hooks/s3/useUpload';
import { formatBytes } from '@/lib/format';

interface Props {
  bucket: string;
  prefix: string;
}

export function Uploader({ bucket, prefix }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const { mutateAsync, progress, isPending } = useUpload(bucket, prefix);

  async function handleFiles(files: FileList | File[]) {
    const arr = Array.from(files);
    for (const file of arr) {
      const key = (prefix || '') + file.name;
      try {
        await mutateAsync({ key, file });
        toast.success(`Uploaded ${key}`);
      } catch (e) {
        const err = e as { message?: string };
        toast.error(`Upload failed: ${err.message ?? 'unknown error'}`);
      }
    }
  }

  return (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragging(false);
        if (e.dataTransfer.files.length > 0) {
          void handleFiles(e.dataTransfer.files);
        }
      }}
      className={`rounded-md border-2 border-dashed p-6 text-center transition-colors ${
        dragging ? 'border-zinc-900 bg-zinc-50' : 'border-zinc-300'
      }`}
    >
      <UploadIcon className="mx-auto size-6 text-zinc-400" />
      <p className="mt-2 text-sm text-zinc-600">
        Drop files here, or
      </p>
      <Button
        variant="outline"
        size="sm"
        className="mt-2"
        onClick={() => inputRef.current?.click()}
        disabled={isPending}
      >
        Choose file
      </Button>
      <input
        ref={inputRef}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => {
          if (e.target.files) {
            void handleFiles(e.target.files);
            e.target.value = '';
          }
        }}
      />
      {Object.values(progress).length > 0 && (
        <div className="mt-4 space-y-2 text-left">
          {Object.values(progress).map((p) => {
            const pct = p.total > 0 ? (p.loaded / p.total) * 100 : 0;
            return (
              <div key={p.key} className="text-xs">
                <div className="flex justify-between">
                  <span className="truncate">{p.key}</span>
                  <span>
                    {formatBytes(p.loaded)} / {formatBytes(p.total)}
                  </span>
                </div>
                <div className="mt-1 h-1.5 w-full bg-zinc-200 rounded">
                  <div
                    className="h-1.5 bg-zinc-900 rounded transition-all"
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
            );
          })}
        </div>
      )}
      <p className="mt-3 text-xs text-zinc-400">
        Files &gt; 5 MB upload via multipart automatically.
      </p>
    </div>
  );
}

import { cn } from '@/lib/cn';

const COLOR: Record<string, string> = {
  available: 'bg-green-100 text-green-800',
  creating: 'bg-amber-100 text-amber-800',
  deleting: 'bg-zinc-100 text-zinc-600',
  rebooting: 'bg-amber-100 text-amber-800',
  modifying: 'bg-amber-100 text-amber-800',
  failed: 'bg-red-100 text-red-800',
  stopped: 'bg-zinc-100 text-zinc-600',
};

export function StatusPill({ status }: { status?: string }) {
  const s = (status ?? '').toLowerCase();
  const cls = COLOR[s] ?? 'bg-zinc-100 text-zinc-700';
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
        cls,
      )}
    >
      {status ?? '—'}
    </span>
  );
}

import { cn } from '@/lib/cn';

const COLOR: Record<string, string> = {
  pending: 'bg-amber-100 text-amber-800',
  running: 'bg-green-100 text-green-800',
  stopping: 'bg-amber-100 text-amber-800',
  stopped: 'bg-zinc-100 text-zinc-700',
  'shutting-down': 'bg-amber-100 text-amber-800',
  terminated: 'bg-red-100 text-red-800',
  available: 'bg-green-100 text-green-800',
  attached: 'bg-green-100 text-green-800',
  attaching: 'bg-amber-100 text-amber-800',
  detaching: 'bg-amber-100 text-amber-800',
  'in-use': 'bg-green-100 text-green-800',
};

export function StatePill({ state }: { state?: string }) {
  const s = (state ?? '').toLowerCase();
  const cls = COLOR[s] ?? 'bg-zinc-100 text-zinc-700';
  return (
    <span
      className={cn(
        'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
        cls,
      )}
    >
      {state ?? '—'}
    </span>
  );
}

export function tagsValue(
  tags: Array<{ Key?: string; Value?: string }> | undefined,
  key: string,
): string | undefined {
  return tags?.find((t) => t.Key === key)?.Value;
}

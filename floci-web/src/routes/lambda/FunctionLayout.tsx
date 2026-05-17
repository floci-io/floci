import { Link, NavLink, Outlet, useParams } from 'react-router-dom';
import { ChevronLeft } from 'lucide-react';
import { cn } from '@/lib/cn';
import { useFunction } from '@/hooks/lambda/useFunctions';

const tabs = [
  { to: 'configuration', label: 'Configuration' },
  { to: 'code', label: 'Code' },
  { to: 'invoke', label: 'Invoke' },
  { to: 'versions', label: 'Versions & Aliases' },
  { to: 'event-sources', label: 'Event sources' },
  { to: 'permissions', label: 'Permissions' },
  { to: 'url', label: 'Function URL' },
  { to: 'concurrency', label: 'Concurrency' },
  { to: 'tags', label: 'Tags' },
];

export default function FunctionLayout() {
  const { name = '' } = useParams();
  const { data } = useFunction(name);
  const cfg = data?.Configuration;

  return (
    <div className="space-y-4">
      <Link
        to="/lambda"
        className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
      >
        <ChevronLeft className="size-4" /> Functions
      </Link>

      <div>
        <h1 className="text-2xl font-semibold">{name}</h1>
        {cfg && (
          <p className="text-xs text-zinc-500 mt-1 flex items-center gap-2 flex-wrap">
            <span>{cfg.Runtime}</span>
            <span>·</span>
            <span className="font-mono">{cfg.Handler}</span>
            <span>·</span>
            <span>{cfg.MemorySize} MB</span>
            <span>·</span>
            <span>{cfg.Timeout}s timeout</span>
            <span>·</span>
            <span>{(cfg.Architectures ?? []).join(', ')}</span>
            <span>·</span>
            <span>state: {cfg.State ?? 'unknown'}</span>
          </p>
        )}
      </div>

      <div className="border-b border-zinc-200">
        <nav className="-mb-px flex gap-1 overflow-x-auto">
          {tabs.map((t) => (
            <NavLink
              key={t.to}
              to={t.to}
              className={({ isActive }) =>
                cn(
                  'whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium',
                  isActive
                    ? 'border-zinc-900 text-zinc-900'
                    : 'border-transparent text-zinc-500 hover:text-zinc-700',
                )
              }
            >
              {t.label}
            </NavLink>
          ))}
        </nav>
      </div>

      <Outlet />
    </div>
  );
}

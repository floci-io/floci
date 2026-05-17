import { NavLink, Outlet, useParams, Link } from 'react-router-dom';
import { ChevronLeft, Search } from 'lucide-react';
import { useBucketLocation } from '@/hooks/s3/useBuckets';
import { cn } from '@/lib/cn';
import { Button } from '@/components/ui/button';

const tabs = [
  { to: 'objects', label: 'Objects' },
  { to: 'versions', label: 'Versions' },
  { to: 'multipart', label: 'Multipart' },
  { to: 'tags', label: 'Tags' },
  { to: 'lifecycle', label: 'Lifecycle' },
  { to: 'cors', label: 'CORS' },
  { to: 'policy', label: 'Policy' },
  { to: 'lock', label: 'Object Lock' },
  { to: 'encryption', label: 'Encryption' },
  { to: 'public-access', label: 'Public Access' },
  { to: 'notifications', label: 'Notifications' },
];

export default function BucketLayout() {
  const { bucket = '' } = useParams();
  const { data: region } = useBucketLocation(bucket);

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <Link
            to="/s3"
            className="inline-flex items-center text-sm text-zinc-500 hover:text-zinc-900"
          >
            <ChevronLeft className="size-4" /> Buckets
          </Link>
          <h1 className="text-2xl font-semibold mt-1">{bucket}</h1>
          <p className="text-xs text-zinc-500">
            Region: <code>{region ?? '—'}</code>
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" asChild>
            <Link to={`/s3/b/${encodeURIComponent(bucket)}/select`}>
              <Search className="size-4" /> S3 Select
            </Link>
          </Button>
        </div>
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

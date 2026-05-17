import { NavLink, Outlet } from 'react-router-dom';
import { cn } from '@/lib/cn';

const tabs = [
  { to: 'instances', label: 'DB Instances' },
  { to: 'clusters', label: 'DB Clusters' },
  { to: 'parameter-groups', label: 'Parameter Groups' },
];

export default function RdsLayout() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">RDS</h1>
        <p className="text-sm text-zinc-500">
          Manage PostgreSQL, MySQL, and MariaDB instances backed by real Docker
          containers. Connect via the host port shown for each instance.
        </p>
      </div>
      <div className="border-b border-zinc-200">
        <nav className="-mb-px flex gap-1">
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

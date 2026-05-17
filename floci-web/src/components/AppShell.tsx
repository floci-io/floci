import { NavLink, Outlet, Link } from 'react-router-dom';
import {
  CalendarClock,
  Cloud,
  Database,
  FolderArchive,
  Server,
  Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '@/lib/cn';

interface ServiceLink {
  to: string;
  label: string;
  icon: LucideIcon;
  enabled: boolean;
}

const services: ServiceLink[] = [
  { to: '/s3', label: 'S3', icon: FolderArchive, enabled: true },
  { to: '/rds', label: 'RDS', icon: Database, enabled: true },
  { to: '/lambda', label: 'Lambda', icon: Zap, enabled: true },
  { to: '/ec2', label: 'EC2', icon: Server, enabled: true },
  { to: '/scheduler', label: 'Scheduler', icon: CalendarClock, enabled: true },
];

export default function AppShell() {
  return (
    <div className="min-h-full grid grid-cols-[16rem_1fr]">
      <aside className="border-r border-border bg-white sticky top-0 self-start h-screen flex flex-col">
        <div className="px-5 py-4 border-b border-border">
          <Link to="/" className="flex items-center gap-2 font-semibold">
            <Cloud className="size-5" />
            <span>Floci Console</span>
          </Link>
          <p className="text-[11px] text-muted-foreground mt-1">
            {window.location.origin}/_aws → :4566
          </p>
        </div>
        <nav className="p-3 space-y-1 flex-1 overflow-y-auto">
          <p className="px-2 pt-2 pb-1 text-[11px] uppercase tracking-wider text-muted-foreground">
            Services
          </p>
          {services.map((s) => {
            const Icon = s.icon;
            return (
              <NavLink
                key={s.to}
                to={s.to}
                className={({ isActive }) =>
                  cn(
                    'flex items-center gap-3 px-2 py-1.5 rounded-md text-sm transition-colors',
                    s.enabled
                      ? isActive
                        ? 'bg-zinc-900 text-white'
                        : 'text-zinc-700 hover:bg-zinc-100'
                      : 'text-zinc-400 cursor-not-allowed pointer-events-none',
                  )
                }
              >
                <Icon className="size-4" />
                <span>{s.label}</span>
              </NavLink>
            );
          })}
        </nav>
        <div className="px-5 py-3 border-t border-border text-[11px] text-muted-foreground">
          Local AWS emulator dev console
        </div>
      </aside>
      <main className="min-w-0 p-6 max-w-6xl">
        <Outlet />
      </main>
    </div>
  );
}

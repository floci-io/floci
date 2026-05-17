import { NavLink, Outlet } from 'react-router-dom';
import { cn } from '@/lib/cn';

const tabs = [
  { to: 'schedules', label: 'Schedules' },
  { to: 'groups', label: 'Schedule Groups' },
];

export default function SchedulerLayout() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">EventBridge Scheduler</h1>
        <p className="text-sm text-zinc-500">
          One-time (<code>at</code>), recurring (<code>rate</code>), and{' '}
          <code>cron</code> schedules. Floci's dispatcher fires SQS, Lambda,
          SNS, and EventBridge <code>PutEvents</code> targets on time when{' '}
          <code>invocation-enabled</code> is on (default).
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

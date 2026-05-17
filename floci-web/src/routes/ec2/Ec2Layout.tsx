import { NavLink, Outlet } from 'react-router-dom';
import { cn } from '@/lib/cn';

const tabs = [
  { to: 'instances', label: 'Instances' },
  { to: 'amis', label: 'AMIs' },
  { to: 'key-pairs', label: 'Key Pairs' },
  { to: 'security-groups', label: 'Security Groups' },
  { to: 'vpcs', label: 'VPCs' },
  { to: 'subnets', label: 'Subnets' },
  { to: 'internet-gateways', label: 'Internet Gateways' },
  { to: 'route-tables', label: 'Route Tables' },
  { to: 'elastic-ips', label: 'Elastic IPs' },
  { to: 'volumes', label: 'Volumes' },
];

export default function Ec2Layout() {
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">EC2</h1>
        <p className="text-sm text-zinc-500">
          Floci launches real Docker containers for each instance. IMDS
          (v1+v2) is served on host port 9169. AMI IDs resolve to public
          Docker images; unrecognised IDs fall back to Amazon Linux 2023.
        </p>
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

import { useEffect, useMemo, useState } from 'react';
import { Input, Label } from '@/components/ui/input';

export type ExpressionMode = 'at' | 'rate' | 'cron';
export type RateUnit = 'minutes' | 'hours' | 'days' | 'weeks';

interface Parsed {
  mode: ExpressionMode;
  atValue?: string;
  rateValue?: number;
  rateUnit?: RateUnit;
  cronValue?: string;
}

function parseExpression(expr: string): Parsed {
  if (expr.startsWith('at(') && expr.endsWith(')')) {
    return { mode: 'at', atValue: expr.slice(3, -1) };
  }
  if (expr.startsWith('rate(') && expr.endsWith(')')) {
    const inside = expr.slice(5, -1);
    const [n, unit] = inside.split(/\s+/);
    return {
      mode: 'rate',
      rateValue: Number(n) || 1,
      rateUnit: (unit as RateUnit) ?? 'minutes',
    };
  }
  if (expr.startsWith('cron(') && expr.endsWith(')')) {
    return { mode: 'cron', cronValue: expr.slice(5, -1) };
  }
  return { mode: 'rate', rateValue: 1, rateUnit: 'hours' };
}

function buildExpression(p: Parsed): string {
  if (p.mode === 'at') return `at(${p.atValue ?? ''})`;
  if (p.mode === 'rate')
    return `rate(${p.rateValue ?? 1} ${p.rateUnit ?? 'minutes'})`;
  return `cron(${p.cronValue ?? '0 12 * * ? *'})`;
}

interface Props {
  value: string;
  onChange: (v: string) => void;
}

export function ExpressionBuilder({ value, onChange }: Props) {
  const initial = useMemo(() => parseExpression(value), []);
  const [mode, setMode] = useState<ExpressionMode>(initial.mode);
  const [atValue, setAtValue] = useState(initial.atValue ?? '');
  const [rateValue, setRateValue] = useState(initial.rateValue ?? 1);
  const [rateUnit, setRateUnit] = useState<RateUnit>(
    initial.rateUnit ?? 'minutes',
  );
  const [cronValue, setCronValue] = useState(
    initial.cronValue ?? '0 12 * * ? *',
  );

  useEffect(() => {
    onChange(
      buildExpression({
        mode,
        atValue,
        rateValue,
        rateUnit,
        cronValue,
      }),
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, atValue, rateValue, rateUnit, cronValue]);

  return (
    <div className="space-y-3 rounded-md border border-zinc-200 p-3">
      <div className="flex gap-4 text-sm">
        {(['rate', 'cron', 'at'] as ExpressionMode[]).map((m) => (
          <label key={m} className="inline-flex items-center gap-1">
            <input
              type="radio"
              checked={mode === m}
              onChange={() => setMode(m)}
            />
            <span className="font-mono">{m}</span>
          </label>
        ))}
      </div>
      {mode === 'at' && (
        <div>
          <Label>Fires once at (UTC, ISO 8601 — no Z suffix)</Label>
          <Input
            value={atValue}
            onChange={(e) => setAtValue(e.target.value)}
            placeholder="2026-12-31T15:00:00"
          />
          <p className="text-xs text-zinc-500 mt-1">
            Honors <code>ScheduleExpressionTimezone</code>. Schedules with{' '}
            <code>ActionAfterCompletion=DELETE</code> are removed after firing.
          </p>
        </div>
      )}
      {mode === 'rate' && (
        <div className="grid grid-cols-2 gap-3">
          <div>
            <Label>Value</Label>
            <Input
              type="number"
              min={1}
              value={rateValue}
              onChange={(e) => setRateValue(Number(e.target.value) || 1)}
            />
          </div>
          <div>
            <Label>Unit</Label>
            <select
              className="flex h-9 w-full rounded-md border border-zinc-300 bg-white px-3 text-sm"
              value={rateUnit}
              onChange={(e) => setRateUnit(e.target.value as RateUnit)}
            >
              <option value="minutes">minutes</option>
              <option value="hours">hours</option>
              <option value="days">days</option>
              <option value="weeks">weeks</option>
            </select>
          </div>
        </div>
      )}
      {mode === 'cron' && (
        <div>
          <Label>Cron expression (AWS 6-field)</Label>
          <Input
            className="font-mono text-xs"
            value={cronValue}
            onChange={(e) => setCronValue(e.target.value)}
            placeholder="minute hour day-of-month month day-of-week year"
          />
          <p className="text-xs text-zinc-500 mt-1">
            Example: <code>0 12 * * ? *</code> fires every day at 12:00 UTC.
          </p>
        </div>
      )}
      <div className="text-xs">
        <span className="text-zinc-500">Final expression: </span>
        <code className="font-mono">{buildExpression({ mode, atValue, rateValue, rateUnit, cronValue })}</code>
      </div>
    </div>
  );
}

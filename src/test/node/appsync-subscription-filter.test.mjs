import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const script = path.join(root, 'src/main/resources/appsync/appsync-js-runtime.mjs');
const filter = (fieldName, operator, value) => ({ fieldName, operator, value });
const group = (...filters) => ({ filters });
const output = (...groups) => ({ filterGroup: groups });

let server;
let runtimeDir;
let url;
let stderr = '';

async function unusedPort() {
  const listener = net.createServer();
  await new Promise((resolve) => listener.listen(0, '127.0.0.1', resolve));
  const port = listener.address().port;
  await new Promise((resolve) => listener.close(resolve));
  return port;
}

before(async () => {
  await mkdir(path.join(root, 'target'), { recursive: true });
  runtimeDir = await mkdtemp(path.join(root, 'target/appsync-filter-'));
  for (let launch = 0; launch < 3; launch++) {
    const port = await unusedPort();
    stderr = '';
    server = spawn(process.execPath, [script], {
      env: { ...process.env, FLOCI_JS_RUNTIME_PORT: String(port), FLOCI_JS_RUNTIME_DIR: runtimeDir },
      stdio: ['ignore', 'ignore', 'pipe']
    });
    server.stderr.on('data', (chunk) => { stderr += chunk; });
    url = `http://127.0.0.1:${port}`;
    for (let attempt = 0; attempt < 100; attempt++) {
      if (server.exitCode !== null) {
        break;
      }
      try {
        const health = await fetch(`${url}/health`);
        if (health.ok) {
          return;
        }
      } catch (expected) {
        // Health polling tolerates connection refusal until the runtime starts.
      }
      await new Promise((resolve) => setTimeout(resolve, 50));
    }
    if (!stderr.includes('EADDRINUSE')) {
      break;
    }
  }
  throw new Error(`resolver runtime did not start: ${stderr}`);
});

after(async () => {
  if (server && server.exitCode === null) {
    server.kill();
    await new Promise((resolve) => server.once('exit', resolve));
  }
  if (runtimeDir) {
    await rm(runtimeDir, { recursive: true, force: true });
  }
});

async function evaluate(code, args = {}, enforceSubset) {
  const response = await fetch(`${url}/evaluate`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, handler: 'request', context: { arguments: args }, enforceSubset })
  });
  assert.equal(response.status, 200);
  return response.json();
}

const resolver = `
import { util } from '@aws-appsync/utils';
export function request(ctx) {
  return util.transform.toSubscriptionFilter(ctx.args.filter, ctx.args.ignored, ctx.args.rules);
}`;

test('an existing util helper still runs in the resolver runtime', async () => {
  const result = await evaluate(`
    import { util } from '@aws-appsync/utils';
    export function request() { return util.str.toUpper('ok'); }`);
  assert.equal(result.ok, true, JSON.stringify(result.error));
  assert.equal(result.result, 'OK');
});

const cases = [
  {
    name: 'a single predicate becomes a filter group',
    args: { filter: { status: { eq: 'open' } } },
    expected: output(group(filter('status', 'eq', 'open')))
  },
  {
    name: 'different fields are ANDed in one group',
    args: { filter: { status: { eq: 'open' }, priority: { ge: 3 } } },
    expected: output(group(filter('status', 'eq', 'open'), filter('priority', 'ge', 3)))
  },
  {
    name: 'operators on the same field produce OR groups',
    args: { filter: { score: { lte: 50, gte: 20 } } },
    expected: output(group(filter('score', 'lte', 50)), group(filter('score', 'gte', 20)))
  },
  {
    name: 'nested AND and OR distribute over the base predicates',
    args: { filter: {
      score: { lte: 50, gte: 20 },
      and: [{ title: { ne: 'Book1' } }, { or: [
        { author: { eq: 'Admin' } }, { published: { eq: false } }
      ] }]
    } },
    expected: output(
      group(filter('score', 'lte', 50), filter('title', 'ne', 'Book1'),
        filter('author', 'eq', 'Admin')),
      group(filter('score', 'lte', 50), filter('title', 'ne', 'Book1'),
        filter('published', 'eq', false)),
      group(filter('score', 'gte', 20), filter('title', 'ne', 'Book1'),
        filter('author', 'eq', 'Admin')),
      group(filter('score', 'gte', 20), filter('title', 'ne', 'Book1'),
        filter('published', 'eq', false))
    )
  },
  {
    name: 'ignored fields are omitted throughout nested filters',
    args: { filter: {
      score: { lte: 50, gte: 20 },
      and: [{ title: { ne: 'Book1' } }, { or: [
        { author: { eq: 'Admin' } }, { published: { eq: false } }
      ] }]
    }, ignored: ['score', 'author'] },
    expected: output(group(filter('title', 'ne', 'Book1'), filter('published', 'eq', false)))
  },
  {
    name: 'strict rules each combine with the base filter as OR branches',
    args: { filter: { status: { eq: 'open' } },
      rules: { priority: { ge: 7 }, owner: { eq: 'A' } } },
    expected: output(
      group(filter('status', 'eq', 'open'), filter('priority', 'ge', 7)),
      group(filter('status', 'eq', 'open'), filter('owner', 'eq', 'A'))
    )
  },
  {
    name: 'strict rules expand each base branch and bypass ignored fields',
    args: { filter: { score: { lte: 50, gte: 20 }, owner: { eq: 'ignored' } },
      ignored: ['owner'], rules: { owner: { eq: 'A' }, priority: { ge: 7 } } },
    expected: output(
      group(filter('score', 'lte', 50), filter('owner', 'eq', 'A')),
      group(filter('score', 'lte', 50), filter('priority', 'ge', 7)),
      group(filter('score', 'gte', 20), filter('owner', 'eq', 'A')),
      group(filter('score', 'gte', 20), filter('priority', 'ge', 7))
    )
  },
  {
    name: 'empty filter has no groups',
    args: { filter: {} },
    expected: output()
  },
  {
    name: 'null filter remains null',
    args: { filter: null },
    expected: null
  }
];

for (const { name, args, expected } of cases) {
  test(name, async () => {
    const result = await evaluate(resolver, args);
    assert.equal(result.ok, true, JSON.stringify(result.error));
    assert.deepEqual(result.result, expected);
  });
}

test('a bounded number of subscription filter groups is accepted', async () => {
  const fields = Object.fromEntries(Array.from({ length: 9 }, (_, i) =>
    [`field${i}`, { eq: i, ne: i }]));
  const result = await evaluate(resolver, { filter: fields });
  assert.equal(result.ok, true, JSON.stringify(result.error));
  assert.equal(result.result.filterGroup.length, 512);
});

test('subscription filter expansion rejects excessive groups before allocating them', async () => {
  const fields = Object.fromEntries(Array.from({ length: 10 }, (_, i) =>
    [`field${i}`, { eq: i, ne: i }]));
  const result = await evaluate(resolver, { filter: fields });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 1000 groups/i);
});

test('thirty double-operator fields are rejected without expanding their billion groups', async () => {
  const fields = Object.fromEntries(Array.from({ length: 30 }, (_, i) =>
    [`field${i}`, { eq: i, ne: i }]));
  const result = await evaluate(resolver, { filter: fields });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 1000 groups/i);
});

test('subscription filter OR branches share the same group limit', async () => {
  const branches = Array.from({ length: 1001 }, (_, i) => ({ field: { eq: i } }));
  const withinBudget = await evaluate(resolver, { filter: { or: branches.slice(0, 1000) } });
  assert.equal(withinBudget.ok, true, JSON.stringify(withinBudget.error));
  assert.equal(withinBudget.result.filterGroup.length, 1000);
  const result = await evaluate(resolver, { filter: { or: branches } });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 1000 groups/i);
});

test('strict filter rules share the same group limit', async () => {
  const rules = Object.fromEntries(Array.from({ length: 1001 }, (_, i) =>
    [`field${i}`, { eq: i }]));
  const result = await evaluate(resolver, { filter: {}, rules });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 1000 groups/i);
});

test('subscription filter expansion also limits total emitted filter entries', async () => {
  const branches = Array.from({ length: 1000 }, (_, i) => ({ branch: { eq: i } }));
  const linearFields = Object.fromEntries(Array.from({ length: 9 }, (_, i) =>
    [`field${i}`, { eq: i }]));
  const withinBudget = await evaluate(resolver, {
    filter: { or: branches, ...linearFields }
  });
  assert.equal(withinBudget.ok, true, JSON.stringify(withinBudget.error));
  assert.equal(withinBudget.result.filterGroup.length, 1000);
  assert.equal(withinBudget.result.filterGroup[0].filters.length, 10);

  const result = await evaluate(resolver, {
    filter: { or: branches, ...linearFields, overflow: { eq: true } }
  });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 10000 filter entries/i);
});

test('repeated values cannot expand into an oversized subscription filter response', async () => {
  const operators = Object.fromEntries(Array.from({ length: 1000 }, (_, i) =>
    [`operator${i}`, i]));
  const single = await evaluate(resolver, {
    filter: { payload: { eq: 'x'.repeat(2 * 1024 * 1024) } }
  });
  assert.equal(single.ok, true, JSON.stringify(single.error));
  const result = await evaluate(resolver, {
    filter: { payload: { eq: 'x'.repeat(6144) }, choice: operators }
  });
  assert.equal(result.ok, false);
  assert.equal(result.error.type, 'RangeError');
  assert.match(result.error.message, /subscription filter exceeds 5242880 output bytes/i);
});

test('output preflight stops serializing predicates as soon as the budget is exceeded', async () => {
  const result = await evaluate(`
    import { util } from '@aws-appsync/utils';
    export function request() {
      let calls = 0;
      const value = { toJSON() { calls++; return 'x'.repeat(2 * 1024 * 1024); } };
      try {
        util.transform.toSubscriptionFilter({ choice: {
          op0: value, op1: value, op2: value, op3: value
        } });
      } catch (error) {
        return { calls, type: error.name };
      }
    }`, {}, false);
  assert.equal(result.ok, true, JSON.stringify(result.error));
  assert.deepEqual(result.result, { calls: 3, type: 'RangeError' });
});

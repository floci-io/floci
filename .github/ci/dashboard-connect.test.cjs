const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { setImmediate } = require('node:timers/promises');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

const html = readFileSync('src/main/resources/ui/index.html', 'utf8');
const script = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].at(-1)[1];

function openDashboard() {
  const requests = new Map();
  const elements = new Map(
    [...html.matchAll(/<dd id="([^"]+)">([^<]*)<\/dd>/g)]
      .map(([, id, textContent]) => [id, { textContent, addEventListener() {} }]),
  );
  const element = (id) => {
    if (!elements.has(id)) elements.set(id, { addEventListener() {}, setAttribute() {} });
    return elements.get(id);
  };
  let selectBoto3;
  const boto3 = {
    dataset: { snippet: 'boto3' },
    addEventListener(_, listener) { selectBoto3 = listener; },
    setAttribute() {},
  };
  runInNewContext(script, {
    document: {
      documentElement: { dataset: {} },
      getElementById: element,
      querySelectorAll: (selector) => selector === '.tab' ? [boto3] : [],
      addEventListener() {},
    },
    location: { origin: 'http://localhost:4566' },
    localStorage: { getItem: () => null },
    window: {},
    fetch: (path) => new Promise((resolve, reject) => requests.set(path, { resolve, reject })),
    setInterval() {},
  });
  return {
    element,
    selectBoto3,
    async finishInfo(info) {
      const request = requests.get('/_floci/info');
      if (info) request.resolve({ ok: true, json: async () => info });
      else request.reject(new Error('offline'));
      // Drain the manifest callbacks while the other dashboard requests are still pending.
      await setImmediate();
    },
  };
}

test('Connect shows configured values before the other dashboard requests finish', async () => {
  const { element, selectBoto3, finishInfo } = openDashboard();
  selectBoto3();
  await finishInfo({ defaults: { region: 'ap-southeast-1', account_id: '012345678901' } });
  assert.equal(element('f-region').textContent, 'ap-southeast-1');
  assert.equal(element('f-account').textContent, '012345678901');
  assert.match(element('snippet').textContent, /region_name="ap-southeast-1"/);
  element('f-region').textContent += ' (configured)';
  selectBoto3();
  assert.match(element('snippet').textContent, /region_name="ap-southeast-1"/);
});

test('Connect keeps the built-in values when info is missing or unavailable', async () => {
  for (const info of [{}, null]) {
    const { element, finishInfo } = openDashboard();
    await finishInfo(info);
    assert.equal(element('f-region').textContent, 'us-east-1');
    assert.equal(element('f-account').textContent, '000000000000');
    assert.match(element('snippet').textContent, /AWS_DEFAULT_REGION=us-east-1/);
  }
});

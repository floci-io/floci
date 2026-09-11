// Floci's APPSYNC_JS resolver runtime, run inside a Node sidecar container.
//
// Floci ships as a Mandrel native executable, which carries no Truffle languages, so resolver
// JavaScript cannot be evaluated in-process. This server evaluates it in real Node instead, which
// also means the resolver code AppSync would run is the code that runs here (ES modules,
// `import` statements and all) rather than a rewritten approximation.
//
// Protocol (JSON over HTTP, one request per handler call):
//   GET  /health    -> {"ok":true,"runtime":"<node version>"}
//   POST /evaluate  <- {"code":"<module source>","handler":"request"|"response","context":{...}}
//                   -> {"ok":true,"result":<json>,"stash":{...},"earlyReturn":<bool>,"errors":[...]}
//                   -> {"ok":false,"error":{"message","type","data","errorInfo","stack"}}
//
// Resolver modules are written to disk under a content hash and imported, so Node's own module
// cache compiles each distinct resolver once. `@aws-appsync/utils` resolves to the shim written
// below: a bare specifier inside a module can only resolve through node_modules, so the resolver
// directory sits beneath one.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pathToFileURL } from 'node:url';

const PORT = Number(process.env.FLOCI_JS_RUNTIME_PORT || 4600);
const ROOT = process.env.FLOCI_JS_RUNTIME_DIR || '/tmp/floci-appsync';
const RESOLVER_DIR = path.join(ROOT, 'resolvers');
const UTILS_DIR = path.join(ROOT, 'node_modules', '@aws-appsync', 'utils');

// ── @aws-appsync/utils ───────────────────────────────────────────────────────
// Deliberately a shim rather than the published package: pulling from npm would make a resolver
// call depend on registry access, and Floci has to work offline. Everything AppSync's JS resolvers
// reach for in practice is here; anything missing throws by name rather than returning undefined,
// so a gap shows up as a clear error instead of a null field.

// NOTE: UTILS_INDEX and UTILS_RDS below are template literals, so a backtick or a ${ inside them
// - in code *or in a comment* - ends the string and the sidecar fails to boot with a SyntaxError.
// Escape them (\` and \${) or write around them.
const UTILS_INDEX = `
import { randomUUID } from 'node:crypto';
const ERROR_MARKER = Symbol.for('floci.appsync.error');
const RETURN_MARKER = Symbol.for('floci.appsync.earlyReturn');

export const appendedErrors = [];
export function resetAppendedErrors() { appendedErrors.length = 0; }

class TemplateError extends Error {
  constructor(message, type, data, errorInfo) {
    super(message);
    this[ERROR_MARKER] = true;
    this.errorType = type ?? null;
    this.errorData = data ?? null;
    this.errorInfo = errorInfo ?? null;
  }
}

class EarlyReturn extends Error {
  constructor(value) {
    super('early return');
    this[RETURN_MARKER] = true;
    this.value = value ?? null;
  }
}

const pad = (n, width = 2) => String(n).padStart(width, '0');

const toDynamoDB = (value) => {
  if (value === null || value === undefined) return { NULL: true };
  if (typeof value === 'string') return { S: value };
  if (typeof value === 'number') return { N: String(value) };
  if (typeof value === 'boolean') return { BOOL: value };
  if (Array.isArray(value)) return { L: value.map(toDynamoDB) };
  if (typeof value === 'object') {
    const m = {};
    for (const [k, v] of Object.entries(value)) m[k] = toDynamoDB(v);
    return { M: m };
  }
  return { S: String(value) };
};

const fromDynamoDB = (value) => {
  if (value === null || value === undefined) return null;
  if ('NULL' in value) return null;
  if ('S' in value) return value.S;
  if ('N' in value) return Number(value.N);
  if ('BOOL' in value) return value.BOOL;
  if ('L' in value) return value.L.map(fromDynamoDB);
  if ('M' in value) {
    const out = {};
    for (const [k, v] of Object.entries(value.M)) out[k] = fromDynamoDB(v);
    return out;
  }
  if ('SS' in value) return value.SS;
  if ('NS' in value) return value.NS.map(Number);
  return null;
};

const unsupported = (name) => () => {
  throw new TemplateError(\`util.\${name} is not implemented by Floci's AppSync JS runtime\`,
    'FlociUnsupported');
};

export const util = {
  error(message, type, data, errorInfo) { throw new TemplateError(message, type, data, errorInfo); },
  appendError(message, type, data, errorInfo) {
    // The member is named type, matching what describeError() reports for util.error. Naming it
    // errorType here instead silently dropped the type off every appended error on its way to Java.
    appendedErrors.push({ message, type: type ?? null, data: data ?? null, errorInfo: errorInfo ?? null });
  },
  unauthorized() { throw new TemplateError('Unauthorized', 'Unauthorized'); },
  autoId() { return randomUUID(); },
  autoUlid: unsupported('autoUlid'),
  autoKsuid: unsupported('autoKsuid'),
  matches(pattern, value) { return new RegExp(pattern).test(String(value)); },
  authType() { return globalThis.__floci_authType ?? null; },
  escapeJavaScript(v) { return String(v).replace(/[\\\\"']/g, (m) => '\\\\' + m); },
  urlEncode(v) { return encodeURIComponent(String(v)); },
  urlDecode(v) { return decodeURIComponent(String(v)); },
  base64Encode(v) { return Buffer.from(v).toString('base64'); },
  base64Decode(v) { return Buffer.from(v, 'base64').toString('utf8'); },
  parseJson(v) { try { return typeof v === 'string' ? JSON.parse(v) : v; } catch { return null; } },
  toJson(v) { return JSON.stringify(v); },
  isNull(v) { return v === null || v === undefined; },
  isNullOrEmpty(v) { return v === null || v === undefined || v === ''; },
  isNullOrBlank(v) { return v === null || v === undefined || String(v).trim() === ''; },
  defaultIfNull(v, d) { return v === null || v === undefined ? d : v; },
  defaultIfNullOrEmpty(v, d) { return v === null || v === undefined || v === '' ? d : v; },
  isString(v) { return typeof v === 'string'; },
  isNumber(v) { return typeof v === 'number'; },
  isBoolean(v) { return typeof v === 'boolean'; },
  isList(v) { return Array.isArray(v); },
  isMap(v) { return v !== null && typeof v === 'object' && !Array.isArray(v); },
  typeOf(v) {
    if (v === null || v === undefined) return 'Null';
    if (Array.isArray(v)) return 'List';
    switch (typeof v) {
      case 'string': return 'String';
      case 'number': return 'Number';
      case 'boolean': return 'Boolean';
      default: return 'Map';
    }
  },
  time: {
    nowISO8601() { return new Date().toISOString(); },
    nowEpochSeconds() { return Math.floor(Date.now() / 1000); },
    nowEpochMilliSeconds() { return Date.now(); },
    nowFormatted(format) {
      const d = new Date();
      return String(format)
        .replace('yyyy', String(d.getUTCFullYear()))
        .replace('MM', pad(d.getUTCMonth() + 1))
        .replace('dd', pad(d.getUTCDate()))
        .replace('HH', pad(d.getUTCHours()))
        .replace('mm', pad(d.getUTCMinutes()))
        .replace('ss', pad(d.getUTCSeconds()));
    },
    epochMilliSecondsToISO8601(ms) { return new Date(Number(ms)).toISOString(); },
    epochMilliSecondsToSeconds(ms) { return Math.floor(Number(ms) / 1000); },
    epochSecondsToISO8601(s) { return new Date(Number(s) * 1000).toISOString(); },
    parseISO8601ToEpochMilliSeconds(iso) { return new Date(iso).getTime(); }
  },
  dynamodb: {
    toDynamoDB,
    fromDynamoDB,
    toString: (v) => ({ S: String(v) }),
    toStringSet: (v) => ({ SS: v.map(String) }),
    toNumber: (v) => ({ N: String(v) }),
    toNumberSet: (v) => ({ NS: v.map(String) }),
    toBinary: (v) => ({ B: v }),
    toBoolean: (v) => ({ BOOL: Boolean(v) }),
    toNull: () => ({ NULL: true }),
    toList: (v) => ({ L: v.map(toDynamoDB) }),
    toMap: (v) => toDynamoDB(v),
    toMapValues(values) {
      const out = {};
      for (const [k, v] of Object.entries(values)) out[k] = toDynamoDB(v);
      return out;
    }
  },
  transform: {
    toDynamoDBFilterExpression: unsupported('transform.toDynamoDBFilterExpression'),
    toElasticsearchQueryDSL: unsupported('transform.toElasticsearchQueryDSL')
  },
  str: {
    normalize: (v) => String(v).normalize(),
    toUpper: (v) => String(v).toUpperCase(),
    toLower: (v) => String(v).toLowerCase()
  }
};

export const runtime = {
  earlyReturn(value) { throw new EarlyReturn(value); }
};

export const extensions = {
  evictFromApiCache() { return {}; },
  setSubscriptionFilter() { return {}; },
  setSubscriptionInvalidationFilter() { return {}; },
  invalidateSubscriptions() { return {}; }
};

export default { util, runtime, extensions };
`;

// The RDS helpers. `toJsonObject` is the one every relational resolver ends with: it turns the
// rds-data ExecuteStatement response into plain row objects, keyed by column label.
const UTILS_RDS = `
const scalar = (field) => {
  if (field === null || field === undefined) return null;
  if (field.isNull) return null;
  if ('stringValue' in field) return field.stringValue;
  if ('longValue' in field) return field.longValue;
  if ('doubleValue' in field) return field.doubleValue;
  if ('booleanValue' in field) return field.booleanValue;
  if ('blobValue' in field) return field.blobValue;
  if ('arrayValue' in field) {
    const array = field.arrayValue ?? {};
    for (const key of ['stringValues', 'longValues', 'doubleValues', 'booleanValues']) {
      if (key in array) return array[key];
    }
    return [];
  }
  return null;
};

export function toJsonObject(result) {
  const parsed = typeof result === 'string' ? JSON.parse(result) : result;
  const statements = parsed?.sqlStatementResults ?? [];
  return statements.map((statement) => {
    const columns = statement.columnMetadata ?? [];
    return (statement.records ?? []).map((record) => {
      const row = {};
      record.forEach((field, index) => {
        const column = columns[index] ?? {};
        row[column.label ?? column.name ?? String(index)] = scalar(field);
      });
      return row;
    });
  });
}

export const typeHint = {
  DATE: 'DATE', DECIMAL: 'DECIMAL', JSON: 'JSON', TIME: 'TIME',
  TIMESTAMP: 'TIMESTAMP', UUID: 'UUID'
};

const quoteIdentifier = (name) => '"' + String(name).replace(/"/g, '""') + '"';

/** Tagged template: sql\`select * from t where id = \${id}\` -> a parameterised statement. */
export function sql(strings, ...values) {
  let statement = '';
  const parameters = [];
  strings.forEach((chunk, index) => {
    statement += chunk;
    if (index < values.length) {
      parameters.push(values[index]);
      statement += ':param' + parameters.length;
    }
  });
  return { statement, parameters };
}

const whereClause = (where, parameters) => {
  if (!where || Object.keys(where).length === 0) return '';
  const clauses = [];
  for (const [column, condition] of Object.entries(where)) {
    if (condition === null || typeof condition !== 'object') {
      parameters.push(condition);
      clauses.push(quoteIdentifier(column) + ' = :param' + parameters.length);
      continue;
    }
    for (const [operator, operand] of Object.entries(condition)) {
      const sqlOperator = {
        eq: '=', ne: '<>', gt: '>', ge: '>=', lt: '<', le: '<=',
        contains: 'LIKE', beginsWith: 'LIKE'
      }[operator];
      if (!sqlOperator) {
        throw new Error('unsupported where operator: ' + operator);
      }
      let value = operand;
      if (operator === 'contains') value = '%' + operand + '%';
      if (operator === 'beginsWith') value = operand + '%';
      parameters.push(value);
      clauses.push(quoteIdentifier(column) + ' ' + sqlOperator + ' :param' + parameters.length);
    }
  }
  return ' WHERE ' + clauses.join(' AND ');
};

export function select({ table, columns, where, limit, offset, orderBy } = {}) {
  const parameters = [];
  const projection = columns && columns.length ? columns.map(quoteIdentifier).join(', ') : '*';
  let statement = 'SELECT ' + projection + ' FROM ' + quoteIdentifier(table) + whereClause(where, parameters);
  if (orderBy && orderBy.length) {
    statement += ' ORDER BY ' + orderBy
      .map((o) => quoteIdentifier(o.column) + (o.dir ? ' ' + o.dir : ''))
      .join(', ');
  }
  if (limit !== undefined && limit !== null) statement += ' LIMIT ' + Number(limit);
  if (offset !== undefined && offset !== null) statement += ' OFFSET ' + Number(offset);
  return { statement, parameters };
}

export function insert({ table, values } = {}) {
  const parameters = [];
  const columns = Object.keys(values ?? {});
  const placeholders = columns.map((column) => {
    parameters.push(values[column]);
    return ':param' + parameters.length;
  });
  return {
    statement: 'INSERT INTO ' + quoteIdentifier(table) + ' (' + columns.map(quoteIdentifier).join(', ')
      + ') VALUES (' + placeholders.join(', ') + ')',
    parameters
  };
}

export function update({ table, values, where } = {}) {
  const parameters = [];
  const assignments = Object.keys(values ?? {}).map((column) => {
    parameters.push(values[column]);
    return quoteIdentifier(column) + ' = :param' + parameters.length;
  });
  return {
    statement: 'UPDATE ' + quoteIdentifier(table) + ' SET ' + assignments.join(', ')
      + whereClause(where, parameters),
    parameters
  };
}

export function remove({ table, where } = {}) {
  const parameters = [];
  return {
    statement: 'DELETE FROM ' + quoteIdentifier(table) + whereClause(where, parameters),
    parameters
  };
}

export function createPgStatement(...statements) {
  return statements.map((s) => (typeof s === 'string' ? { statement: s, parameters: [] } : s));
}

export const createMySQLStatement = createPgStatement;
`;

const UTILS_PACKAGE_JSON = JSON.stringify({
  name: '@aws-appsync/utils',
  version: '1.0.0-floci',
  type: 'module',
  main: './index.mjs',
  exports: {
    '.': './index.mjs',
    './rds': './rds.mjs',
    './dynamodb': './index.mjs'
  }
}, null, 2);

fs.mkdirSync(UTILS_DIR, { recursive: true });
fs.mkdirSync(RESOLVER_DIR, { recursive: true });
fs.writeFileSync(path.join(UTILS_DIR, 'package.json'), UTILS_PACKAGE_JSON);
fs.writeFileSync(path.join(UTILS_DIR, 'index.mjs'), UTILS_INDEX);
fs.writeFileSync(path.join(UTILS_DIR, 'rds.mjs'), UTILS_RDS);

const ERROR_MARKER = Symbol.for('floci.appsync.error');
const RETURN_MARKER = Symbol.for('floci.appsync.earlyReturn');
const utilsModule = await import(pathToFileURL(path.join(UTILS_DIR, 'index.mjs')).href);

/** Writes the module once per distinct source and lets Node's module cache do the rest. */
async function loadResolver(code) {
  const hash = crypto.createHash('sha256').update(code).digest('hex');
  const file = path.join(RESOLVER_DIR, hash + '.mjs');
  if (!fs.existsSync(file)) {
    fs.writeFileSync(file, code);
  }
  return import(pathToFileURL(file).href);
}

function describeError(error) {
  if (error && error[RETURN_MARKER]) {
    return null;
  }
  if (error && error[ERROR_MARKER]) {
    return {
      message: error.message,
      type: error.errorType,
      data: error.errorData,
      errorInfo: error.errorInfo,
      stack: null
    };
  }
  return {
    message: error?.message ?? String(error),
    type: error?.name ?? 'Error',
    data: null,
    errorInfo: null,
    stack: error?.stack ?? null
  };
}

async function evaluate(body) {
  const { code, handler, context } = body;
  if (typeof code !== 'string' || !code.trim()) {
    return { ok: false, error: { message: 'no resolver code supplied', type: 'FlociBadRequest' } };
  }
  const module = await loadResolver(code);
  const fn = module[handler];
  if (typeof fn !== 'function') {
    // A resolver with no response handler is legal in AppSync only for the request side, so this
    // is reported rather than treated as a no-op: the caller decides what a missing handler means.
    return { ok: false, missingHandler: true,
      error: { message: 'resolver module exports no ' + handler + '() function', type: 'FlociMissingHandler' } };
  }

  utilsModule.resetAppendedErrors();
  const ctx = { ...context };
  ctx.stash = ctx.stash ?? {};
  // AppSync exposes both spellings, on the same object: a resolver that writes ctx.args sees it
  // in ctx.arguments too.
  ctx.args = ctx.arguments = ctx.arguments ?? ctx.args ?? {};
  globalThis.__floci_authType = context?.request?.authType ?? null;

  try {
    const result = await fn(ctx, utilsModule.util, utilsModule.runtime);
    return {
      ok: true,
      result: result === undefined ? null : result,
      stash: ctx.stash,
      earlyReturn: false,
      errors: [...utilsModule.appendedErrors]
    };
  } catch (error) {
    if (error && error[RETURN_MARKER]) {
      return {
        ok: true,
        result: error.value,
        stash: ctx.stash,
        earlyReturn: true,
        errors: [...utilsModule.appendedErrors]
      };
    }
    return { ok: false, error: describeError(error), stash: ctx.stash,
      errors: [...utilsModule.appendedErrors] };
  }
}

// Evaluations run one at a time. util.appendError collects into a module-level array and
// util.authType reads a global, so two evaluations interleaving at an await inside an async
// resolver would mix one's errors and auth type into the other. Serialising costs nothing here (handlers
// are short) and removes the whole class of cross-talk.
let queue = Promise.resolve();
const evaluateSerially = (body) => {
  const next = queue.then(() => evaluate(body), () => evaluate(body));
  // Keep the chain alive whatever this evaluation does, so one rejection cannot wedge the server.
  queue = next.then(() => undefined, () => undefined);
  return next;
};

const readBody = (req) => new Promise((resolve, reject) => {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  req.on('error', reject);
});

const send = (res, status, payload) => {
  const body = JSON.stringify(payload);
  res.writeHead(status, { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body) });
  res.end(body);
};

http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      send(res, 200, { ok: true, runtime: process.version });
      return;
    }
    if (req.method === 'POST' && req.url === '/evaluate') {
      const body = JSON.parse(await readBody(req));
      send(res, 200, await evaluateSerially(body));
      return;
    }
    send(res, 404, { ok: false, error: { message: 'not found', type: 'FlociBadRequest' } });
  } catch (error) {
    send(res, 200, { ok: false, error: describeError(error) });
  }
}).listen(PORT, '0.0.0.0', () => {
  console.log('floci appsync js runtime listening on ' + PORT + ' (node ' + process.version + ')');
});

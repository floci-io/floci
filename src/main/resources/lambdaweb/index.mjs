// The runtime entry Floci starts as /var/runtime/index.js, where AWS starts its own. It turns
// console output into the JSON log events AWS writes, with the request each one belongs to,
// filters the output by applicationLogLevel, then imports the app.
import { AsyncLocalStorage } from 'node:async_hooks';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import util from 'node:util';

// FATAL is above ERROR. Node has no console method for it, so it drops every console call.
const LEVELS = { TRACE: 0, DEBUG: 1, INFO: 2, WARN: 3, ERROR: 4, FATAL: 5 };
const minimum = LEVELS[process.env.AWS_LAMBDA_LOG_LEVEL] ?? LEVELS.INFO;
const region = process.env.AWS_REGION;
const requests = new AsyncLocalStorage();
const writeOut = process.stdout.write.bind(process.stdout);
const writeErr = process.stderr.write.bind(process.stderr);

const emit = http.Server.prototype.emit;
http.Server.prototype.emit = function (event, request) {
  if (event !== 'request') {
    return emit.apply(this, arguments);
  }
  const context = {
    requestId: request.headers['lambda-web-request-id'],
    endpointName: request.headers['lambda-web-endpoint-name'],
  };
  return requests.run(context, () => emit.apply(this, arguments));
};

function withErrors(key, value) {
  if (!(value instanceof Error)) {
    return value;
  }
  return { errorType: value.name, errorMessage: value.message, stackTrace: String(value.stack).split('\n'), ...value };
}

function line(level, message) {
  const context = requests.getStore();
  const event = {
    timestamp: new Date().toISOString(),
    level,
    message,
    requestId: context?.requestId,
    endpointName: context?.endpointName,
    region,
  };
  return JSON.stringify(event, withErrors) + '\n';
}

function render(level, args) {
  if (args.length !== 1 || typeof args[0] === 'string') {
    return line(level, args.length === 0 ? undefined : util.format(...args));
  }
  try {
    return line(level, args[0]);
  } catch (unserializable) {
    // A BigInt or a cycle is not JSON. AWS writes the text Node prints for it.
    return line(level, util.format(...args));
  }
}

function patch(name, level, write) {
  console[name] = function (...args) {
    if (LEVELS[level] >= minimum) {
      write(render(level, args));
    }
  };
}

patch('log', 'INFO', writeOut);
patch('info', 'INFO', writeOut);
patch('debug', 'DEBUG', writeOut);
patch('warn', 'WARN', writeErr);
patch('error', 'ERROR', writeErr);
console.trace = function (...args) {
  if (LEVELS.TRACE >= minimum) {
    const frames = String(new Error().stack).split('\n').slice(1).filter((frame) => !frame.includes(import.meta.url));
    writeOut(line('TRACE', { traceMessage: util.format(...args), stackTrace: frames }));
  }
};

// Output that does not go through console counts as INFO.
if (LEVELS.INFO < minimum) {
  const drop = (chunk, encoding, callback) => {
    const done = typeof encoding === 'function' ? encoding : callback;
    if (typeof done === 'function') {
      process.nextTick(done);
    }
    return true;
  };
  process.stdout.write = drop;
  process.stderr.write = drop;
}

// AWS writes one ERROR event in place of Node's stack when the app fails to load. Floci reads the
// error type from INIT_ERROR_FILE once the process is gone, so any exit status of the app keeps its meaning.
// /tmp is writable by any user, also in an image that does not run as root.
const INIT_ERROR_FILE = '/tmp/floci-init-error';
const entry = process.env.AWS_LAMBDA_NODEJS_ENTRYPOINT || 'index.js';
// A runtime image could carry the file from a container that failed before.
try {
  fs.rmSync(INIT_ERROR_FILE, { force: true });
} catch (ignored) {
  // The app still runs. Only a failed start would get the old error type.
}
try {
  await import(pathToFileURL(path.resolve('/var/task', entry)).href);
} catch (error) {
  // AWS goes by the code alone, also for an error the app throws itself.
  const missing = error?.code === 'MODULE_NOT_FOUND' || error?.code === 'ERR_MODULE_NOT_FOUND';
  const errorType = missing ? 'Runtime.ImportCodeError' : 'Application.InitCodeError';
  const reason = error instanceof Error ? error.message : String(error);
  if (LEVELS.ERROR >= minimum) {
    writeErr(line('ERROR', `${errorType}: could not load customer code: ${reason}` + (missing
      ? ". The entrypoint must be 'index.js' at the root of the deployment package, or the"
        + ' AWS_LAMBDA_NODEJS_ENTRYPOINT environment variable must point to your entry file'
      : '')));
  }
  try {
    fs.writeFileSync(INIT_ERROR_FILE, errorType);
  } catch (ignored) {
    // A full disk leaves Floci with Runtime.ExitError, without a stack trace AWS would not write.
  }
  process.exit(1);
}

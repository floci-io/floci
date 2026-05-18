// scripts/aws-fixtures-capture.mjs
//
// Captures real AWS Cognito request/response pairs for contract-test fixtures.
// Sanitizes PII before writing and best-effort deletes fixture users after.
//
// Usage: AWS_PROFILE=wirebit-dev node scripts/aws-fixtures-capture.mjs <pool-id> <client-id>
//
// Output: compatibility-tests/src/test/resources/fixtures/aws-cognito/*.json

import {
  CognitoIdentityProviderClient,
  SignUpCommand,
  ConfirmSignUpCommand,
  ResendConfirmationCodeCommand,
  ForgotPasswordCommand,
  InitiateAuthCommand,
  AdminDeleteUserCommand,
} from "@aws-sdk/client-cognito-identity-provider";
import { writeFileSync, mkdirSync } from "node:fs";
import { join } from "node:path";
import { randomBytes } from "node:crypto";

const [poolId, clientId] = process.argv.slice(2);
if (!poolId || !clientId) {
  console.error("Usage: node aws-fixtures-capture.mjs <pool-id> <client-id>");
  process.exit(1);
}

const OUTPUT_DIR = "compatibility-tests/src/test/resources/fixtures/aws-cognito";
mkdirSync(OUTPUT_DIR, { recursive: true });

const client = new CognitoIdentityProviderClient({ region: "us-east-1" });
const createdUsers = []; // for cleanup

function sanitize(obj) {
  if (obj === null || obj === undefined) return obj;
  const json = JSON.stringify(obj);
  return JSON.parse(
    json
      .replace(/[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g, "fixture@example.com")
      .replace(/\+\d{8,15}/g, "+5215555555555")
      .replace(/"Session":\s*"[^"]+"/g, '"Session":"<SESSION>"')
      .replace(/"RequestId":\s*"[^"]+"/g, '"RequestId":"<REQUEST_ID>"')
      .replace(/"x-amzn-requestid":\s*"[^"]+"/g, '"x-amzn-requestid":"<REQUEST_ID>"')
      .replace(/"date":\s*"[^"]+"/g, '"date":"<DATE>"'),
  );
}

async function capture(name, fn) {
  let request = null, response = null, error = null;
  try {
    const r = await fn();
    request = r.request;
    response = r.response;
  } catch (e) {
    const ctorRequest = e.$response?.body ? null : null;
    error = {
      name: e.name,
      message: e.message,
      $metadata: {
        httpStatusCode: e.$metadata?.httpStatusCode,
        requestId: e.$metadata?.requestId,
      },
    };
    request = fn._capturedRequest ?? null;
  }
  const fixture = {
    name,
    capturedAt: new Date().toISOString(),
    request: sanitize(request) ?? {},
    response: response ? sanitize(response) : null,
    error: error ? sanitize(error) : null,
  };
  writeFileSync(join(OUTPUT_DIR, `${name}.json`), JSON.stringify(fixture, null, 2));
  const status = error ? `(error: ${error.name})` : "(ok)";
  console.log(`✓ ${name} ${status}`);
}

const rand = () => randomBytes(4).toString("hex");

async function sendAndCapture(req, command, fn) {
  fn._capturedRequest = req;
  const response = await client.send(new command(req));
  return { request: req, response };
}

// --- scenarios ---

await capture("sign-up.happy", async function impl() {
  const email = `fixture-su-${rand()}@example.com`;
  createdUsers.push(email);
  const req = {
    ClientId: clientId,
    Username: email,
    Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  };
  return sendAndCapture(req, SignUpCommand, impl);
});

await capture("sign-up.error.username-exists", async function impl() {
  const email = `fixture-dup-${rand()}@example.com`;
  createdUsers.push(email);
  // First create
  await client.send(new SignUpCommand({
    ClientId: clientId, Username: email, Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  }));
  // Then duplicate
  const req = {
    ClientId: clientId, Username: email, Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  };
  return sendAndCapture(req, SignUpCommand, impl);
});

await capture("confirm-sign-up.error.code-mismatch", async function impl() {
  const email = `fixture-cm-${rand()}@example.com`;
  createdUsers.push(email);
  await client.send(new SignUpCommand({
    ClientId: clientId, Username: email, Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  }));
  const req = { ClientId: clientId, Username: email, ConfirmationCode: "000000" };
  return sendAndCapture(req, ConfirmSignUpCommand, impl);
});

await capture("resend-confirmation.happy", async function impl() {
  const email = `fixture-rc-${rand()}@example.com`;
  createdUsers.push(email);
  await client.send(new SignUpCommand({
    ClientId: clientId, Username: email, Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  }));
  const req = { ClientId: clientId, Username: email };
  return sendAndCapture(req, ResendConfirmationCodeCommand, impl);
});

await capture("resend-confirmation.error.rate-limit", async function impl() {
  const email = `fixture-rl-${rand()}@example.com`;
  createdUsers.push(email);
  await client.send(new SignUpCommand({
    ClientId: clientId, Username: email, Password: "Fixture1!Aa",
    UserAttributes: [
      { Name: "email", Value: email },
      { Name: "phone_number", Value: "+5215555550001" },
      { Name: "given_name", Value: "Fixture" },
      { Name: "family_name", Value: "User" },
    ],
  }));
  // Fire once successfully (after the implicit signup code)
  await client.send(new ResendConfirmationCodeCommand({ ClientId: clientId, Username: email }));
  // Fire immediately again — should hit rate-limit
  const req = { ClientId: clientId, Username: email };
  return sendAndCapture(req, ResendConfirmationCodeCommand, impl);
});

await capture("forgot-password.unknown-user", async function impl() {
  const req = { ClientId: clientId, Username: `does-not-exist-${rand()}@example.com` };
  return sendAndCapture(req, ForgotPasswordCommand, impl);
});

await capture("initiate-auth.srp.unknown-user", async function impl() {
  const req = {
    AuthFlow: "USER_SRP_AUTH",
    ClientId: clientId,
    AuthParameters: {
      USERNAME: `nobody-${rand()}@example.com`,
      SRP_A: "a".repeat(512),
    },
  };
  return sendAndCapture(req, InitiateAuthCommand, impl);
});

// --- cleanup ---

console.log(`\nCleaning up ${createdUsers.length} fixture users...`);
for (const username of createdUsers) {
  try {
    await client.send(new AdminDeleteUserCommand({ UserPoolId: poolId, Username: username }));
    console.log(`  deleted ${username}`);
  } catch (e) {
    console.log(`  skip ${username} (${e.name})`);
  }
}

console.log(`\nAll fixtures saved to ${OUTPUT_DIR}`);

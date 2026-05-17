# floci-web

Browser-based management console for [Floci](https://github.com/floci-io/floci), the local AWS emulator.

Currently covers **S3**, **RDS**, **Lambda**, **EC2**, and **EventBridge Scheduler**. The left sidebar lists every service the console supports — add new services by dropping hooks under `src/hooks/<svc>/`, routes under `src/routes/<svc>/`, and a `ServiceLink` entry in `src/components/AppShell.tsx`.

Built with React 18, Vite, TypeScript, AWS SDK v3, TanStack Query, React Router, Tailwind, and shadcn-style Radix primitives.

## Prerequisites

- Node.js 20+
- A running Floci instance at `http://localhost:4566`

```bash
# in a separate terminal / clone
cd ~/Desktop/projects/floci
./mvnw quarkus:dev
```

## Setup

```bash
npm install
npm run dev
# open http://localhost:5173
```

## How it talks to Floci

The dev server proxies `/_aws/*` to `http://localhost:4566/*`. Every AWS SDK client in the app (`@aws-sdk/client-s3`, `@aws-sdk/client-rds`, …) is configured with `endpoint: window.location.origin + "/_aws"` — the same alias is shared by all services, since Floci dispatches by `Action` / `X-Amz-Target` / path, not by URL prefix. All requests are same-origin to the browser — no CORS preflight needed in dev.

This works because Floci defaults to `floci.auth.validate-signatures: false`. If you enable signature validation, you'll need to remove the proxy path rewrite and call Floci directly (and configure per-service CORS).

## Scripts

| Command | Purpose |
|---|---|
| `npm run dev` | Vite dev server with HMR |
| `npm run build` | Type-check + production build |
| `npm run preview` | Preview the production build |
| `npm run typecheck` | TypeScript-only check, no emit |

## Feature coverage

### S3 — mirrors [`floci/docs/services/s3.md`](https://github.com/floci-io/floci/blob/main/docs/services/s3.md)

- Buckets: list, create (with Object Lock at create), delete, location
- Objects: list (prefix + delimiter), upload (auto-multipart >5MB), download (presigned), copy, delete, bulk delete
- Versions, Multipart in-progress, Tags, Lifecycle, CORS, Policy, Object Lock, Encryption, Public Access Block, Notifications
- Pre-signed URL generator (GET / PUT)
- S3 Select playground (CSV / JSON / Parquet)

### RDS — mirrors [`floci/docs/services/rds.md`](https://github.com/floci-io/floci/blob/main/docs/services/rds.md)

- DB Instances: list, create (postgres / mysql / mariadb / Aurora), describe, reboot, modify (class / storage / IAM auth), delete. Connection-command (`psql` / `mysql` / `mariadb`) generated per instance with the host port Floci proxies to.
- DB Clusters: list, create, describe, modify, delete. Cluster detail shows member instances and lets you add a new instance attached to the cluster.
- DB Parameter Groups: list, create, delete. Per-group parameter editor with filter, paged auto-fetch, batched modify (dynamic params apply immediately, others queued for reboot).
- IAM database authentication toggle on instance create + modify.

### EventBridge Scheduler — mirrors [`floci/docs/services/scheduler.md`](https://github.com/floci-io/floci/blob/main/docs/services/scheduler.md)

- **Schedules**: list (with group filter), create / update / delete. Dialog has a three-mode expression builder — `at(...)` one-time fire, `rate(N unit)` recurring, `cron(...)` AWS 6-field cron — plus timezone, optional start/end dates, flexible time window toggle (with stored-but-not-jittered warning), and `ActionAfterCompletion` selector when in `at(...)` mode. Target editor takes ARN, RoleArn, and a JSON `Input` payload.
- **Schedule Groups**: list, create, delete (default group protected). Detail page exposes tags (TagResource / UntagResource diff) and the schedules belonging to the group.
- Doc-driven hints surfaced to the user: dispatcher tick interval, supported target families (SQS / Lambda / SNS / EventBridge `PutEvents`), and the "RetryPolicy + DeadLetterConfig are stored but not honored" caveat shown on schedule detail.

### EC2 — mirrors [`floci/docs/services/ec2.md`](https://github.com/floci-io/floci/blob/main/docs/services/ec2.md)

- **Instances**: list, launch (AMI / instance type / key pair / subnet / SG / IAM profile / UserData / Name tag), detail page with start / stop / reboot / terminate buttons, network + access + IMDS info, tags. UserData is base64-encoded in the browser before launch.
- **AMIs**: read-only list of `DescribeImages` results plus Floci's built-in AMI mappings.
- **Key Pairs**: list, `CreateKeyPair` (with dummy PEM displayed once), `ImportKeyPair` for working SSH access, delete.
- **Security Groups**: list, create (with VPC selection), delete. Detail page with ingress + egress rule tables, add-rule dialog (protocol / port range / CIDR), revoke per rule.
- **VPCs**: list, create, delete (default VPC protected).
- **Subnets**: list, create (VPC + CIDR + AZ), delete.
- **Internet Gateways**: list, create, attach to VPC, detach, delete.
- **Route Tables**: list, create, delete. Detail page with routes table + add-route dialog (gateway / NAT gateway / instance targets) and revoke per route, plus subnet associations.
- **Elastic IPs**: allocate, associate to running instance, disassociate, release.
- **Volumes**: list, create (gp3/gp2/io2/io1/st1/sc1/standard, AZ), delete.

### Lambda — mirrors [`floci/docs/services/lambda.md`](https://github.com/floci-io/floci/blob/main/docs/services/lambda.md)

- Functions: list, create (ZIP upload / S3 reactive sync / `S3Bucket=hot-reload` bind mount), describe, delete.
- Configuration tab: runtime, handler, role, memory, timeout, tracing, description, environment variables.
- Code tab: code SHA-256, code size, download URL; update via ZIP upload or S3.
- Invoke tab: JSON payload editor, sync/async/dry-run modes, optional 4 KB log tail (decoded from base64), version/alias qualifier.
- Versions & Aliases tab: publish version, list versions, create/delete aliases pinned to a version.
- Event sources tab: list/create/delete event source mappings for SQS / Kinesis / DDB Streams; SQS gets `ScalingConfig.MaximumConcurrency` (2–1000), streams get `StartingPosition`.
- Permissions tab: parsed `Statement` table + raw policy JSON viewer; add / remove statements.
- Function URL tab: create / update / delete URL config with auth type (NONE / AWS_IAM); copy URL to clipboard.
- Concurrency tab: reserved concurrent executions get / put / delete.
- Tags tab: function-level tags with diff-based save (TagResource + UntagResource).

### Not in the UI

`floci/docs/services/s3.md` lists these AWS S3 features as not implemented in Floci — the UI does not expose them:

- Replication
- Website hosting
- Access logging
- Request payment
- Intelligent-Tiering configurations
- Inventory configurations
- Metrics / Analytics configurations

## Production deployment

The Vite dev proxy is not part of the production build. If you serve `dist/` from a different origin than Floci, you must either:

1. Place a same-origin reverse proxy in front of Floci (nginx, Caddy, etc.) that maps `/_s3 → :4566`, **or**
2. Run `PutBucketCors` against every bucket the UI needs to touch (each rule must allow the frontend origin and the methods you use).

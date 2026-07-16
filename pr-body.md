Closes #1749

Implements the EventBridge Connection lifecycle: `CreateConnection`, `DescribeConnection`, `UpdateConnection`, `DeleteConnection`, and `ListConnections`, mirroring the existing Archive CRUD pattern in `EventBridgeHandler`/`EventBridgeService`.

## Behavior notes

- Connections are `AUTHORIZED` immediately (the emulator performs no real auth handshake).
- `DescribeConnection` strips credential values (`ApiKeyValue`, `Password`, `ClientSecret`) and masks invocation http parameter values flagged `IsValueSecret`, matching AWS.
- `SecretArn` is synthesized in AWS's `events!connection/<name>/<uuid>` format; no actual Secrets Manager secret is created (documented deviation).
- Out of scope: `DeauthorizeConnection`, `ListConnections` pagination (consistent with `ListArchives`).

## Testing

- New `EventBridgeConnectionIntegrationTest` (9 ordered cases: lifecycle, duplicate/missing/invalid-auth errors, secret stripping).
- Verified end-to-end with the AWS CLI (`events create-connection` / `describe-connection` / `list-connections` / `delete-connection`) against the packaged emulator.
- Docs action table regenerated (`docs-sync`).

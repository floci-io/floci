# Transfer Family

**Protocol:** JSON 1.1  
**Endpoint:** `http://localhost:4566/`  
**X-Amz-Target prefix:** `TransferService.`

AWS Transfer Family managed file transfer server management. This implementation covers the management-plane API for server and user lifecycle, SSH public key management, and tagging. Actual SFTP/FTP protocol handling is out of scope — server state is simulated in-process.

## Supported Actions

<!-- floci:actions:start -->
| Action |
| --- |
| `CreateServer` |
| `DescribeServer` |
| `DeleteServer` |
| `ListServers` |
| `StartServer` |
| `StopServer` |
| `UpdateServer` |
| `CreateUser` |
| `DescribeUser` |
| `DeleteUser` |
| `ListUsers` |
| `UpdateUser` |
| `ImportSshPublicKey` |
| `DeleteSshPublicKey` |
| `TagResource` |
| `UntagResource` |
| `ListTagsForResource` |
<!-- floci:actions:end -->

## Configuration

```yaml
floci:
  services:
    transfer:
      enabled: true
```

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_TRANSFER_ENABLED` | `true` | Enable or disable Transfer Family |

## ARN Format

```
arn:aws:transfer:{region}:{accountId}:server/{serverId}
arn:aws:transfer:{region}:{accountId}:user/{serverId}/{userName}
```

Server IDs have the format `s-` followed by 17 lowercase alphanumeric characters (e.g. `s-01234567890abcdef`).

## Example Usage

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a server
aws transfer create-server \
  --protocols SFTP \
  --endpoint-type PUBLIC

# List servers
aws transfer list-servers

# Stop a server (must be ONLINE)
aws transfer stop-server --server-id s-01234567890abcdef

# Start a server (must be OFFLINE)
aws transfer start-server --server-id s-01234567890abcdef

# Create a user
aws transfer create-user \
  --server-id s-01234567890abcdef \
  --user-name alice \
  --role arn:aws:iam::000000000000:role/transfer-role \
  --home-directory /uploads

# Import an SSH public key
aws transfer import-ssh-public-key \
  --server-id s-01234567890abcdef \
  --user-name alice \
  --ssh-public-key-body "ssh-rsa AAAA..."

# List users on a server
aws transfer list-users --server-id s-01234567890abcdef

# Tag a server
aws transfer tag-resource \
  --arn arn:aws:transfer:us-east-1:000000000000:server/s-01234567890abcdef \
  --tags Key=env,Value=dev

# Delete a user then the server
aws transfer delete-user \
  --server-id s-01234567890abcdef \
  --user-name alice
aws transfer stop-server --server-id s-01234567890abcdef
aws transfer delete-server --server-id s-01234567890abcdef
```

## Notes

- **Phase 1** covers the management-plane API only. Data-plane SFTP connectivity (actual file transfer) is not emulated.
- Server `EndpointType` defaults to `PUBLIC`. The `State` field transitions between `ONLINE` and `OFFLINE` via `StartServer` / `StopServer`.
- SSH key bodies are stored and returned as-is; no cryptographic validation is performed.

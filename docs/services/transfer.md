# Transfer Family

**Protocol:** JSON 1.1  
**Endpoint:** `http://localhost:4566/`  
**X-Amz-Target prefix:** `TransferService.`

AWS Transfer Family managed file transfer server management. This implementation covers the management-plane API for server and user lifecycle, SSH public key management, and tagging. Actual SFTP/FTP protocol handling is out of scope — server state is simulated in-process.

## Supported Actions

### Servers

| Action | Description |
|---|---|
| `CreateServer` | Create a managed file transfer server |
| `DescribeServer` | Get server metadata and configuration |
| `UpdateServer` | Update protocols, endpoint type, logging role, security policy |
| `DeleteServer` | Delete a server in any state, along with its users |
| `ListServers` | Paginated list of servers |
| `StartServer` | Transition server from `OFFLINE` to `ONLINE` |
| `StopServer` | Transition server from `ONLINE` to `OFFLINE` |

### Users

| Action | Description |
|---|---|
| `CreateUser` | Associate a user with a server |
| `DescribeUser` | Get user configuration and SSH keys |
| `UpdateUser` | Update role, home directory, or home directory mappings |
| `DeleteUser` | Remove a user from a server |
| `ListUsers` | Paginated list of users on a server |

### SSH Public Keys

| Action | Description |
|---|---|
| `ImportSshPublicKey` | Attach an SSH public key to a user |
| `DeleteSshPublicKey` | Remove an SSH public key from a user |

### Tagging

| Action | Description |
|---|---|
| `TagResource` | Add or update tags on a server or user |
| `UntagResource` | Remove tags from a server or user |
| `ListTagsForResource` | List tags for a server or user |

## Configuration

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

# Amazon MQ (RabbitMQ)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/`

Floci emulates Amazon MQ by orchestrating **RabbitMQ** containers. Each broker is
backed by a real `rabbitmq:3-management` container, so AMQP clients and the RabbitMQ
management console work against the published endpoints.

Only the **RabbitMQ** engine and the `SINGLE_INSTANCE` deployment mode are supported;
`CreateBroker` rejects `ACTIVEMQ` and the multi-AZ deployment modes.

## Supported Actions

| Action | Description |
|---|---|
| `CreateBroker` | Provisions a RabbitMQ container and seeds the admin user |
| `DescribeBroker` | Get broker metadata, state, and connection endpoints |
| `ListBrokers` | List all emulated brokers |
| `DeleteBroker` | Stops and removes the RabbitMQ container |
| `RebootBroker` | Reboots the broker |

### User management

Amazon MQ's user API (`CreateUser`, `DescribeUser`, `ListUsers`, `UpdateUser`,
`DeleteUser`) applies **only to ActiveMQ** brokers. As on real AWS, Floci rejects these
operations for RabbitMQ brokers with a `BadRequestException`. Manage RabbitMQ users
through the RabbitMQ management console. The broker's initial administrator is supplied
in the `CreateBroker` `Users` list (exactly one user is required) and seeded into the
container.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AMAZONMQ_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_AMAZONMQ_MOCK` | `false` | `true` = metadata-only CRUD, no Docker containers |
| `FLOCI_SERVICES_AMAZONMQ_DEFAULT_IMAGE` | `rabbitmq:3-management` | Docker image for RabbitMQ broker containers |

## How it works

When `mock` is set to `false` (default), Floci uses the Docker API to start a RabbitMQ
container for each created broker. For Docker socket setup, private registry
authentication, and other Docker settings see [Docker Configuration](../configuration/docker.md).

- **Port Mapping**: The AMQP port (5672) and the management UI (15672) are each mapped to
  a dynamic host port. Use the endpoints returned by `DescribeBroker` rather than a fixed
  port.
- **Admin user**: The `CreateBroker` user is seeded via `RABBITMQ_DEFAULT_USER` /
  `RABBITMQ_DEFAULT_PASS`. Unlike the built-in `guest` user (which RabbitMQ restricts to
  loopback connections), this user can authenticate over the mapped port.
- **Persistence**: Each broker gets a named Docker volume. In memory mode the volume is
  removed on broker delete; in persistent modes it is retained unless
  `FLOCI_STORAGE_PRUNE_VOLUMES_ON_DELETE=true`.
- **Readiness**: The broker state transitions to `RUNNING` once the RabbitMQ management
  API answers on its port.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a broker (exactly one admin user is required for RabbitMQ)
aws mq create-broker \
  --broker-name my-broker \
  --engine-type RABBITMQ \
  --engine-version "3.13" \
  --deployment-mode SINGLE_INSTANCE \
  --host-instance-type mq.t3.micro \
  --no-publicly-accessible \
  --auto-minor-version-upgrade \
  --users '[{"Username":"admin","Password":"AdminPass123","ConsoleAccess":true}]' \
  --endpoint-url $AWS_ENDPOINT_URL

# Describe a broker (poll until BrokerState is RUNNING)
BROKER_ID=$(aws mq list-brokers --query 'BrokerSummaries[0].BrokerId' --output text --endpoint-url $AWS_ENDPOINT_URL)
aws mq describe-broker --broker-id $BROKER_ID --endpoint-url $AWS_ENDPOINT_URL

# Delete a broker
aws mq delete-broker --broker-id $BROKER_ID --endpoint-url $AWS_ENDPOINT_URL
```

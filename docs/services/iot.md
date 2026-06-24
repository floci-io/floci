# AWS IoT Core

**Protocol:** REST JSON (control) + MQTT (data plane)  
**Endpoint:** `http://localhost:4566/`

AWS IoT Core data plane with token-based custom authentication. An embedded MQTT broker (Moquette) lets devices connect, publish, and subscribe over MQTT and MQTT-over-WebSocket, in plain and TLS variants (`tcp://`, `ws://`, `ssl://`, `wss://`). Every connection is authenticated by a token-based custom authorizer: Floci invokes your authorizer Lambda at CONNECT and enforces the IAM policy it returns for `iot:Connect`, `iot:Publish`, `iot:Subscribe`, and `iot:Receive`.

## Supported Actions

### Custom Authorizers

| Action | Description |
|---|---|
| `CreateAuthorizer` | Register a token-based custom authorizer (Lambda ARN, token key, signing keys) |
| `DescribeAuthorizer` | Get authorizer configuration |
| `UpdateAuthorizer` | Update function ARN, token key, signing keys, or status |
| `DeleteAuthorizer` | Delete an authorizer (not while it is the default) |
| `ListAuthorizers` | Paginated list of authorizers, optionally filtered by status |
| `SetDefaultAuthorizer` | Set the account default authorizer |
| `TestInvokeAuthorizer` | Invoke the authorizer Lambda and return its authentication result and policy |

### Data Plane

| Action | Description |
|---|---|
| `Publish` | Publish a message (request body) to a topic via `POST /topics/{topicName}` (optional `?qos=0\|1`) |
| `DescribeEndpoint` | Return the broker address for the requested `endpointType` |

## Custom Authentication

The broker routes every CONNECT through a custom authorizer. Custom-auth parameters are read from the MQTT CONNECT username, encoded as a query string — the format the AWS IoT Device SDK v2 produces and which mqtt.js can replicate:

```
<username>?x-amz-customauthorizer-name=<authorizerName>&x-amz-customauthorizer-signature=<sig>&<tokenKeyName>=<tokenValue>
```

`x-amz-customauthorizer-name` is optional when a default authorizer is set. The signature is required unless the authorizer was created with `signingDisabled`; when required it is verified (RSA `SHA256withRSA`) against the registered `tokenSigningPublicKeys` before the Lambda is invoked. `TestInvokeAuthorizer` exercises the same path without a device, returning `{ isAuthenticated, principalId, policyDocuments, disconnectAfterInSeconds, refreshAfterInSeconds }`.

!!! note "Self-signed TLS certificate"
    TLS listeners use an auto-generated self-signed certificate for `localhost` (or a keystore you supply via `FLOCI_SERVICES_IOT_BROKER_TLS_KEYSTORE_PATH`). There is no public CA chain locally, so clients must trust it — for example `rejectUnauthorized: false` in mqtt.js, or a permissive trust store in the AWS IoT Device SDK.

### Lifecycle (presence) events

On connect and disconnect the broker publishes an AWS-shaped event to the reserved presence topics, so other clients can track device presence:

```
$aws/events/presence/connected/{clientId}
$aws/events/presence/disconnected/{clientId}
```

```json
{
  "clientId": "device-1",
  "timestamp": 1782314873926,
  "eventType": "connected",
  "sessionIdentifier": "e8940d94-5cda-48d2-a586-790cb743a636",
  "principalIdentifier": "device-principal",
  "versionNumber": 0
}
```

Disconnect events additionally include `clientInitiatedDisconnect` and `disconnectReason` (`CLIENT_INITIATED_DISCONNECT` or `CONNECTION_LOST`); each disconnect yields exactly one event. Subscribers need a policy that allows `iot:Subscribe` / `iot:Receive` on the `$aws/events/presence/#` topics. Disable with `FLOCI_SERVICES_IOT_BROKER_LIFECYCLE_EVENTS_ENABLED=false`.

### Last Will & Testament

Standard MQTT LWT is supported: set a will on CONNECT and the broker publishes it to the will topic when the client drops ungracefully. A clean DISCONNECT discards the will, per the MQTT specification.

## Configuration

| Environment variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IOT_ENABLED` | `true` | Enable or disable AWS IoT Core |
| `FLOCI_SERVICES_IOT_BROKER_ENABLED` | `true` | Start the embedded MQTT broker |
| `FLOCI_SERVICES_IOT_BROKER_HOST` | `0.0.0.0` | Broker bind host |
| `FLOCI_SERVICES_IOT_BROKER_TCP_BASE_PORT` | `1883` | First port in the MQTT (TCP) range |
| `FLOCI_SERVICES_IOT_BROKER_TCP_MAX_PORT` | `1899` | Last port in the MQTT (TCP) range |
| `FLOCI_SERVICES_IOT_BROKER_WS_BASE_PORT` | `8083` | First port in the MQTT-over-WebSocket range |
| `FLOCI_SERVICES_IOT_BROKER_WS_MAX_PORT` | `8099` | Last port in the MQTT-over-WebSocket range |
| `FLOCI_SERVICES_IOT_BROKER_WS_PATH` | `/mqtt` | WebSocket path |
| `FLOCI_SERVICES_IOT_BROKER_TLS_ENABLED` | `true` | Start TLS listeners (`ssl://` and `wss://`) |
| `FLOCI_SERVICES_IOT_BROKER_MQTTS_BASE_PORT` | `8883` | First port in the secure MQTT (TLS) range |
| `FLOCI_SERVICES_IOT_BROKER_MQTTS_MAX_PORT` | `8899` | Last port in the secure MQTT (TLS) range |
| `FLOCI_SERVICES_IOT_BROKER_WSS_BASE_PORT` | `8443` | First port in the secure WebSocket (WSS) range |
| `FLOCI_SERVICES_IOT_BROKER_WSS_MAX_PORT` | `8459` | Last port in the secure WebSocket (WSS) range |
| `FLOCI_SERVICES_IOT_BROKER_TLS_KEYSTORE_PATH` | *(auto)* | PKCS12 keystore; a self-signed certificate is generated if unset |
| `FLOCI_SERVICES_IOT_BROKER_TLS_KEYSTORE_PASSWORD` | `floci-iot` | Keystore password |
| `FLOCI_SERVICES_IOT_BROKER_LIFECYCLE_EVENTS_ENABLED` | `true` | Publish `$aws/events/presence/*` connect/disconnect events |

## ARN Format

```
arn:aws:iot:{region}:{accountId}:authorizer/{authorizerName}
```

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Register a token-based custom authorizer (its Lambda must already be deployed)
aws iot create-authorizer \
  --authorizer-name device-auth \
  --authorizer-function-arn arn:aws:lambda:us-east-1:000000000000:function:my-authorizer \
  --token-key-name MyAuthToken \
  --signing-disabled

# Validate the authorizer without a device
aws iot test-invoke-authorizer \
  --authorizer-name device-auth \
  --token "my-token" \
  --mqtt-context '{"clientId":"device-1"}'

# Resolve the broker endpoint that devices connect to over wss://
aws iot describe-endpoint --endpoint-type iot:Data-ATS

# Publish a message over HTTP
aws iot-data publish \
  --topic devices/sensor-1/data \
  --cli-binary-format raw-in-base64-out \
  --payload '{"temp":22.5}'
```

## Java SDK Example

The management plane and HTTP publish use the standard AWS SDK (`IotClient` and
`IotDataPlaneClient`):

```java
IotClient iot = IotClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

// Register a token-based custom authorizer
iot.createAuthorizer(r -> r
    .authorizerName("device-auth")
    .authorizerFunctionArn("arn:aws:lambda:us-east-1:000000000000:function:my-authorizer")
    .tokenKeyName("MyAuthToken")
    .signingDisabled(true)
    .status(AuthorizerStatus.ACTIVE));

// Validate it without a device
TestInvokeAuthorizerResponse result = iot.testInvokeAuthorizer(r -> r
    .authorizerName("device-auth")
    .token("my-token")
    .mqttContext(m -> m.clientId("device-1")));
System.out.println(result.isAuthenticated());

// Resolve the broker endpoint
String endpoint = iot.describeEndpoint(r -> r.endpointType("iot:Data-ATS")).endpointAddress();

// Publish a message over HTTP
IotDataPlaneClient data = IotDataPlaneClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

data.publish(r -> r
    .topic("devices/sensor-1/data")
    .qos(1)
    .payload(SdkBytes.fromUtf8String("{\"temp\":22.5}")));
```

## Connecting Devices over MQTT

Connect with the **AWS IoT Device SDK v2** (or any MQTT client), supplying the custom-auth
parameters and pointing the endpoint at the address returned by `DescribeEndpoint`:

```java
try (AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder.newDefaultBuilder()) {
    builder.withCustomAuthorizer(
            null,            // username
            "device-auth",   // authorizerName
            null,            // tokenSignature (null when the authorizer is signingDisabled)
            "unused",        // password
            "MyAuthToken",   // tokenKeyName
            "my-token");     // tokenValue
    builder.withEndpoint("localhost").withPort(8443).withClientId("device-1");
    try (MqttClientConnection connection = builder.build()) {
        connection.connect().get();
        connection.publish(new MqttMessage(
            "devices/sensor-1/data",
            "{\"temp\":22.5}".getBytes(StandardCharsets.UTF_8),
            QualityOfService.AT_LEAST_ONCE));
        connection.disconnect().get();
    }
}
```

Any generic MQTT client works the same way — encode the authorizer name, signature, and token
into the CONNECT username as shown under [Custom Authentication](#custom-authentication).

## Notes

- HTTP `Publish` is unauthenticated; MQTT publish/subscribe remain policy-enforced.
- The thing registry (things, thing types, thing groups, IoT policies), X.509 certificate-based client authentication, jobs, and device shadows are not implemented.

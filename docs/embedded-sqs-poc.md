# Embedded SQS proof of concept

This experiment runs the real Floci SQS service and JSON handler inside the
calling JVM. It starts a JDK HTTP server on a dynamically assigned loopback port.
There is no Docker process, Quarkus application bootstrap, or Spring context.

```java
try (EmbeddedSqs server = EmbeddedSqs.start();
     SqsClient client = SqsClient.builder()
         .endpointOverride(server.endpoint())
         .region(Region.US_EAST_1)
         .credentialsProvider(StaticCredentialsProvider.create(
             AwsBasicCredentials.create("test", "test")))
         .build()) {
    String queue = client.createQueue(r -> r.queueName("orders")).queueUrl();
    client.sendMessage(r -> r.queueUrl(queue).messageBody("hello"));
}
```

## Run

With JDK 25 selected, from this worktree:

```sh
./mvnw test -Dtest=EmbeddedSqsTest
```

The SDK version matches the repository's Java compatibility project (2.52.0).
Tests exercise standard queue delivery, visibility changes, deletion, FIFO order
and deduplication, DLQ delivery, long polling, instance isolation, SDK error decoding, and port
release. They do not use `@QuarkusTest`.

Verified on 2026-09-13 with Corretto 25, based on Floci `1af3b566`:
6 tests passed, 0 failures/errors/skips. Surefire reported 1.789 seconds for the
test class, including a one-second long poll. This is not a startup benchmark.
The initial Maven build took 1 minute 50 seconds including dependency downloads
and compilation. Report: `target/surefire-reports/io.github.hectorvent.floci.services.sqs.EmbeddedSqsTest.txt`.

## Scope and tradeoffs

This is a test fixture in `src/test`, not a published Maven library. It reuses
`SqsServiceFactory`, the existing test construction seam, so it does not alter
production storage/configuration or duplicate queue algorithms. The build still
compiles Floci and carries its dependency graph. Runtime simplicity does not yet
mean a small independent artifact.

Supported transport is AWS SQS JSON 1.0. Query/XML clients, SNS/Lambda integration,
IAM/signature validation, multi-account routing, persistence, and ElasticMQ HOCON
import are outside this POC. Region comes from the SDK signature; account is fixed
to `000000000000`. Use fake credentials and loopback only. The request body limit
is 2 MiB. HTTP workers are capped at eight, with 32 queued tasks; saturation may
disconnect clients. This is not a production broker or a full compatibility claim.

To migrate a JSON-capable ElasticMQ test, replace server startup with
`EmbeddedSqs.start()`, use `endpoint()`, and recreate queues through the SDK.
Do not reuse old queue URLs or copy ElasticMQ persistence files.

## Next packaging step

Expose a supported SQS construction/lifecycle seam, separate optional SNS hooks,
and extract only the required service/protocol/storage dependencies into an
independent artifact. Add Query protocol and shared SDK contract tests before
claiming ElasticMQ replacement compatibility. A Spring test adapter can then
publish the endpoint through dynamic properties while owning `close()`.

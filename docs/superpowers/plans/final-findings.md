1. **Missing APIs**: The spec requires `DescribeClusters` and `DeleteCluster` to be supported in Phase 1, but the current patch only implements `CreateCluster`.
2. **Missing Data Plane (Docker)**: There is no implementation of the `RedshiftContainerManager` or the Docker lifecycle (Create, Health check, Delete) using the `postgres:15-alpine` container. The cluster status currently stays hardcoded at "creating".
3. **Storage Architecture**: `RedshiftService` stores clusters in a plain `ConcurrentHashMap`. As per the spec and Floci standards, it must use `StorageFactory` to support both in-memory and persistent storage modes.
4. **Configuration Missing**: The required configuration properties (e.g., port, image version) have not been added to `EmulatorConfig`, main `application.yml`, or test `application.yml`.
5. **Service Registration**: The new service is not registered in `ServiceRegistry` as required by the Floci `AGENTS.md` guidelines for adding a new service.
6. **Error Handling**: `RedshiftQueryHandler` returns a raw 400 Response for unknown actions. It should throw an `AwsException` so that the `AwsExceptionMapper` can format standard AWS XML errors.
7. **Missing Integration Tests**: The spec explicitly requires integration tests using the AWS Java SDK v2 (`RedshiftClient`) and JDBC driver to ensure the Data Plane works, which are completely absent from the patch.

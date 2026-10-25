# Redshift Serverless

**Protocol:** JSON 1.1
**Endpoint:** `POST http://localhost:4566/` with `X-Amz-Target: RedshiftServerless.<Operation>` and `Content-Type: application/x-amz-json-1.1`

Floci emulates the namespace and workgroup lifecycle of Amazon Redshift Serverless: the account and Region scoped namespace that holds a serverless database, its admin credentials, and its IAM roles, and the workgroup that carries its compute settings and endpoint. This is the surface Terraform's `aws_redshiftserverless_namespace` and `aws_redshiftserverless_workgroup` drive, and the surface the `AWS::RedshiftServerless::Namespace` and `AWS::RedshiftServerless::Workgroup` CloudFormation types provision.

For the upstream API shape, see the [Amazon Redshift Serverless API Reference](https://docs.aws.amazon.com/redshift-serverless/latest/APIReference/Welcome.html).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateNamespace` | Create a namespace, applying AWS defaults for `dbName`, `kmsKeyId`, and `logExports` |
| `GetNamespace` | Return a namespace by name |
| `ListNamespaces` | Page through the namespaces in the account and Region |
| `UpdateNamespace` | Apply the supplied fields to an existing namespace and return it |
| `DeleteNamespace` | Remove a namespace and return it with `status` `DELETING` |
| `CreateWorkgroup` | Create a workgroup in a namespace that has none, applying AWS defaults, starting its container and proxy, and returning its `endpoint` |
| `GetWorkgroup` | Return a workgroup by name |
| `ListWorkgroups` | Page through the workgroups in the account and Region |
| `UpdateWorkgroup` | Apply the supplied fields to an existing workgroup and return it |
| `DeleteWorkgroup` | Stop a workgroup's container and proxy, release its endpoint, and return it with `status` `DELETING` |
| `CreateSnapshot` | Create an `AVAILABLE` snapshot of a namespace, dumping the workgroup's database when it is running; a snapshot created in a standby Region stands in for a cross-Region copy |
| `GetSnapshot` | Return a snapshot by `snapshotName` or `snapshotArn` |
| `ListSnapshots` | Page through the snapshots in the Region, filtered by `namespaceName`, `namespaceArn`, `ownerAccount` and a `startTime`/`endTime` window |
| `UpdateSnapshot` | Change a snapshot's `retentionPeriod` (-1 for indefinite); an omitted period leaves it unchanged |
| `DeleteSnapshot` | Remove a snapshot and return it |
| `RestoreFromSnapshot` | Restore into an existing namespace and workgroup, replaying the snapshot's dump into the workgroup's database; neither is created |
| `CreateUsageLimit` | Set a usage limit on a workgroup, defaulting `period` to `monthly` and `breachAction` to `log` |
| `GetUsageLimit` | Return a usage limit by `usageLimitId` |
| `ListUsageLimits` | Page through the usage limits in the Region, filtered by `resourceArn` and `usageType` |
| `UpdateUsageLimit` | Change the `amount` or `breachAction` of a usage limit and return it |
| `DeleteUsageLimit` | Remove a usage limit and return it |
| `GetCredentials` | Mint a temporary database user and password for a workgroup, valid on its endpoint until `expiration` |
| `ListTagsForResource` | Return the tags on the namespace or workgroup named by `resourceArn` |
| `TagResource` | Merge tags into the namespace or workgroup named by `resourceArn` |
| `UntagResource` | Remove tags by key from the namespace or workgroup named by `resourceArn` |
| `PutResourcePolicy` | Replace the resource policy of a snapshot; the policy must be a JSON object and is stored, not evaluated |
| `GetResourcePolicy` | Return the resource policy of a snapshot |
| `DeleteResourcePolicy` | Remove the resource policy of a snapshot |
| `GetTrack` | Return one of the release tracks `current` and `trailing`; a track carries no `workgroupVersion` |
| `ListTracks` | Page through the two release tracks |
| `ListManagedWorkgroups` | Always an empty list: Floci models no Glue zero-ETL managed workgroups |
| `ListReservations` | Always an empty list: Floci offers no reservations |
| `ListReservationOfferings` | Always an empty list: Floci publishes no reservation offerings |
| `GetReservation` | Always `ResourceNotFoundException`, as no reservation exists |
| `GetReservationOffering` | Always `ResourceNotFoundException`, as no offering exists |
| `CreateReservation` | Validates `capacity` and `offeringId`, then answers `ResourceNotFoundException` because no offering exists |
| `CreateEndpointAccess` | Record a managed VPC endpoint for a workgroup; `address` and `port` are the workgroup's own |
| `GetEndpointAccess` | Return an endpoint by `endpointName` |
| `ListEndpointAccess` | Page through the endpoints, filtered by `workgroupName`, `ownerAccount` and `vpcId` |
| `UpdateEndpointAccess` | Replace the `vpcSecurityGroupIds` of an endpoint |
| `DeleteEndpointAccess` | Remove an endpoint and return it with `endpointStatus` `DELETING` |
| `CreateCustomDomainAssociation` | Record a custom domain and ACM certificate ARN for a workgroup |
| `GetCustomDomainAssociation` | Return a custom domain association |
| `ListCustomDomainAssociations` | Page through the associations, filtered by `customDomainName` and `customDomainCertificateArn` |
| `UpdateCustomDomainAssociation` | Point an association at another certificate ARN |
| `DeleteCustomDomainAssociation` | Remove a custom domain association |
| `CreateSnapshotCopyConfiguration` | Record a snapshot copy configuration to another Region; nothing is copied |
| `ListSnapshotCopyConfigurations` | Page through the snapshot copy configurations, filtered by `namespaceName` |
| `UpdateSnapshotCopyConfiguration` | Change the `snapshotRetentionPeriod` of a snapshot copy configuration |
| `DeleteSnapshotCopyConfiguration` | Remove a snapshot copy configuration and return it |
| `CreateScheduledAction` | Record a scheduled `createSnapshot` action; it is stored, never run |
| `GetScheduledAction` | Return a scheduled action by name |
| `ListScheduledActions` | Page through the scheduled actions, filtered by `namespaceName` |
| `UpdateScheduledAction` | Apply the supplied members to a scheduled action; `schedule` and `targetAction` replace the stored ones |
| `DeleteScheduledAction` | Remove a scheduled action and return it |
<!-- floci:actions:end -->

Namespace and workgroup state is account and Region scoped and persisted through `StorageFactory`.

## Compatibility Notes

- **A workgroup runs a real PostgreSQL container.** `CreateWorkgroup` starts the container (this needs Docker and uses `floci.services.redshift.image-version`) and an authenticating proxy on the `endpoint`, whose port comes from the same pool as provisioned Redshift clusters (`floci.services.redshift.proxy-base-port` to `proxy-max-port`). Connect with any PostgreSQL client or JDBC driver using the namespace admin user and password, to the namespace `dbName`. The Redshift Data API accepts `WorkgroupName` ([details](redshift-data.md)). Only the operations in the Supported Actions table are served. The rest of the Redshift Serverless API is not emulated and answers `UnknownOperationException`: snapshot restore access policies, recovery points, table restore and restore status, the Identity Center token, and lakehouse configuration.
- **Admin credentials come from the namespace.** The backend role is the namespace `adminUsername` (default `admin`) and the password is the `adminUserPassword` the namespace was created with. A namespace created without a password gets a generated one that is kept internally and never returned, so such a workgroup is reachable through `GetCredentials` and the Data API only unless managed credentials are enabled. Changing `adminUserPassword` with `UpdateNamespace` takes effect for new connections and is refused with `ValidationException` if the backend rejects it (AWS password rules: 8 to 64 characters with upper case, lower case and a digit, and none of `' "  / @`). Changing `adminUsername` on a running workgroup does not rename the backend role; the new name is accepted at the proxy, which opens the backend as the original role.
- **`GetCredentials` returns a master-equivalent password.** `dbUser` is derived from the signing identity (`IAM:<user>` or `IAMR:<role>`, falling back to `IAMR:floci` when the caller cannot be resolved), `dbPassword` is random, and `expiration` and `nextRefreshTime` are epoch seconds (the same instant). `durationSeconds` defaults to 900 and must be 900 to 3600. `dbName`, when given, must be the namespace `dbName`. `customDomainName` can be used in place of `workgroupName` to resolve the associated workgroup. A returned credential stays valid until it expires or the workgroup is deleted.
- **The runtime survives a restart when its container does.** On startup Floci re-attaches to the container and restarts the proxy on the same port, so the endpoint is stable. If the container is gone the workgroup comes back with an empty database; if neither can be started, the workgroup stays `AVAILABLE`, the Data API reports that its runtime is not available, and the failure is logged. Containers have no Docker volume, as for provisioned clusters.
- **Managed admin credentials use Secrets Manager.** `CreateNamespace` and `UpdateNamespace` accept `manageAdminPassword=true` to generate a password and create a service-owned secret. The namespace returns `adminPasswordSecretArn` and, when supplied, `adminPasswordSecretKmsKeyId`; the latter requires `manageAdminPassword=true`. The secret contains `engine`, `username`, `password`, `dbname`, and `namespaceName`. `GetNamespace` and `ListNamespaces` return the same references, and the Data API accepts the secret ARN with `WorkgroupName`. Passwords are never returned in namespace responses. Enabling management updates a running backend's password. While credentials are managed, an explicit `adminUserPassword` is rejected; switching to `manageAdminPassword=false` requires a replacement password, updates the backend, and removes the secret. Deleting the namespace also removes its managed secret. Credential changes compensate backend, secret, and namespace writes on failure. The secret reference survives persistence and snapshot restore of the existing namespace. Changing managed-credential settings through `RestoreFromSnapshot` or CloudFormation is not implemented. As for other service-owned secrets in Floci, automatic rotation and `RotateSecret` password replacement are not executed; no successful rotation is reported.
- **Defaults follow AWS.** `dbName` defaults to `dev`, `kmsKeyId` to `AWS_OWNED_KMS_KEY`, `logExports` to an empty list, and `status` to `AVAILABLE` immediately: there is no `MODIFYING` or `CREATING` phase to poll through.
- **`creationDate` is an ISO-8601 string**, for example `2026-09-11T18:12:55.433Z`, not the
  epoch-seconds number that awsJson1.1 uses by default. `Namespace.creationDate` carries
  `TimestampFormatTrait(ISO_8601)` in the API model, and strict SDKs reject a number here even
  though the AWS CLI accepts one. (Not a model-wide rule: roughly half the model's timestamp
  members carry no format trait and use the epoch default, so check each member when extending
  this service.)
- **`namespaceId` is a generated UUID** and `namespaceArn` is `arn:aws:redshift-serverless:<region>:<account>:namespace/<namespaceId>`.
- **`DeleteNamespace` returns the deleted namespace with `status` `DELETING`** and removes it in the same call, so the next `GetNamespace` returns `ResourceNotFoundException`.
- **`UpdateNamespace` applies only the fields present in the request.** An omitted field keeps its stored value; an explicitly empty `iamRoles` or `logExports` array clears it.
- **A namespace has at most one workgroup, and a workgroup belongs to exactly one namespace.** A second `CreateWorkgroup` for a namespace returns `ConflictException`, and so does `DeleteNamespace` while the namespace still has a workgroup. AWS documents the one-to-one relationship; the `DeleteNamespace` refusal is the one rule here that is inferred rather than documented, because the API reference lists `ConflictException` for that call without naming its trigger.
- **Workgroup defaults follow AWS.** `baseCapacity` defaults to 128 RPUs, `port` to 5439, `ipAddressType` to `ipv4`, `trackName` to `current`, `pricePerformanceTarget` to `{"status":"DISABLED"}`, and `enhancedVpcRouting`, `publiclyAccessible` and `extraComputeForAutomaticOptimization` to `false`. `status` is `AVAILABLE` as soon as the call returns; `DeleteWorkgroup` returns the workgroup with `DELETING` and removes it in the same call.
- **Usage limits are stored and echoed, not enforced.** Floci does not meter RPU consumption, so no `breachAction` ever fires and `period` never resets a counter. A limit belongs to a workgroup: `resourceArn` must be a workgroup ARN (`ResourceNotFoundException` otherwise), and `amount` must be a positive integer. Deleting a workgroup removes its limits, and a workgroup recreated under the same name does not inherit them. `UpdateUsageLimit` changes only `amount` and `breachAction`. `CreateUsageLimit` takes no tags in the API model, so usage limits are not taggable here.
- **Capacity, network and security settings are stored and echoed, not enforced.** `baseCapacity`, `maxCapacity`, `subnetIds`, `securityGroupIds`, `enhancedVpcRouting`, `publiclyAccessible` and `configParameters` have no effect on the emulator. `configParameters` returns only what the caller supplied: AWS also adds a set of default parameters that the API reference does not enumerate, and Terraform treats the attribute as computed, so the difference does not produce a plan diff.
- **`port` is validated and echoed.** It must be in 5431-5455 or 8191-8215. The `endpoint.port` is the port Floci allocated, which is what a client connects to.
- **`UpdateWorkgroup` applies only the fields present.** AWS documents that several parameters cannot be changed in one request; Floci accepts any combination. A `trackName` different from the current one is recorded as `pendingTrackName` and `trackName` is unchanged, as AWS switches tracks on the next release. `workgroupName` and `namespaceName` cannot be changed.
- **`workgroupArn` is `arn:aws:redshift-serverless:<region>:<account>:workgroup/<workgroupId>`**, and `creationDate` is an ISO-8601 string like the namespace one. `workgroupVersion`, `patchVersion`, `crossAccountVpcs` and `endpoint.vpcEndpoints` are omitted.
- **Tagging is keyed by `resourceArn`, not by name**, and namespaces and workgroups are the taggable Redshift Serverless resources in Floci. `TagResource` merges into the existing tags rather than replacing them, `UntagResource` removes by key, and any ARN that does not name a known namespace or workgroup in the caller's Region returns `ResourceNotFoundException`. Tags supplied at create are readable through `ListTagsForResource` and survive an update.

## AWS-compatible failures

Every request is checked against the AWS model constraints before dispatch: required members, string lengths and patterns (including resource names and tag keys and values), list sizes (including subnets, security groups, and tag lists), number ranges, and enum values. Floci returns `ValidationException` for a malformed request or one breaking these model constraints, `ConflictException` when a namespace or workgroup name is already taken, when a namespace already has a workgroup, or when a namespace being deleted still has one, and `ResourceNotFoundException` for an unknown namespace or workgroup.

AWS also models `AccessDeniedException`, `InternalServerException`, and `ThrottlingException`. Floci does not inject provider-side failures that cannot be derived from the request or emulator state.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_REDSHIFT_SERVERLESS_ENABLED` | `true` | Enable or disable Redshift Serverless |

Workgroup containers and proxies use the `floci.services.redshift.*` settings: `image-version`, `proxy-base-port` and `proxy-max-port`, `endpoint-host`, `docker-network` and the proxy connection limits.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws redshift-serverless create-namespace \
  --namespace-name analytics \
  --admin-username admin \
  --admin-user-password Secret123! \
  --db-name dev

aws redshift-serverless get-namespace --namespace-name analytics
aws redshift-serverless list-namespaces
aws redshift-serverless update-namespace --namespace-name analytics --log-exports userlog
aws redshift-serverless create-workgroup \
  --workgroup-name analytics-wg \
  --namespace-name analytics \
  --base-capacity 32

aws redshift-serverless get-workgroup --workgroup-name analytics-wg
aws redshift-serverless get-credentials --workgroup-name analytics-wg --db-name dev
aws redshift-data execute-statement --workgroup-name analytics-wg --database dev --sql "select 1"
aws redshift-serverless list-workgroups
aws redshift-serverless update-workgroup --workgroup-name analytics-wg --base-capacity 64

aws redshift-serverless delete-workgroup --workgroup-name analytics-wg
aws redshift-serverless delete-namespace --namespace-name analytics
```
- **Snapshots dump the database.** While the namespace's workgroup is running, `CreateSnapshot` runs `pg_dump` on its container and stores the dump under `<persistent-path>/redshift-serverless-dumps/<account>/<region>/`. `RestoreFromSnapshot` drops every user schema (the catalog bootstrap schema excepted) and replays the dump with `psql`, stopping at the first failing statement, so rows written after the snapshot are gone. The current contents are dumped first and put back if the replay fails, and a restore whose recorded dump file has gone missing fails instead of reporting success. A snapshot taken with no running workgroup is metadata only, and restoring it leaves the database untouched. `DeleteSnapshot` removes the dump. Snapshot ARNs use the snapshot name as their id, snapshot names are 3-255 lowercase letters, digits and hyphens, and `retentionPeriod` is `-1` (indefinite, the default) or 1 to 3653 days. Snapshots are taggable by ARN. `snapshotName` and `snapshotArn` cannot be sent together. Floci's own `floci_internal` bootstrap schema, which feeds the `svv_external_*` views from the Glue catalog, is left out of both the dump and the reset, matching AWS where the Glue catalog is outside a namespace snapshot, so a client schema with that exact name is not captured. A real snapshot expires after its retention period; Floci reports `snapshotRemainingDays` but does not expire snapshots.
- **`UpdateSnapshot` changes only `retentionPeriod`.** It is validated like `CreateSnapshot` (`-1` or 1 to 3653 days), `-1` makes the snapshot indefinite, and an omitted period leaves the snapshot unchanged. `snapshotRemainingDays` keeps counting from the creation time, because Floci stores no separate retention start.
- **Resource policies are recorded, not acted on.** A policy is stored per account and Region, echoed in the shape of the API model, and dropped on read once the snapshot it belongs to is gone. A snapshot recreated under the same name is a different snapshot: the record keeps the snapshot's creation time, so the policy is not inherited. `PutResourcePolicy` accepts a JSON object for a snapshot ARN of this account and Region; no principal in it gains access. A foreign or non-snapshot ARN is a `ValidationException`.
- **Endpoint access is recorded, not acted on.** An endpoint is stored per account and Region, echoed in the shape of the API model, and dropped on read once its workgroup is gone; a workgroup recreated under the same name is a different workgroup and does not inherit it. `CreateEndpointAccess` creates no VPC endpoint. `address` and `port` are the workgroup's own, so a client can connect; `vpcEndpoint` carries a generated id and one network interface per subnet, without a `vpcId`, and `ListEndpointAccess` with a `vpcId` returns nothing. `vpcSecurityGroupIds` is optional. Endpoint names are 1 to 30 letters, digits or hyphens, start with a letter, and contain no consecutive or trailing hyphens. Security groups are the only member `UpdateEndpointAccess` changes.
- **Custom domain associations are recorded, not acted on.** An association is stored per account and Region and dropped on read once its workgroup is gone; a workgroup recreated under the same name does not inherit it. A workgroup has at most one custom domain association. The certificate expiry time comes from ACM when the certificate exists in the emulator.
- **Snapshot copy configurations are recorded, not acted on.** `CreateSnapshotCopyConfiguration` copies no snapshot. A configuration is stored per account and Region and dropped on read once its namespace is gone; a namespace recreated under the same name does not inherit it. The destination Region must differ from the namespace's, and a namespace has only one configuration at a time (`ConflictException`). Delete the existing configuration before choosing another destination Region.
- **Scheduled actions are recorded, never run.** An action is stored per account and Region and dropped on read once its namespace is gone; a namespace recreated under the same name does not inherit it. The owning `namespaceName` and the `targetAction.createSnapshot.namespaceName` must each exist but are not required to match, as the API reference does not say they must. `schedule` holds exactly one of `at` and `cron`; a one-off `at` in the future is the only `nextInvocations` reported, because cron expressions are not evaluated. `startTime`, `endTime` and `at` are epoch seconds.

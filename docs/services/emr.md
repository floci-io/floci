# EMR

**Protocol:** JSON 1.1
**Endpoint:** `http://localhost:4566/`
**Target prefix:** `X-Amz-Target: ElasticMapReduce.*`

Floci emulates the Amazon EMR (Elastic MapReduce) management API. Clusters (job flows), instance groups and fleets, steps, security configurations, and tags are tracked in Floci's storage backend so the AWS CLI and SDK clients can drive the full cluster lifecycle locally. EMR does not launch real Hadoop/Spark clusters. Clusters transition through their lifecycle states as a state machine. In the `docker` runner mode Spark steps, commands and scripts run for real, each in a container of its own, after the cluster's bootstrap actions (see [Running steps](#running-steps)).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `RunJobFlow` | Creates a new cluster (job flow) and returns its `JobFlowId` |
| `DescribeCluster` | Returns the configuration and status of a cluster |
| `ListClusters` | Lists clusters, filterable by state and creation time |
| `TerminateJobFlows` | Terminates one or more clusters |
| `SetTerminationProtection` | Enables or disables termination protection |
| `SetVisibleToAllUsers` | Sets cluster visibility for the account |
| `SetKeepJobFlowAliveWhenNoSteps` | Controls auto-termination when steps complete |
| `SetUnhealthyNodeReplacement` | Toggles unhealthy-node replacement |
| `ModifyCluster` | Updates cluster-level settings such as step concurrency |
| `AddJobFlowSteps` | Adds one or more steps to a cluster |
| `DescribeStep` | Returns the detail and status of a single step |
| `ListSteps` | Lists the steps of a cluster, filterable by state |
| `CancelSteps` | Cancels pending steps |
| `AddInstanceGroups` | Adds instance groups to a running cluster |
| `ListInstanceGroups` | Lists the instance groups of a cluster |
| `AddInstanceFleet` | Adds an instance fleet to a cluster |
| `ListInstanceFleets` | Lists the instance fleets of a cluster |
| `ListInstances` | Lists the EC2 instances of a cluster |
| `CreateSecurityConfiguration` | Creates a named security configuration |
| `DescribeSecurityConfiguration` | Returns a security configuration |
| `DeleteSecurityConfiguration` | Deletes a security configuration |
| `ListSecurityConfigurations` | Lists all security configurations |
| `AddTags` | Adds tags to a cluster |
| `RemoveTags` | Removes tags from a cluster |
| `ModifyInstanceGroups` | Resizes or reconfigures instance groups |
| `ModifyInstanceFleet` | Changes an instance fleet's target capacities |
| `ListBootstrapActions` | Lists the bootstrap actions given to `RunJobFlow` |
| `PutManagedScalingPolicy` | Sets a cluster's managed scaling policy |
| `GetManagedScalingPolicy` | Returns a cluster's managed scaling policy |
| `RemoveManagedScalingPolicy` | Removes a cluster's managed scaling policy |
| `PutAutoTerminationPolicy` | Sets a cluster's idle timeout auto termination policy |
| `GetAutoTerminationPolicy` | Returns a cluster's auto termination policy |
| `RemoveAutoTerminationPolicy` | Removes a cluster's auto termination policy |
| `PutAutoScalingPolicy` | Attaches an automatic scaling policy to a core or task instance group |
| `RemoveAutoScalingPolicy` | Removes an instance group's automatic scaling policy |
| `ListReleaseLabels` | List release labels, newest first, by prefix or application; see [Release catalogue](#release-catalogue) |
| `DescribeReleaseLabel` | A release's applications and Amazon Linux releases (the latest release without a label) |
| `ListSupportedInstanceTypes` | The instance types a release supports, with vCPU, memory, storage and architecture |
| `GetBlockPublicAccessConfiguration` | Returns the account's block public access configuration for the Region |
| `PutBlockPublicAccessConfiguration` | Sets the account's block public access configuration for the Region |
<!-- floci:actions:end -->

## Cluster lifecycle

In the default `immediate` runner mode nothing runs, but a cluster and its steps go through the states
of the Management Guide's cluster lifecycle, each held for as long as the [configuration](#configuration)
says. A cluster is `STARTING` for the first half of `cluster-startup-delay-seconds` and `BOOTSTRAPPING`
for the second, then `RUNNING` while it runs steps and `WAITING` once it has none left. A request's
steps are all created `PENDING`, then start in the order they were added, at most `StepConcurrencyLevel`
at a time, each `RUNNING` for `step-duration-seconds`. Steps added while the cluster starts wait for it
to be ready. Raising the level with `ModifyCluster` starts the steps that now fit at once, and lowering
it lets the running ones complete. `TerminateJobFlows` makes a cluster `TERMINATING` for
`cluster-termination-delay-seconds` and then `TERMINATED`, and cancels any step not yet completed, as its
API reference says. Each call finds the clusters it reads as they stand at that moment. With every
duration at its default of 0, a cluster is ready, and its steps complete, before the call that started
them returns.

`CancelSteps` cancels a `PENDING` step, and a `RUNNING` one from release `emr-5.28.0`, as its API
reference says. A cancelled `RUNNING` step makes room for the next `PENDING` one. Its container stops, and what the
applications it started on YARN have logged goes to `containers/` (see **Output**), while an application in
`--deploy-mode cluster` runs on to its end: EMR's cancel signals the step and its descendant processes, and
"asynchronous processes are not affected", as the Management Guide puts it. On an earlier release
a `RUNNING` step answers `FAILED` with "Step is not in a cancellable state: RUNNING".

A cluster created with `Instances.KeepJobFlowAliveWhenNoSteps` set to `false` terminates after its
initial steps or steps added later complete, reporting `ALL_STEPS_COMPLETED`, without waiting first. A
cluster with no steps remains `WAITING` until steps are added. Set `KeepJobFlowAliveWhenNoSteps` to
`true` to keep the cluster `WAITING` after its steps complete. Automatic step-completion termination
also applies when termination protection is enabled.
`AddJobFlowSteps` rejects a `TERMINATING` or `TERMINATED` cluster without adding steps or changing its
termination status, and answers an unknown cluster with `ValidationException` "Specified job flow ID
does not exist.", as AWS does.
Instance groups and fleets report `RUNNING` from the cluster's creation until it is `TERMINATED`. Then
they report `TERMINATED` with zero running or provisioned capacity; their requested and target
capacities remain recorded. `ListInstances` numbers the synthetic IDs and addresses of the `MASTER`
group's or fleet's instance first, so the primary node keeps `i-00000000000000001` and `10.0.0.1`, as
`DescribeCluster`'s `MasterPublicDnsName` says, while the others resize, and every other instance's private
address is `10.<group>.0.<n>`, its group's or fleet's number in the cluster and its own in it, so it too stays while
another resizes. An instance fleet has an instance for each unit of its capacity, its on-demand ones first, each of the
first instance type in its `InstanceTypeConfigs`. `InstanceGroupId` and `InstanceGroupTypes` keep instance groups'
instances, `InstanceFleetId` and `InstanceFleetType` instance fleets', and a call's filters all apply together.
`ListInstances` retains each group's or fleet's
instances with their original synthetic IDs and `TERMINATED` status for 30 days after termination. New groups or fleets are rejected
on terminated clusters. As on AWS, `ModifyInstanceGroups` answers `ValidationException` "An instance
group may only be modified when the cluster is running or waiting." for a cluster in any other state,
and `ModifyInstanceFleet` answers `InvalidRequestException` "A job flow that is shutting down,
terminated, or finished may not be modified." with `ErrorCode` `INVALID_JOB_SHUT_DOWN` for a
`TERMINATING` or `TERMINATED` one, and "An instance fleet may only be modified when the cluster is
running or waiting." with `ErrorCode` `MODIFY_INSTANCE_FLEET_OF_RUNNING_JOBFLOW_ONLY` for one in any
other state, such as `STARTING` or `BOOTSTRAPPING`. `AddInstanceFleet` gives a `TERMINATING` or
`TERMINATED` cluster the "shutting down" answer, and `AddInstanceGroups` gives it as
`ValidationException`, as `AddJobFlowSteps` does. `InstanceStates` filters the modeled `RUNNING` and
`TERMINATED` instances. As `ListInstances` "Provides information for all active Amazon EC2 instances and Amazon EC2
instances terminated in the last 30 days, up to a maximum of 2,000", a call lists at most 2,000 and gives a `Marker`
for the next; a `Marker` no call gave is refused with `InvalidRequestException`.
A cluster has instance groups or instance fleets, never both, as "instance fleets and instance groups cannot coexist in a
cluster": `RunJobFlow` with both is refused with `ValidationException` "The RunJobFlow request can contain
InstanceFleets parameters or InstanceGroups parameters, but not both.", the API reference's own words, and
`AddInstanceGroups` on a cluster with instance fleets with `ValidationException`, `AddInstanceFleet` on one with
instance groups with `InvalidRequestException`, each saying the cluster uses the other kind; AWS's wording for these is
not documented, so it is Floci's. A fleet without its `InstanceFleetType`, or with an `InstanceTypeConfigs` member
without its `InstanceType`, both required, is refused with the framework's `ValidationException`.

A cluster that is `TERMINATING`, `TERMINATED` or `TERMINATED_WITH_ERRORS` also refuses `ModifyCluster`,
the four `Set*` setters, and `Put` and `Remove` of its managed scaling, auto-termination and automatic
scaling policies, with the same "shutting down" message: `ModifyCluster` as `InvalidRequestException`
with `ErrorCode` `INVALID_JOB_SHUT_DOWN`, since its model declares that exception, and the others as
`ValidationException`, since theirs declare none. `GetManagedScalingPolicy` gives such a cluster that
`ValidationException` in place of its policy, as AWS was recorded doing, and Terraform's provider expects the
same answer from `RemoveManagedScalingPolicy`. For the other operations, which AWS documents for running
clusters, no answer for an ended one is recorded, so theirs is Floci's reading, taken from the operations
whose answer is. A `Set*` request naming such a cluster changes none of the clusters it names. `AddTags` and
`RemoveTags` refuse a `TERMINATED` or `TERMINATED_WITH_ERRORS` cluster, as the Management Guide says "you
cannot add, edit, or remove tags from a terminated cluster", with `InvalidRequestException` "Tags cannot
be modified on terminated clusters." The guide names no error: the message is the one a tagging tool
matches from AWS, and the code is Floci's reading.

`TerminateJobFlows`, the four `Set*` cluster setters and `CancelSteps` refuse a request without a
member their model requires (`JobFlowIds` and the setting, or `StepIds`) with `ValidationException`
"1 validation error detected: Value null at 'jobFlowIds' failed to satisfy constraint: Member must
not be null", AWS's framework wording, as `RunJobFlow` does for `Name`. They used to read a missing
list as empty and a missing setting as its default, and answer success. An empty `JobFlowIds` is
still accepted.

A state machine drives clusters and steps through the `elasticmapreduce:` integrations, `.sync`
included; see [Step Functions: Amazon EMR tasks](step-functions.md#amazon-emr-tasks).

## Running steps

With `runner-mode` set to `docker` (`FLOCI_SERVICES_EMR_RUNNER_MODE=docker`), a step runs. Floci starts
one container per step from the cluster's release image, the one EMR Serverless's `docker` mode uses
(`public.ecr.aws/emr-serverless/spark/<release>`), and runs the step in it: a Spark step's
`spark-submit` on the YARN its primary node runs (see **YARN**), or in local mode where it runs none, the container
holding the driver and its executors, or a command or a script. The cluster itself goes through its lifecycle as in the
`immediate` mode, but for its bootstrap actions and its primary node (see **Storage**).

* **Which steps run**: a `command-runner.jar` step whose first argument is `spark-submit`, the way the
  Management Guide writes Spark steps, runs the arguments after it, with its `--master` and `--deploy-mode` as
  given where the cluster runs YARN (see **YARN**) and without them where it does not. A custom JAR step runs its `MainClass`, or else the one its manifest names, through
  `spark-submit`, with the JAR and the step's `Args`; a MapReduce job it starts, such as the Management
  Guide's `wordcount` of Hadoop's examples JAR, runs on the cluster's YARN, or in local mode where it runs none (see
  **Hadoop streaming and MapReduce**).
  A `Jar` of `/usr/lib/hadoop-mapreduce/hadoop-streaming.jar`, where EMR has it, runs the image's
  `/usr/lib/hadoop/hadoop-streaming.jar`. Any other `command-runner.jar` step runs its
  command, its first argument, with the rest, and a `script-runner.jar` step (by name, or by its
  `s3://<region>.elasticmapreduce/libs/script-runner/script-runner.jar` URI) its script, as the Release
  Guide's "Run commands and scripts on an Amazon EMR cluster" describes them. An `s3-dist-cp` step runs
  Floci's own S3DistCp (see **S3DistCp**), and `hadoop-streaming` and `hadoop-lzo` steps the image's
  Hadoop (see **Hadoop streaming and MapReduce**). On a cluster whose `Applications` name Hive, or HCatalog, with
  which EMR installs Hive's client too, a Hive step runs Hive (see **Hive steps**); on one with neither, `hive-script`
  runs in the release's image, and says what EMR's does without Hive, "Hive isn't installed.", and the step fails. An
  `emr-spark-*` release includes neither ("This release includes the following applications: AmazonCloudWatchAgent,
  Delta, Hudi, Iceberg, JupyterEnterpriseGateway, Livy, and Spark."), so a cluster of one has neither, whatever its
  `Applications` name: no image with Hive, no metastore service. `pig-script`, which the image does not have, is
  `FAILED` without running.
* **Commands and scripts**: they run as the image's `hadoop` user in `/home/hadoop`, with `bash`, Python
  3, Java, `curl` and the AWS CLI v2, which reaches Floci through `AWS_ENDPOINT_URL`. A
  `script-runner.jar` step's script is fetched from S3 with the AWS CLI and the step's credentials, then
  run with the step's other arguments; a path not in S3 is run as it is, a bare name from `/home/hadoop`
  when a file there has it, one a former step left there included. `hadoop fs` and `hdfs dfs` reach the
  cluster's HDFS (see **Storage**) and Floci's S3. A `spark-submit` they start runs on the cluster's YARN, or in local
  mode where it runs none, on Floci's S3 with the
  cluster's `spark-defaults`, `core-site`, `spark-hive-site` and `spark-log4j2`, which Floci puts in a Spark
  configuration of its own, a copy of the folder a `spark-env` export of `SPARK_CONF_DIR` names, else of the
  image's, ahead of the image's EMR Serverless master; where the cluster runs no YARN, its own `--master yarn` or
  `--deploy-mode cluster` fails.
* **Storage**: a cluster's primary node keeps its disks while the cluster lives, and so does Floci: each step's
  container mounts the cluster's two Docker volumes, one at `/home/hadoop` and one at `/mnt`, so what a step leaves
  there is there for the steps after it, and for steps running at the same time. What the bootstrap actions left in
  `/home/hadoop` or `/mnt` comes with the image the steps run in, and Docker copies it into the volumes when the
  first step starts. Every cluster runs HDFS, "best used for caching the results produced by intermediate job-flow
  steps", as the Management Guide puts it: a NameNode and a DataNode on its primary node, which Floci runs in a
  container of their own, `emr-<cluster-id>-hdfs`, from the image the cluster's steps run in where it runs YARN (see
  **YARN**), else from the release's EMR Serverless Hive image, which has HDFS
  where the release's image has only its client (`hive-image-template` names another), or, for an `emr-spark-*`
  release, which has no Hive image, from `emr-7.5.0`'s, with their data in a third volume and permissions off. Where
  the cluster was created with core nodes the DataNodes are theirs instead (see **YARN**), and HDFS keeps what EMR's does, one copy
  of a block below four core nodes, two below ten and three otherwise, or what the `hdfs-site` classification's
  `dfs.replication` says, which is the one property of that classification Floci applies. On a cluster with Hive or HCatalog (see **Which steps run**), the same container runs Hive's
  metastore service, which EMR's HCatalog server is, on port 9083, its Derby database in that volume and its warehouse
  in the HDFS; it reaches the S3 locations of tables through Floci's S3 as the role of the cluster's EC2 instance
  profile, as the bootstrap actions do, with a session that goes with the container. It gets the cluster's `core-site`
  and `hive-site` after its own
  settings, as EMR's primary node does, so a `hive-site` setting of `hive.metastore.warehouse.dir`, say, holds for the
  service as for the Hive steps. A primary node with the service that an earlier Floci left running starts again, on
  the data its volume keeps, as its session went with that Floci. The container is on a Docker network of the cluster's own, also
  `emr-<cluster-id>-hdfs`, which the cluster's steps join besides their own, so the steps of other clusters, another
  account's included, cannot reach its files or tables; each cluster whose HDFS runs takes one network from the
  Docker daemon's address pools. When Floci runs in a container, that container joins the network of each cluster
  with Hive too, as `floci`, which is how the metastore reaches Floci's S3, and leaves it when the HDFS container
  goes. The cluster's first step starts it, and waits for it: a step whose cluster's HDFS is not ready within two
  minutes fails ("The step could not start: The HDFS of EMR cluster ... did not start within 120 seconds"). If its
  NameNode, DataNode or metastore exits, its container ends with it, and the cluster's next step starts it again on
  its data. Their warnings and errors are the container's output, which `docker logs` shows. With `docker-network`
  set to `host`, steps reach it on the host's loopback, and its daemons listen there only, as anyone reaching them
  could change its files, on the host's own ports (8020, 9870, 9866, 9864, 9867), so only one cluster's HDFS runs at
  a time. With `container:<id>`, its container joins that namespace as the steps do, and listens on its loopback in
  the same way; the primary node's name goes in the hosts file that namespace's containers share, `<id>`'s own
  included. In both, the metastore service does not run: Hive 3's has no setting for the address it listens on, so
  it would take 9083 on every interface of the namespace, the host's own for `host`, and Hive steps keep a metastore
  of their own (see **Hive steps**). With `none`, steps have no network, so the cluster runs no HDFS, and its steps
  run as without one. Steps reach
  it by the primary node's `PrivateDnsName`, as `ListInstances` gives it (`ip-10-0-0-1.ec2.internal`, say), on port
  8020: `hdfs://` paths without a host, and paths without a scheme ("hdfs:// (or no prefix)"), lead there through
  `fs.defaultFS`, a relative path under `/user/hadoop`, and the cluster's `spark-defaults` and `core-site` can set
  it otherwise. A command's `hadoop fs` gets the same, through a copy of the image's Hadoop configuration, or of the
  folder a `spark-env` export of `HADOOP_CONF_DIR` names, with the cluster's `core-site` after it, which wins; the
  image's `hdfs` command cannot run, so `hdfs dfs` runs as `hadoop fs`, and its other commands fail. A structured
  streaming query keeps its checkpoints through Spark's file system checkpoint manager, as its default one refuses
  an `hdfs://` path without a host. Where the cluster runs no YARN, in a command's Hadoop configuration, which a custom
  JAR's MapReduce in the driver reads too, the `hdfs` scheme is a view of that HDFS in which one folder, `/tmp/hadoop/mapred/staging`, is
  the container's own, for MapReduce in local mode (see **Hadoop streaming and MapReduce**); Spark and Hive keep
  HDFS's own client, so a Spark step can write a Hive table there. The HDFS container, its network and the volumes
  go once the cluster has ended and its last step is done ("HDFS is ephemeral storage that is reclaimed when you terminate a
  cluster"), on a state reset, and, for clusters that are gone or have ended, when the EMR service starts, unless
  `reconcile-containers-on-startup` is off. A shutdown of Floci removes the HDFS containers of the clusters that are
  still up, and their next step starts them again, on the data their volume keeps. `/tmp` is each container's own.
* **YARN**: on a Docker network of the cluster's own, the default, "the primary node runs the YARN ResourceManager
  service", as the Management Guide puts it, in the primary node's container, with a NodeManager beside it on a cluster
  created with no core node, which keeps it, and its DataNode, as core nodes are added, the blocks it holds staying in
  reach, and the cluster's Spark, MapReduce (see **Hadoop streaming and MapReduce**) and Hive's Tez (see **Hive steps**)
  run on them. A
  Spark step or custom JAR step submits to it, with `--master yarn` unless it names another master, and in the deploy
  mode it asks for, `client` when it names none, and so does a `spark-submit` a command or script starts. In client
  mode the driver stays in the step's container and its output in the step's logs, and the executors run in YARN's
  containers on the cluster's nodes; in cluster mode the driver runs on one of them too, its output in the application's
  container logs (see **Output**) and only the client's in the step's.
  A node needs Spark, Python and what the bootstrap actions installed, as an EMR cluster's nodes hold every
  application it installs, so each runs from the image the steps run in: the bootstrap's, else the release's EMR
  Serverless image with the HDFS, MapReduce and YARN of its EMR Serverless Hive image, and Hive on a cluster with
  Hive, which Floci makes the first time one of the release's clusters needs it (see **Hive steps**), and in which the
  cluster's bootstrap actions run. An `emr-spark-*` release has no EMR Serverless Hive image, and `emr-7.5.0`'s HDFS and
  YARN do not run on its newer Hadoop, so its image holds the whole of `emr-7.5.0`'s Hadoop in `/opt`, which only the
  node's daemons run from (see **Known limitations**). EMR sizes a NodeManager, and the executors' memory and cores ("determined by the
  core and task instance types"), by the instance type; Floci's node shares the machine it runs on, so its NodeManager
  offers `yarn-node-memory-mb` and `yarn-node-vcores`, 4096 MB and 4 by default, and an executor is 1 GB and one core
  and a driver 2 GB and one core, which the cluster's `spark-defaults` and a step's own options replace. A container
  larger than the NodeManager offers is refused by YARN, and its step fails. Dynamic allocation is on, as it is on
  EMR, through Spark's shuffle tracking: the images have no external shuffle service. Executors reach Floci's S3 at
  Floci's address on the cluster's network, which Floci's container joins as for the metastore (see **Storage**), over
  HTTP, which Floci serves beside HTTPS with TLS on, so no certificate is involved, with the step's AWS credentials,
  and find the driver at its container's name on that network.
  Each core and task node is a container of its own on that network, `emr-<cluster-id>-node-<group>-<n>`, with its data
  in a volume of its own: "Core nodes run the Data Node daemon", "a core node runs YARN NodeManager daemons, Hadoop
  MapReduce tasks, and Spark executors", and "Task nodes don't run the Data Node daemon". Its name on the network is the
  private DNS name `ListInstances` gives it, which YARN and HDFS know it by, an instance fleet's as an instance group's.
  How many there are follows the instance
  groups' counts or the instance fleets' target capacities, at most `yarn-max-worker-nodes`, 4 by default, core nodes
  first, as each costs 300 to 800 MB while idle; with 0 the cluster runs on its primary node alone. `ModifyInstanceGroups`,
  `AddInstanceGroups`, `ModifyInstanceFleet` and `AddInstanceFleet` reach the nodes of a cluster a step has started them
  on at once, and the first step of any other starts those asked for by then. A new node takes work as soon as it has
  registered, even from a step already running. One no longer wanted is decommissioned, as "both YARN NodeManager and
  HDFS DataNode daemons must be decommissioned", so the steps running on it finish and its blocks move to the nodes that
  stay, within EMR's default timeout of an hour, each node its own, and then it goes with its data; core nodes stop at the copies HDFS
  keeps, as "Amazon EMR will not allow clusters to scale core nodes below `dfs.replication`". A core node's NodeManager
  is decommissioned only once HDFS has moved its blocks, as a decommissioned NodeManager exits, which ends the node's
  container, its DataNode with it. A core node that has gone stays among HDFS's excluded nodes for a minute, until the
  NameNode, told to by Floci, has it dead 40 seconds after it stopped, so the copies of blocks it held never count
  again. A core node a resize brings back under its name in that minute takes no new blocks until it is over. A core
  node that stops before HDFS has moved its blocks starts again on its data, and one a shutdown of Floci cut off while
  it was being decommissioned starts again on its data with the cluster's next step, to be decommissioned then.
* **Hadoop streaming and MapReduce**: on a cluster that runs YARN (see **YARN**), a step's MapReduce runs there, its
  application master and tasks in YARN's containers on the cluster's nodes. A step's `hadoop` command, and so
  `hadoop-streaming`, a `hadoop jar` and `hdfs dfs`, run from the nodes' Hadoop (`/usr/lib`, or the one an `emr-spark-*`
  release keeps in `/opt`), with a `mapred-site.xml` that names YARN, the nodes' folders and the step's AWS environment,
  and an application master of 1 GB and tasks of 768 MB, which the cluster's `mapred-site` replaces. A custom JAR step's
  MapReduce gets the same. Where the cluster runs no YARN, a step's `hadoop` command runs MapReduce in local mode, in
  its own JVM: the image has MapReduce only in Spark's shaded Hadoop client, which
  Floci makes the
  command's MapReduce home (`HADOOP_MAPRED_HOME`, unless a `spark-env` export names one), so it is on the
  command's class path whatever `HADOOP_CLASSPATH` a command sets, and a copy of its `mapred-site.xml` sets
  `mapreduce.framework.name` to `local`. This has run on the `emr-7.5.0` and `emr-7.10.0` images; on another,
  whose Spark jars may be named otherwise, the step's standard error names each jar of the three the MapReduce
  home links that the image lacks, as a job that needs it fails later, on its class path. So a
  `command-runner.jar` step whose first argument is `hadoop-streaming`, which "Submits an Hadoop streaming
  program. In the console and some SDKs, this is a streaming step", as the Release Guide's tools table puts it
  (the AWS CLI's `Type=STREAMING` becomes one), runs the image's `hadoop-streaming.jar` with the step's other
  arguments: its mapper and reducer can come from Floci's S3 through `-files`, `-cacheFile` or
  `-cacheArchive`, and its `-input` and `-output` can be in Floci's S3 or the cluster's HDFS. A job stages its
  files in `/tmp/hadoop/mapred/staging`, a folder of the step's container, then looks for them at that path
  of the default file system, the cluster's HDFS, so there the `hdfs` scheme leads to that folder. A
  `hadoop-lzo` step runs the image's LZO indexer the same way, as does a `hadoop jar` a command or script
  runs. `s3://` and `s3a://` paths reach Floci's S3
  through S3A, with the AWS SDK Spark's own S3A finds, in place of the image's EMRFS, which is not pointed at
  Floci. The image's `log4j.properties` for Hadoop is empty, so Hadoop logs to the step's standard error, from
  `INFO` up, in the layout of the Management Guide's step log lines, and `stderr.gz` holds a failed job's
  reason ("ERROR org.apache.hadoop.streaming.StreamJob (main): Error Launching job : Output directory ...
  already exists"), which EMR writes to the step's `syslog`. A MapReduce job a custom JAR step's
  `spark-submit` starts runs in local mode the same way, in the driver's JVM, as `spark-submit` puts the
  copied Hadoop configuration (`HADOOP_CONF_DIR`) on its class path; with the image's, whose `mapred-site.xml`
  names YARN, the job would wait for it without end. The cluster's `mapred-site` properties, which "Change
  values in the MapReduce application's mapred-site.xml file", come before Floci's `mapreduce.framework.name`
  and staging folder, which win, so a job's own `-D mapreduce.framework.name=yarn` still fails, and
  `mapreduce.application.classpath`, which the Release Guide gives for a custom JAR's dependencies, changes
  nothing, as a job's tasks run in its own JVM. The cluster's `hadoop-log4j` properties, which "Change values
  in Hadoop's log4j.properties file", follow the `log4j.properties` lines, a later key winning, and its
  `hadoop-env` exports, which "Change values in the Hadoop environment for all Hadoop components", start the
  `hadoop-env.sh` the `hadoop` command reads, as given, with no shell expansion, as `spark-env`'s are, ahead
  of the image's own lines, which add to `HADOOP_CLASSPATH` rather than set it, so the Release Guide's
  `HADOOP_CLASSPATH` of `/path/to/my.jar` keeps the LZO codec the image's `core-site.xml` names; a name a
  shell cannot export is left out. A custom JAR step's `spark-submit` reads neither `hadoop-env.sh` nor
  Hadoop's `log4j.properties`, so those two do not reach it: a `hadoop-env` `HADOOP_CLASSPATH`, say, is not on
  its class path, and it logs as Spark does.
* **Hive steps**: an EMR cluster's nodes hold every application it installs, so a cluster whose `Applications` name
  Hive, or HCatalog, which installs Hive's client and metastore service, runs its bootstrap actions and all its steps
  in an image of the release's with Hive added: the release's EMR
  Serverless image with the Hive, Tez, HDFS, MapReduce and YARN of its EMR Serverless Hive image
  (`public.ecr.aws/emr-serverless/hive/<release>`, 2.7 GB unpacked, pulled to make it; `hive-image-template` names
  another). Floci makes it the first time one of the release's clusters needs it: a container of the release's
  image, never started, gets the Hive image's folders, and is committed, as a bootstrap's is, as
  `floci-aws-emr-release:<release>-<hash>`, 0.6 GB more than the release's image, and kept for the release's next
  cluster (`docker image rm` removes it); a newer pull of either image makes another. A failure to make it, a Hive
  image that cannot be pulled say, fails the step that needed it ("The step could not start: ..."), or, for a
  bootstrap, ends the cluster with `INTERNAL_ERROR`. The two images ship different builds of one goodies JAR under
  one name, so Tez keeps the Hive image's, and Hive runs with the Hive image's Hadoop configuration, which puts Tez
  on its class path, while the cluster's other steps keep the release image's. Then a `command-runner.jar` step
  running `hive-script`, as the AWS CLI writes a `Type=Hive` step (`hive-script --run-hive-script --args` and the
  step's `Args`), or `hive` itself, runs Hive: `hive-script` runs `hive` with the arguments after `--args`, as the
  image's `hadoop` user in `/home/hadoop`, on the cluster's storage, its Tez on the cluster's YARN, or, where it runs
  none, in local mode in its JVM, as an [EMR Serverless Hive job run](emr-serverless.md#running-hive-job-runs)'s does.
  The images hold EMR Serverless's build of Tez, whose `tez-site.xml` names its own plugins, so on YARN Hive runs the
  stock classes from the same jars, with an application master of 1 GB and tasks of 768 MB and one virtual core. It gets the cluster's `core-site`,
  `tez-site` and `hive-site` properties, each a `--hiveconf`, then the step's own arguments, so a `--hiveconf` of
  the step's wins over a classification's, as it does over `hive-site.xml`; the cluster's `hive-env` exports are its
  environment. Floci's settings come before them all, and any of them replaces one: S3A on Floci's S3 for `s3://`,
  where Tez runs, the cluster's HDFS, and its metastore. "By default, Hive records metastore information in a
  MySQL database on the primary node's file system", which Floci keeps in a Derby database of the metastore service
  its primary node runs (see **Storage**), so a table one Hive step makes is there for the next, until the cluster
  ends, and any number of Hive steps use it at once. The `hive-site` setting of
  `hive.metastore.client.factory.class` the Release Guide gives for the Glue Data Catalog uses Floci's Glue instead,
  with the cluster's account as its `hive.metastore.glue.catalogid`, which a `hive-site` one replaces. A table
  without a `LOCATION` is in the cluster's HDFS, under `/user/hive/warehouse` ("By default, this is a location in
  HDFS"), which other steps read as `hdfs:///user/hive/warehouse/<table>`, and an `hdfs://` `LOCATION` is there too.
  The cluster's Spark steps use that metastore too, as Spark's default catalog "always connects to a Hive metastore"
  (the Release Guide's "Use an Iceberg cluster with Spark") and its "Using HCatalog" has Spark SQL read a table the
  HCat CLI made, so a Spark step with Hive support reads a Hive step's tables and makes tables Hive steps read; a
  `spark-hive-site` naming the Glue Data Catalog's client factory still uses Floci's Glue. Where that service does not
  run, with `docker-network` set to `host` or `container:<id>`, or to
  `none`, the metastore is an embedded Derby database in the cluster's `/mnt` instead, which only one Hive step at a
  time can use: with `StepConcurrencyLevel` above 1, a Hive step that starts while another runs fails ("Another
  instance of Derby may have already booted the database"), and Spark steps see a Hive step's tables only through
  the Glue Data Catalog. What the bootstrap actions installed is there for every step, Hive's included. A script or
  command that starts `hive` itself gets none of these settings: only a Hive step runs Hive. Hive's own log stays in
  the container.
* **S3DistCp**: the image has no `s3-dist-cp`, so a `command-runner.jar` step whose
  first argument is `s3-dist-cp`, the Release Guide's form, runs Floci's own program instead, through
  `spark-submit` in the step's container, copying through Hadoop's file systems: `--src` and `--dest` take
  S3 paths in Floci's S3, the cluster's HDFS (`hdfs:///output`, or a path without a scheme) or a file of
  the container, a source folder's files keeping their folders under `--dest`. It takes the Release
  Guide's options, `--name=value` or `--name value`, and `-D property=value`. `--srcPattern` and
  `--groupBy` match the whole source path, as Python regular expressions, which share Java's common
  syntax. `--groupBy` concatenates the files whose groups give the same name, which is the groups' text,
  into the folder of the first of them, and refuses a regular expression with no group, as S3DistCp does;
  `--targetSize` breaks a group larger than it into parts named with a number before the extension.
  `--outputCodec` (`gzip`, `gz`, `lzo`, `lzop`, `snappy`, or `none` to decompress) decompresses each
  source by its own extension and replaces that extension with the codec's; without it a file's
  compression stays as it is, and a group keeps the compression its files share, named with that codec's
  extension, or has none when they differ, each file decompressed so that the group is one stream.
  `--deleteOnSuccess` deletes the copied files, but not their folders, once
  all are copied. `--outputManifest` writes gzipped JSON lines (`path`, `baseName`, `srcDir`, `size`) to
  `--dest`; `--previousManifest` leaves out the files it names by `baseName`, and fails the step when it
  is not there unless `--requirePreviousManifest` is `false` (`=false` or `false` after it);
  `--copyFromManifest` copies the files it names. `--srcPrefixesFile` copies the files under each of its
  lines instead of listing `--src`, a line ending in `/` naming that folder alone and any other a prefix as
  S3 lists one (`logs/day` takes in `logs/day-old/`).
  `--s3ServerSideEncryption`, `--storageClass`, `--disableMultipartUpload` and
  `--multipartUploadChunkSize` set S3A's matching settings; `--s3Endpoint` is ignored, as S3 is Floci's. A
  bucket name with an underscore fails the step, as S3DistCp "does not support" one, and so does a copy
  Hadoop fails, leaving what was copied, as S3DistCp does. `--appendToLastFile` puts the new data after
  what a destination file holds, through a copy renamed over it, on every file system, as S3 cannot append
  in place. The copy has a name of its own and goes if the writing fails; the file steps
  aside, under a name of its own too, until the copy has taken its place, and comes back if it cannot, so a
  failed append leaves it as it was. A copy that would write two files to the same path, the manifest included
  (`a.log` and `a.log.gz` with `--outputCodec=gzip`, say), a file over one of its own sources, as with the
  same `--src` and `--dest`, a file outside `--dest`, through a `..` in a manifest's `baseName` or in
  `--outputManifest`, or a file with no name, from a `--groupBy` whose groups matched nothing, say, fails
  before it writes anything. A step's log has one line saying what was copied.
  Floci's own readings, as no source says: the grouped file's folder, `--numberFiles` putting the number
  straight before the file's name, a group's compression without `--outputCodec`, and failing a copy that
  would write over its own output or source, or outside `--dest`.
* **HTTPS**: with [TLS](../configuration/tls.md) on, each step's container, and the bootstrap actions'
  one, also gets Floci's CA bundle as a Java trust store, which every JVM in it uses, as an
  [EMR Serverless job run](emr-serverless.md#running-spark-job-runs)'s do, so Java code reaches Floci
  over HTTPS too, and through AWS's own endpoint names with `FLOCI_DNS_SPOOF_AWS_ENDPOINTS`.
* **Bootstrap actions**: a cluster with `BootstrapActions` is `BOOTSTRAPPING` until they have run, one
  after the other, in one container of the release's image (with Hive added for a cluster with Hive, see
  **Hive steps**), whatever the second half of
  `cluster-startup-delay-seconds`. Each action's script is read from Floci's S3 (an `s3n://` or `s3a://`
  path names the same bucket, and another path is the image's own) and runs with its `Args` in
  `/home/hadoop`, with the credentials of the cluster's `JobFlowRole` instance profile, as a step would.
  "Bootstrap actions execute as the Hadoop user by default", and `sudo` gives them root, but the image
  has no `sudo`: an action runs as root, with a `sudo` that runs its command as it is, and what it leaves
  in `/home/hadoop` is the `hadoop` user's. Once every action has succeeded, the container is kept as an
  image named after the cluster, which its steps run in, so that what the actions installed or wrote is
  there for each of them; the image goes once the cluster has ended and its last step is done. An action
  that exits with another code than 0 ends the cluster: it cancels its steps and goes `TERMINATING`, then
  `TERMINATED_WITH_ERRORS`, with the reason `BOOTSTRAP_FAILURE` and AWS's message "On the master instance
  (i-00000000000000001), bootstrap action 1 returned a non-zero return code", the instance as
  `ListInstances` gives the master. A script that cannot be read ends it the same way, in Floci's own
  words, and a bootstrap that cannot run at all, an image that cannot be pulled say, with
  `INTERNAL_ERROR`. `TerminateJobFlows` stops the actions at once, and a cluster Floci was bootstrapping
  when it stopped ends with `INTERNAL_ERROR` once Floci is back. With a `LogUri`, each action's output
  goes to `stdout.gz` and `stderr.gz` under `<LogUri>/<cluster-id>/node/<instance-id>/bootstrap-actions/<n>/`,
  as the Management Guide's bootstrap failure pages give the folder. The actions run once, as on the
  master, and every node of the cluster runs from the image they leave, so each holds what they installed. Shutdown
  actions do not run, and
  `Status.ErrorDetails` is not given.
* **States**: a step is `RUNNING` from when it starts until its driver or command exits; `step-duration-seconds`
  does not apply. The first step of a release also waits while its image is pulled (3.5 to 4.3 GB
  unpacked; see [`floci.docker.image-pull-timeout-seconds`](../configuration/docker.md)). It is then
  `COMPLETED` on exit code 0, and `FAILED` otherwise. A `FAILED` step's `Status.FailureDetails` has the `Reason`
  "Unknown Error", which the API reference says EMR gives when it cannot find the root cause, and a
  `Message` in Floci's own words, such as "The Spark driver exited with code 1." or "The step's command
  exited with code 1.".
* **ActionOnFailure**: a failed step's action is taken as the API reference and the Management Guide's
  termination protection table give it. `CONTINUE` runs the next step. `TERMINATE_CLUSTER` (or
  `TERMINATE_JOB_FLOW`) ends the cluster, unless it waits after its steps
  (`KeepJobFlowAliveWhenNoSteps`) and is termination protected, when it goes on. `CANCEL_AND_WAIT`
  cancels the `PENDING` steps and leaves the cluster `WAITING`, or ends it when it is set to end after
  its steps. A cluster a step failure ends cancels its other steps and goes `TERMINATING`, then
  `TERMINATED_WITH_ERRORS`, with the reason `STEP_FAILURE` and the message "Shut down as step failed".
  While `StepConcurrencyLevel` is above 1 no action is taken, and with level 1 and other steps still
  running `CANCEL_AND_WAIT` is not either, as the guide says.
* **Cancelling**: `CancelSteps` on a `RUNNING` step, and `TerminateJobFlows`, stop its driver at once; a
  cancelled step takes no `ActionOnFailure`. A step Floci was running when it stopped is `FAILED` once
  Floci is back, and its `ActionOnFailure` is taken then. Floci's shutdown and a state reset stop every
  driver, then the clusters' HDFS, a step not yet at its driver as if its driver were stopped, and start no other
  step; a driver a hard stop left running is removed when the EMR service
  starts, before any step can start, unless `reconcile-containers-on-startup` is off.
* **What `spark-submit` gets**: the step's arguments, after the `spark-defaults` properties of the
  cluster's `Configurations` and its `core-site` and `spark-hive-site` ones as `spark.hadoop.` settings,
  which Spark's Hadoop configuration, and its Hive client's, take as they would `core-site.xml` and
  `hive-site.xml`, so the Management Guide's `hive.metastore.client.factory.class` for the Glue Data Catalog
  applies; a `spark-hive-site` setting wins over the same one in `core-site`, and a `spark.hadoop.` setting
  in `spark-defaults` over both. `s3://` and `s3a://` paths reach Floci's S3 through S3A, on releases
  before 7.10.0 too, where `s3://` would otherwise be EMRFS, so the application and its files can be read
  from there; the Glue Data Catalog client is pointed at Floci's Glue, with the cluster's account as its
  `hive.metastore.glue.catalogid` (from `emr-7.10.0` the client would otherwise ask STS for it), which a
  `spark-hive-site` one replaces for a Data Catalog in another account; and `hdfs://` paths, and those
  without a scheme, at the cluster's HDFS. The cluster's `spark-log4j2` properties, which "Change values in
  Spark's log4j2.properties file", follow the `log4j2.properties` of the folder a `spark-env` export of
  `SPARK_CONF_DIR` names, else of the image's, in a copy of the step's own, a later key winning, so
  `rootLogger.level` set to `warn` quiets Spark's `INFO` lines. Its `HADOOP_CONF_DIR` is the copy of the
  cluster's Hadoop configuration a command's `hadoop` gets, `mapred-site` included (see **Hadoop streaming
  and MapReduce**). Other classifications (`spark-metrics`, `spark-log4j` for releases before 6.8.0,
  `mapred-env` and the rest) are not applied.
* **A step's `Properties`**: "Java properties that are set when the step runs. You can use these properties
  to pass key-value pairs to your main function", as the API Reference puts it. A custom JAR step's are
  system properties of the JVM its main class runs in, which `System.getProperty` reads, passed to
  `spark-submit` in `SPARK_SUBMIT_OPTS` after any Java options a `spark-env` export puts there, so a step's
  own win. A `command-runner.jar` or `script-runner.jar` step's would be those of the runner's own JVM,
  which Floci has none of, and a JVM's system properties do not pass to a process it starts, so they go
  nowhere. This is Floci's reading, as that sentence is the only source.
* **`spark-env`**: the variables the cluster's `spark-env` exports, in its nested `export` classification,
  are in each step's container environment, `PYSPARK_PYTHON` or `JAVA_HOME` say, replacing any of Floci's
  own with the same name. They are set as given, with no shell expansion of a `$VARIABLE` in
  them, and a command or script sees them too, where EMR writes them in `spark-env.sh`, which only Spark's
  own scripts read. The image's `spark-env.sh` gives `PYSPARK_PYTHON`, `PYSPARK_DRIVER_PYTHON`,
  `SPARK_HOME` and `HADOOP_CONF_DIR` only a default, so the exports win there, but a variable it sets
  outright, such as `AWS_STS_REGIONAL_ENDPOINTS`, keeps the image's value in `spark-submit`.
* **Credentials**: the container gets a session for the step's `ExecutionRoleArn`, or else for the role
  of the cluster's `JobFlowRole` instance profile, when IAM knows it and the profile holds one role,
  and otherwise the account's placeholder credentials, with `AWS_ENDPOINT_URL` pointing at Floci.
* **Output**: the driver's output goes to Floci's log, prefixed `emr:<stepId>`. With a `LogUri` on the
  cluster, its standard output and error also go to Floci's S3 once the step ends, gzipped, as
  `stdout.gz` and `stderr.gz` under `<LogUri>/<cluster-id>/steps/<step-id>/`, the step log folder of the
  Management Guide's "Log locations" (an `s3n://` or `s3a://` `LogUri` names the same bucket). A
  cancelled step's logs hold what its driver wrote until it was stopped. Logs that cannot be written,
  to a bucket that does not exist say, leave the step as it ended. On a cluster that runs YARN, the logs of its
  applications' containers, a cluster-mode driver's output among them, go gzipped to
  `<LogUri>/<cluster-id>/containers/<application>/<container>/` when a step ends, "Application container logs. The logs
  for each YARN application are stored in these locations", but for the `launch_container.sh` that holds a container's
  credentials. The `controller` and `syslog` logs
  and the other log folders are not written, nothing is copied while a step runs (EMR copies its logs
  every five minutes), and `FailureDetails` has no `LogFile`.
* **Image**: `image-template` names another image, such as a mirror or a dated tag of the release's
  image: `registry.internal/emr-serverless/spark/%s:20250425`, and `hive-image-template` another Hive image:
  `registry.internal/emr-serverless/hive/%s:20250425`. Every cluster's steps need both, the Hive image for the
  cluster's HDFS (see **Storage**), so a mirror sets both, and carries `emr-7.5.0`'s Hive image for an `emr-spark-*`
  release. A release with no EMR Serverless image, or another with no EMR Serverless Hive image, fails its steps
  with "The step could not start: Could not pull image".

### Known limitations

- **No more nodes than `yarn-max-worker-nodes`.** A cluster that names more core and task instances runs that many,
  core nodes first, while `ListInstances` still lists them all. Its core and task nodes offer the same capacity whatever
  their instance type.
- **An instance fleet's unit of capacity is one instance.** Floci does not choose among a fleet's instance types as EMR
  does: `ListInstances` gives each of its instances the first in its `InstanceTypeConfigs`, and its target capacity is
  divided by no `WeightedCapacity`, so it has an instance and a node for each unit.
- **A resize shows at once.** An instance group is `RUNNING` with its new count, and an instance fleet its new capacity,
  as soon as the resize is asked for, while the nodes take a few seconds to follow, or longer to decommission. A core
  group shrunk below the copies HDFS keeps says the count asked for, while its nodes stop at the copies; EMR documents
  that it does not scale below them, but no error for the request.
- **No YARN node labels.** An application master can run on any node, as on EMR's 6.x releases by default. EMR's 7.x
  releases label nodes by market type and 5.x's keep application masters on core nodes.
- **No nodes for a cluster made with `MasterInstanceType`, `SlaveInstanceType` and `InstanceCount`.** Floci keeps no
  instance groups for that older form of `RunJobFlow`, so such a cluster runs on its primary node alone.
- **An `emr-spark-*` cluster's HDFS and YARN are `emr-7.5.0`'s.** The Release Guide lists both at Hadoop 3.4.2 for
  `emr-spark-8.0.0` and `emr-spark-8.1.0`, and EMR Serverless publishes no image holding them, so the primary node runs
  Hadoop 3.4.0's. Spark and its Hadoop client are the release's own.
- **No YARN in a network namespace the steps share, or with no network.** With `docker-network` set to `host` or
  `container:<id>`, YARN's daemons would listen on every interface of the namespace, and with `none` the steps could not
  reach them, so the cluster's Spark runs in local mode.
- **MapReduce and Tez in local mode where there is no YARN.** Only a cluster that runs YARN (see **YARN**) runs them on
  it; on any other, `hadoop-streaming`, a custom JAR's MapReduce and Hive's Tez run in the step's container.
- **Tez's intermediate data is not compressed on YARN.** The images name the LZO codec for it, whose native library they
  lack, so Floci turns that compression off, which a `tez-site` classification can turn back on with a codec they have.
- **No Hive metastore service in a network namespace the steps share.** With `docker-network` set to `host` or
  `container:<id>`, the primary node runs HDFS but not Hive's metastore service: Hive 3's has no setting for the
  address it listens on, so it would serve its tables, and the files under them, on every interface of the
  namespace, the host's own for `host`. Hive steps there keep an embedded Derby database in the cluster's `/mnt`, so
  only one Hive step at a time can use it, and Spark steps see a Hive step's tables only through the Glue Data
  Catalog. On a cluster's own network, the default, the service runs (see **Storage** and **Hive steps**).

## EventBridge events

Every state a cluster or a step enters is sent to the default EventBridge bus of its Region, in the
account that made the call, as AWS sends it: source `aws.emr`, detail type `EMR Cluster State Change`
or `EMR Step Status Change`, an empty `resources`, and the detail of the EMR Management Guide's
examples. A cluster's detail holds `severity`, `stateChangeReason`, `name`, `clusterId`, `state` and
`message`; a step's holds `severity`, `actionOnFailure`, `stepId`, `name`, `clusterId`, `state` and
`message`. The severity and message are the ones the guide's "Monitoring Amazon EMR events" tables
give each state, with times to the minute (`2026-10-24 09:41 UTC`).

Each change sends its events:

| Change | Events, in order |
|---|---|
| `RunJobFlow` | the cluster `STARTING`, then each step `PENDING` |
| `AddJobFlowSteps` | each step `PENDING` |
| The cluster is ready, or a step completes | the step `COMPLETED`; each step it makes room for `RUNNING`; once none is left, the cluster `WAITING`, or `TERMINATED` with `ALL_STEPS_COMPLETED` when it ends after its steps |
| A step fails (`docker` runner mode) | the step `FAILED`; then as its `ActionOnFailure` goes: as a completed step, or `CANCELLED` for each step it cancels and the cluster `WAITING`, or `CANCELLED` for each step not yet completed and the cluster `TERMINATED_WITH_ERRORS` with `STEP_FAILURE` |
| `TerminateJobFlows` | `CANCELLED` for each step not yet completed; the cluster `TERMINATED` with `USER_REQUEST` once it has been `TERMINATING` for the delay, also when a protected cluster fails the call |
| `CancelSteps` | `CANCELLED` for each step it cancels, and `RUNNING` for the step a cancelled `RUNNING` one makes room for |

A change that falls due between calls, such as a step completing after `step-duration-seconds`, is
made at that time and its events sent then, whether or not anything reads the cluster.

A `STARTING` or `WAITING` cluster's `stateChangeReason` is `{"code":""}`, as in the guide's example,
and a terminated one's carries the reason's code and message, such as
`{"code":"USER_REQUEST","message":"Terminated by user request"}`. `TERMINATED` is `INFO` for
`USER_REQUEST` and `ALL_STEPS_COMPLETED` and `CRITICAL` for any other reason, and
`TERMINATED_WITH_ERRORS` is `CRITICAL`; a cancelled step is `WARN` and a failed one `ERROR`. The tables have no row for a cluster's `BOOTSTRAPPING`, `RUNNING` or `TERMINATING`, which
send nothing, so a `WAITING` cluster that runs added steps sends `WAITING` again when they are done.
The guide gives two `WAITING` messages without saying when each applies: a cluster that ran steps
reports it "finished running all pending steps", one that did not that it "was created ... and is
ready for use".

Events go out in the order their changes were made, once the call that made them no longer holds the
cluster. A rule's target that calls EMR while an event is delivered, such as one ending the cluster on
its `STARTING` event, has its events sent after the rest of that call's.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_EMR_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_EMR_DEFAULT_RELEASE_LABEL` | `emr-7.5.0` | The release label of a cluster created without one; see [Release catalogue](#release-catalogue) |
| `FLOCI_SERVICES_EMR_CLUSTER_STARTUP_DELAY_SECONDS` | `0` | Seconds a new cluster takes to be ready to run steps, the first half `STARTING` and the second `BOOTSTRAPPING`; `0` makes it ready as soon as it is created |
| `FLOCI_SERVICES_EMR_STEP_DURATION_SECONDS` | `0` | Seconds a step stays `RUNNING` before it completes; `0` completes it as soon as it starts |
| `FLOCI_SERVICES_EMR_CLUSTER_TERMINATION_DELAY_SECONDS` | `0` | Seconds a cluster stays `TERMINATING` before it is `TERMINATED`; `0` ends it at once |
| `FLOCI_SERVICES_EMR_RUNNER_MODE` | `immediate` | `immediate` completes steps by time without running anything; `docker` runs each step in a container, after the cluster's bootstrap actions (see [Running steps](#running-steps)) |
| `FLOCI_SERVICES_EMR_DOCKER_NETWORK` | the global default | Docker network for step containers |
| `FLOCI_SERVICES_EMR_IMAGE_TEMPLATE` | `public.ecr.aws/emr-serverless/spark/%s:latest`, or `spark4/` in place of `spark/` for an `emr-spark-*` release | Image for a `docker` step, `%s` standing for the cluster's release label; a mirror sets `FLOCI_SERVICES_EMR_HIVE_IMAGE_TEMPLATE` too |
| `FLOCI_SERVICES_EMR_HIVE_IMAGE_TEMPLATE` | `public.ecr.aws/emr-serverless/hive/%s:latest` | The EMR Serverless Hive image a `docker` cluster takes HDFS and YARN from, and a cluster with Hive takes Hive from, `%s` standing for the cluster's release label, or `emr-7.5.0` for the Hadoop of an `emr-spark-*` release; see [Storage and Hive steps](#running-steps) |
| `FLOCI_SERVICES_EMR_YARN_NODE_MEMORY_MB` | `4096` | The memory a `docker` cluster's NodeManager offers the YARN containers its Spark executors run in (see [Running steps](#running-steps)) |
| `FLOCI_SERVICES_EMR_YARN_NODE_VCORES` | `4` | The virtual cores it offers them |
| `FLOCI_SERVICES_EMR_YARN_MAX_WORKER_NODES` | `4` | The most core and task nodes a `docker` cluster runs as containers of their own, core nodes first; 0 keeps a cluster on its primary node |
| `FLOCI_SERVICES_EMR_RECONCILE_CONTAINERS_ON_STARTUP` | `true` | Remove the step drivers and bootstrap containers a previous run of this Floci left on the Docker daemon, and the images bootstrap actions left for clusters that are gone or have ended, when the EMR service starts; turn it off when two Floci instances share a daemon with the same port and no resource namespace |

## Release catalogue

`ListReleaseLabels`, `DescribeReleaseLabel` and `ListSupportedInstanceTypes` read a curated catalogue,
not AWS's full list: `emr-7.5.0` (Floci's default), `emr-7.4.0`, `emr-6.15.0`, `emr-6.14.0` and
`emr-5.36.2`. Each release's applications and versions come from its page in the Amazon EMR Release
Guide, with names lowercased and AWS's `-amzn-N` build suffix dropped, as the API reports them, and
its two most recent Amazon Linux releases. A `floci.services.emr.default-release-label` outside the
catalogue is still listed, with no applications.

`ListReleaseLabels` filters by `Prefix` (such as `emr-6`) and `Application` (`spark` or
`spark@3.4.1`, matched without regard to case) and pages with `MaxResults` (1 to 100) /
`NextToken`; a token only works with the filters it was issued for. `DescribeReleaseLabel` answers
every application at once, since AWS reserves its `MaxResults` and `NextToken` for future use.
`ListSupportedInstanceTypes` reports common families (c5, m5, m5d, m6g, r5, r6g) with
their EC2 sizes. An unknown release label, a bad token or an out-of-range `MaxResults` is
`InvalidRequestException`. `RunJobFlow` does not check its release label or instance types against
the catalogue.

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create a cluster
CLUSTER_ID=$(aws emr run-job-flow \
  --name "demo-cluster" \
  --release-label emr-7.0.0 \
  --instances KeepJobFlowAliveWhenNoSteps=true,InstanceGroups='[{InstanceCount=1,InstanceGroupType=MASTER,InstanceType=m5.xlarge}]' \
  --query 'JobFlowId' --output text)

# Inspect it
aws emr describe-cluster --cluster-id "$CLUSTER_ID"
aws emr list-clusters

# Add a step
aws emr add-steps --cluster-id "$CLUSTER_ID" \
  --steps Type=CUSTOM_JAR,Name=demo,Jar=command-runner.jar,Args=[echo,hello]

# Terminate
aws emr terminate-clusters --cluster-ids "$CLUSTER_ID"
```

`aws emr add-steps --execution-role-arn` applies the role to every step in that request.
`RunJobFlow` also applies `StepExecutionRoleArn` to its initial steps. `describe-step`
includes `ExecutionRoleArn` for those steps; `list-steps` returns step summaries without it.

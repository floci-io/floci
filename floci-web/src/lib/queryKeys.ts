export const qk = {
  buckets: () => ['s3', 'buckets'] as const,
  bucketLocation: (bucket: string) => ['s3', 'bucket-location', bucket] as const,
  objects: (bucket: string, prefix: string) =>
    ['s3', 'objects', bucket, prefix] as const,
  objectDetail: (bucket: string, key: string) =>
    ['s3', 'object-detail', bucket, key] as const,
  versions: (bucket: string, prefix: string) =>
    ['s3', 'versions', bucket, prefix] as const,
  versioning: (bucket: string) => ['s3', 'versioning', bucket] as const,
  multipart: (bucket: string) => ['s3', 'multipart', bucket] as const,
  bucketTags: (bucket: string) => ['s3', 'bucket-tags', bucket] as const,
  objectTags: (bucket: string, key: string) =>
    ['s3', 'object-tags', bucket, key] as const,
  cors: (bucket: string) => ['s3', 'cors', bucket] as const,
  lifecycle: (bucket: string) => ['s3', 'lifecycle', bucket] as const,
  policy: (bucket: string) => ['s3', 'policy', bucket] as const,
  encryption: (bucket: string) => ['s3', 'encryption', bucket] as const,
  objectLock: (bucket: string) => ['s3', 'object-lock', bucket] as const,
  publicAccessBlock: (bucket: string) =>
    ['s3', 'public-access-block', bucket] as const,
  notifications: (bucket: string) => ['s3', 'notifications', bucket] as const,
  objectAcl: (bucket: string, key: string) =>
    ['s3', 'object-acl', bucket, key] as const,
  retention: (bucket: string, key: string) =>
    ['s3', 'retention', bucket, key] as const,
  legalHold: (bucket: string, key: string) =>
    ['s3', 'legal-hold', bucket, key] as const,
};

export const rdsKeys = {
  instances: () => ['rds', 'instances'] as const,
  instance: (id: string) => ['rds', 'instance', id] as const,
  clusters: () => ['rds', 'clusters'] as const,
  cluster: (id: string) => ['rds', 'cluster', id] as const,
  parameterGroups: () => ['rds', 'parameter-groups'] as const,
  parameterGroupParameters: (name: string) =>
    ['rds', 'parameter-group', name, 'parameters'] as const,
};

export const ec2Keys = {
  instances: () => ['ec2', 'instances'] as const,
  instance: (id: string) => ['ec2', 'instance', id] as const,
  images: () => ['ec2', 'images'] as const,
  keyPairs: () => ['ec2', 'key-pairs'] as const,
  securityGroups: () => ['ec2', 'security-groups'] as const,
  securityGroup: (id: string) => ['ec2', 'security-group', id] as const,
  vpcs: () => ['ec2', 'vpcs'] as const,
  subnets: () => ['ec2', 'subnets'] as const,
  internetGateways: () => ['ec2', 'internet-gateways'] as const,
  routeTables: () => ['ec2', 'route-tables'] as const,
  routeTable: (id: string) => ['ec2', 'route-table', id] as const,
  elasticIps: () => ['ec2', 'elastic-ips'] as const,
  volumes: () => ['ec2', 'volumes'] as const,
  availabilityZones: () => ['ec2', 'availability-zones'] as const,
  instanceTypes: () => ['ec2', 'instance-types'] as const,
};

export const lambdaKeys = {
  functions: () => ['lambda', 'functions'] as const,
  function: (name: string) => ['lambda', 'function', name] as const,
  versions: (name: string) => ['lambda', 'versions', name] as const,
  aliases: (name: string) => ['lambda', 'aliases', name] as const,
  esm: (name?: string) =>
    ['lambda', 'event-source-mappings', name ?? ''] as const,
  policy: (name: string) => ['lambda', 'policy', name] as const,
  url: (name: string) => ['lambda', 'url', name] as const,
  concurrency: (name: string) => ['lambda', 'concurrency', name] as const,
  tags: (resource: string) => ['lambda', 'tags', resource] as const,
};

export const schedulerKeys = {
  schedules: (groupName?: string) =>
    ['scheduler', 'schedules', groupName ?? ''] as const,
  schedule: (name: string, groupName: string) =>
    ['scheduler', 'schedule', groupName, name] as const,
  groups: () => ['scheduler', 'groups'] as const,
  group: (name: string) => ['scheduler', 'group', name] as const,
  groupTags: (arn: string) => ['scheduler', 'group-tags', arn] as const,
};

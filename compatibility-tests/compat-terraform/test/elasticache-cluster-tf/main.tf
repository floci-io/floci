resource "aws_elasticache_replication_group" "cluster" {
  replication_group_id       = "floci-tf-valkey-cluster"
  description                = "floci terraform cluster-mode compatibility test"
  engine                     = "valkey"
  node_type                  = "cache.t4g.micro"
  parameter_group_name       = "default.valkey8.cluster.on"
  automatic_failover_enabled = true
  num_node_groups            = 2
  replicas_per_node_group    = 1
}

output "cluster_enabled" {
  value = aws_elasticache_replication_group.cluster.cluster_enabled
}

output "configuration_endpoint_address" {
  value = aws_elasticache_replication_group.cluster.configuration_endpoint_address
}

output "num_node_groups" {
  value = aws_elasticache_replication_group.cluster.num_node_groups
}

output "member_clusters" {
  value = aws_elasticache_replication_group.cluster.member_clusters
}

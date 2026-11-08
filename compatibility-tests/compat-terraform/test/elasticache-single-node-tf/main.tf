resource "aws_elasticache_cluster" "single_node" {
  cluster_id           = "floci-tf-redis-single"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.t4g.micro"
  num_cache_nodes      = 1
  port                 = 6396
  parameter_group_name = "default.redis7"

  snapshot_retention_limit = 5
  snapshot_window          = "03:00-05:00"
  maintenance_window       = "tue:04:00-tue:05:00"
}

output "cluster_id" {
  value = aws_elasticache_cluster.single_node.cluster_id
}

output "engine" {
  value = aws_elasticache_cluster.single_node.engine
}

output "port" {
  value = aws_elasticache_cluster.single_node.port
}

output "cache_node_address" {
  value = aws_elasticache_cluster.single_node.cache_nodes[0].address
}

output "cache_node_port" {
  value = aws_elasticache_cluster.single_node.cache_nodes[0].port
}

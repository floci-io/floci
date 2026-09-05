resource "aws_neptune_cluster" "cluster" {
  cluster_identifier                  = "floci-tf-neptune"
  engine                              = "neptune"
  backup_retention_period             = 5
  preferred_backup_window             = "07:00-09:00"
  iam_database_authentication_enabled = true
  deletion_protection                 = false
  skip_final_snapshot                 = true
  apply_immediately                   = true

  tags = {
    Name = "floci-tf-neptune"
  }
}

output "cluster_arn" {
  value = aws_neptune_cluster.cluster.arn
}

output "cluster_resource_id" {
  value = aws_neptune_cluster.cluster.cluster_resource_id
}

output "endpoint" {
  value = aws_neptune_cluster.cluster.endpoint
}

output "port" {
  value = aws_neptune_cluster.cluster.port
}

output "storage_encrypted" {
  value = aws_neptune_cluster.cluster.storage_encrypted
}

output "parameter_group_name" {
  value = aws_neptune_cluster.cluster.neptune_cluster_parameter_group_name
}

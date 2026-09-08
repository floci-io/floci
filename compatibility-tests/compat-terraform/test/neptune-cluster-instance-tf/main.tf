resource "aws_neptune_cluster" "cluster" {
  cluster_identifier  = "floci-tf-neptune-instances"
  engine              = "neptune"
  port                = 8183
  skip_final_snapshot = true
  apply_immediately   = true
}

resource "aws_neptune_cluster_instance" "writer" {
  identifier         = "floci-tf-neptune-writer"
  cluster_identifier = aws_neptune_cluster.cluster.id
  instance_class     = "db.r5.large"
  engine             = "neptune"
  port               = 8183
  apply_immediately  = true

  tags = {
    Name = "floci-tf-neptune-writer"
  }
}

resource "aws_neptune_cluster_instance" "reader" {
  identifier                 = "floci-tf-neptune-reader"
  cluster_identifier         = aws_neptune_cluster.cluster.id
  instance_class             = "db.r5.large"
  engine                     = "neptune"
  port                       = 8183
  promotion_tier             = 2
  auto_minor_version_upgrade = false
  apply_immediately          = true

  depends_on = [aws_neptune_cluster_instance.writer]
}

output "writer_arn" {
  value = aws_neptune_cluster_instance.writer.arn
}

output "writer_endpoint" {
  value = aws_neptune_cluster_instance.writer.endpoint
}

output "writer_port" {
  value = aws_neptune_cluster_instance.writer.port
}

output "writer_is_writer" {
  value = aws_neptune_cluster_instance.writer.writer
}

output "writer_storage_encrypted" {
  value = aws_neptune_cluster_instance.writer.storage_encrypted
}

output "writer_parameter_group_name" {
  value = aws_neptune_cluster_instance.writer.neptune_parameter_group_name
}

output "writer_auto_minor_version_upgrade" {
  value = aws_neptune_cluster_instance.writer.auto_minor_version_upgrade
}

output "reader_is_writer" {
  value = aws_neptune_cluster_instance.reader.writer
}

output "reader_promotion_tier" {
  value = aws_neptune_cluster_instance.reader.promotion_tier
}

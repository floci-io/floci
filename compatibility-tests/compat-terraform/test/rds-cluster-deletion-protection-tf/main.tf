variable "deletion_protection" {
  type    = bool
  default = true
}

resource "aws_rds_cluster" "protected" {
  cluster_identifier  = "floci-rds-cluster-deletion-protection"
  engine              = "aurora-postgresql"
  engine_version      = "16.3"
  master_username     = "app"
  master_password     = "changeme123!"
  skip_final_snapshot = true
  deletion_protection = var.deletion_protection
}

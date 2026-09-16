# Regression coverage for #2266 at the Terraform layer.
#
# "Allow traffic from security group X" is how one tier is wired to another -- an ALB
# to its backend tasks, an application to its database, a cluster to itself. When
# AuthorizeSecurityGroupIngress dropped the source, the provider's create-wait could
# not find the rule it had just made and the apply failed with
# "couldn't find resource"; a response that reported the rule but lost the reference
# would instead apply cleanly and then show as drift on every subsequent plan.
#
# Both of the standard spellings are exercised, because they read the source back
# through different APIs: aws_security_group_rule polls DescribeSecurityGroups, and
# aws_vpc_security_group_ingress_rule polls DescribeSecurityGroupRules.

resource "aws_vpc" "sg_sources" {
  cidr_block = "10.71.0.0/16"

  tags = {
    Name = "floci-sg-rule-sources"
  }
}

resource "aws_security_group" "alb" {
  name        = "floci-sg-rule-sources-alb"
  description = "Front tier"
  vpc_id      = aws_vpc.sg_sources.id
}

resource "aws_security_group" "backend" {
  name        = "floci-sg-rule-sources-backend"
  description = "Back tier, reachable only from the front tier"
  vpc_id      = aws_vpc.sg_sources.id
}

# The classic spelling: reads its source back off DescribeSecurityGroups.
resource "aws_security_group_rule" "backend_from_alb" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  security_group_id        = aws_security_group.backend.id
  source_security_group_id = aws_security_group.alb.id
  description              = "app traffic from the load balancer"
}

# Self-reference: a cluster whose members talk to each other. This is the shape that
# fails first, because the group is both the rule's owner and its source.
resource "aws_security_group_rule" "backend_self" {
  type              = "ingress"
  from_port         = 0
  to_port           = 0
  protocol          = "-1"
  security_group_id = aws_security_group.backend.id
  self              = true
  description       = "intra-cluster traffic"
}

# The current spelling: reads its source back off DescribeSecurityGroupRules, and
# needs referencedGroupInfo rather than a UserIdGroupPair.
resource "aws_vpc_security_group_ingress_rule" "backend_from_alb_v2" {
  security_group_id            = aws_security_group.backend.id
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 9090
  to_port                      = 9090
  ip_protocol                  = "tcp"
  description                  = "admin traffic from the load balancer"
}

# A CIDR rule alongside them: the reference must not be lost when a group carries
# both kinds, and a CIDR rule must not sprout a phantom reference.
resource "aws_vpc_security_group_ingress_rule" "backend_from_cidr" {
  security_group_id = aws_security_group.backend.id
  cidr_ipv4         = "10.71.0.0/16"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "https from inside the vpc"
}

output "alb_sg_id" {
  value = aws_security_group.alb.id
}

output "backend_sg_id" {
  value = aws_security_group.backend.id
}

output "backend_from_alb_v2_rule_id" {
  value = aws_vpc_security_group_ingress_rule.backend_from_alb_v2.security_group_rule_id
}

output "backend_from_cidr_rule_id" {
  value = aws_vpc_security_group_ingress_rule.backend_from_cidr.security_group_rule_id
}

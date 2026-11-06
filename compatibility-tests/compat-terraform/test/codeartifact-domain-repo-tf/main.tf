# Verifies the CodeArtifact control plane: domain/repository CRUD, tags, upstream repositories,
# and resource policies, all of which previously had no Floci support at all.

resource "aws_codeartifact_domain" "test" {
  domain = "floci-codeartifact-domain"

  tags = {
    owner = "platform"
  }
}

data "aws_iam_policy_document" "domain_policy" {
  statement {
    effect    = "Allow"
    actions   = ["codeartifact:GetDomainPermissions"]
    resources = [aws_codeartifact_domain.test.arn]
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
  }
}

resource "aws_codeartifact_domain_permissions_policy" "test" {
  domain          = aws_codeartifact_domain.test.domain
  policy_document = data.aws_iam_policy_document.domain_policy.json
}

resource "aws_codeartifact_repository" "store" {
  domain     = aws_codeartifact_domain.test.domain
  repository = "floci-codeartifact-store"
}

resource "aws_codeartifact_repository" "consumer" {
  domain      = aws_codeartifact_domain.test.domain
  repository  = "floci-codeartifact-consumer"
  description = "consumes packages via the store repository"

  upstream {
    repository_name = aws_codeartifact_repository.store.repository
  }

  tags = {
    team = "data"
  }
}

data "aws_iam_policy_document" "repository_policy" {
  statement {
    effect    = "Allow"
    actions   = ["codeartifact:ReadFromRepository"]
    resources = [aws_codeartifact_repository.consumer.arn]
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
  }
}

resource "aws_codeartifact_repository_permissions_policy" "test" {
  domain          = aws_codeartifact_domain.test.domain
  repository      = aws_codeartifact_repository.consumer.repository
  policy_document = data.aws_iam_policy_document.repository_policy.json
}

output "domain_arn" {
  value = aws_codeartifact_domain.test.arn
}

output "domain_owner_tag" {
  value = aws_codeartifact_domain.test.tags["owner"]
}

output "repository_arn" {
  value = aws_codeartifact_repository.consumer.arn
}

output "repository_upstream" {
  value = aws_codeartifact_repository.consumer.upstream[0].repository_name
}

output "repository_team_tag" {
  value = aws_codeartifact_repository.consumer.tags["team"]
}

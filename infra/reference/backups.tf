# Database backups: a private S3 bucket the nodes may write to and read from, but not delete from.
# Backups expire after 14 days by lifecycle rule, so a compromised cluster cannot wipe them early.
# The bucket name is stored in Secrets Manager (pagerewind/backup) for the backup jobs.

resource "random_id" "backups" {
  byte_length = 4
}

resource "aws_s3_bucket" "backups" {
  bucket = "pagerewind-backups-${random_id.backups.hex}"
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    id     = "expire-after-14-days"
    status = "Enabled"
    filter {}
    expiration {
      days = 14
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

data "aws_iam_policy_document" "backups" {
  statement {
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.backups.arn]
  }
  statement {
    # No s3:DeleteObject: old backups only go away by the lifecycle rule.
    actions   = ["s3:PutObject", "s3:GetObject"]
    resources = ["${aws_s3_bucket.backups.arn}/*"]
  }
}

resource "aws_iam_role_policy" "backups" {
  name   = "database-backups"
  role   = module.k3s.node_role_name
  policy = data.aws_iam_policy_document.backups.json
}

resource "aws_secretsmanager_secret" "backup" {
  name                    = "pagerewind/backup"
  description             = "Settings for the database backup jobs"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "backup" {
  secret_id     = aws_secretsmanager_secret.backup.id
  secret_string = jsonencode({ bucket = aws_s3_bucket.backups.id })
}

output "backup_bucket" {
  value = aws_s3_bucket.backups.id
}

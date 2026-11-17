data "aws_caller_identity" "current" {}

module "state_backend" {
  source      = "../modules/state-backend"
  bucket_name = "pagerewind-tfstate-${data.aws_caller_identity.current.account_id}"
}

output "state_bucket" {
  value = module.state_backend.bucket_name
}
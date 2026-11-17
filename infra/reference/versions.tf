terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Bucket and profile come from ../backend.hcl (not committed):
  #   terraform init -backend-config="../backend.hcl"
  backend "s3" {
    key          = "reference/terraform.tfstate"
    region       = "ap-southeast-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region  = "ap-southeast-2"
  profile = "pager"

  default_tags {
    tags = {
      Project   = "pagerewind"
      ManagedBy = "terraform"
    }
  }
}
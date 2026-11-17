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

  backend "s3" {
    bucket       = "pagerewind-tfstate-715412582959"
    key          = "reference/terraform.tfstate"
    region       = "ap-southeast-2"
    profile      = "pager"
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
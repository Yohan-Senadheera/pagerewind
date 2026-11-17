variable "name" {
  description = "Prefix for resource names"
  type        = string
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  type    = string
  default = "10.0.1.0/24"
}

variable "admin_cidr" {
  description = "Own IP in CIDR form, e.g. 203.0.113.5/32. Only this IP may use SSH and the k8 API."
  type        = string
}
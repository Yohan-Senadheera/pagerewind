variable "name" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "security_group_id" {
  type = string
}

variable "ssh_public_key" {
  type = string
}

variable "instance_type" {
  type    = string
  default = "m7i-flex.large"
}

variable "root_volume_gb" {
  type    = number
  default = 30
}

variable "server_private_ip" {
  description = "Fixed private IP for the server, so the agent knows where to join"
  type        = string
  default     = "10.0.1.10"
}
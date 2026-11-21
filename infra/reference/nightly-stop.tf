# Cost backstop: stop both nodes every night (default 04:00 Sri Lanka time), in case they were left running.
# Runs in AWS (EventBridge Scheduler), so it works when the laptop is off or the AWS login has expired.
# Stopped nodes keep their disks; ".\scripts\pagerewind.ps1 start" brings them back in about 3 minutes.

variable "nightly_stop_enabled" {
  description = "Stop both nodes every night."
  type        = bool
  default     = true
}

variable "nightly_stop_hour" {
  description = "Hour (0-23, Asia/Colombo) at which both nodes are stopped. After late-night work sessions."
  type        = number
  default     = 4

  validation {
    condition     = var.nightly_stop_hour >= 0 && var.nightly_stop_hour <= 23 && floor(var.nightly_stop_hour) == var.nightly_stop_hour
    error_message = "nightly_stop_hour must be a whole hour from 0 to 23."
  }
}

data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
  }
}

# Shared by this nightly stop and by the session timer that "pagerewind.ps1 start" sets.
resource "aws_iam_role" "nightly_stop" {
  name               = "pagerewind-auto-stop"
  description        = "Lets EventBridge Scheduler stop the PageRewind nodes"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

# Only stop, and only these two instances.
data "aws_iam_policy_document" "nightly_stop" {
  statement {
    actions = ["ec2:StopInstances"]
    resources = [
      "arn:aws:ec2:*:*:instance/${module.k3s.server_instance_id}",
      "arn:aws:ec2:*:*:instance/${module.k3s.agent_instance_id}",
    ]
  }
}

resource "aws_iam_role_policy" "nightly_stop" {
  name   = "stop-pagerewind-nodes"
  role   = aws_iam_role.nightly_stop.id
  policy = data.aws_iam_policy_document.nightly_stop.json
}

resource "aws_scheduler_schedule" "nightly_stop" {
  name        = "pagerewind-nightly-stop"
  description = "Stop the PageRewind nodes at ${format("%02d", var.nightly_stop_hour)}:00 Asia/Colombo"
  state       = var.nightly_stop_enabled ? "ENABLED" : "DISABLED"

  schedule_expression          = "cron(0 ${var.nightly_stop_hour} * * ? *)"
  schedule_expression_timezone = "Asia/Colombo"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    # Calls the EC2 StopInstances API directly; no Lambda needed.
    arn      = "arn:aws:scheduler:::aws-sdk:ec2:stopInstances"
    role_arn = aws_iam_role.nightly_stop.arn
    input = jsonencode({
      InstanceIds = [module.k3s.server_instance_id, module.k3s.agent_instance_id]
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

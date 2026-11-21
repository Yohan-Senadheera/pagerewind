# Cost backstop: stop both nodes every night at 02:00 Sri Lanka time, in case they were left running.
# Runs in AWS (EventBridge Scheduler), so it works when the laptop is off or the AWS login has expired.
# Stopped nodes keep their disks; ".\scripts\pagerewind.ps1 start" brings them back in about 3 minutes.

variable "nightly_stop_enabled" {
  description = "Stop both nodes every night at 02:00 Asia/Colombo."
  type        = bool
  default     = true
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

resource "aws_iam_role" "nightly_stop" {
  name               = "pagerewind-nightly-stop"
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
  description = "Stop the PageRewind nodes at 02:00 Asia/Colombo"
  state       = var.nightly_stop_enabled ? "ENABLED" : "DISABLED"

  schedule_expression          = "cron(0 2 * * ? *)"
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

# DynamoDB module - Rate limit and idempotency tables

# Rate Limits Table
resource "aws_dynamodb_table" "rate_limits" {
  name         = "${var.project_name}-${var.environment}-rate-limits"
  billing_mode = var.rate_limit_table_config.billing_mode
  hash_key     = "pk"

  # Only set capacity if PROVISIONED
  read_capacity  = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? var.rate_limit_table_config.read_capacity : null
  write_capacity = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? var.rate_limit_table_config.write_capacity : null

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.environment == "prod" ? true : false
  }

  server_side_encryption {
    enabled = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-rate-limits"
    Purpose     = "Rate limit token bucket storage"
  }
}

# Idempotency Table
resource "aws_dynamodb_table" "idempotency" {
  name         = "${var.project_name}-${var.environment}-idempotency"
  billing_mode = var.idempotency_table_config.billing_mode
  hash_key     = "pk"

  read_capacity  = var.idempotency_table_config.billing_mode == "PROVISIONED" ? var.idempotency_table_config.read_capacity : null
  write_capacity = var.idempotency_table_config.billing_mode == "PROVISIONED" ? var.idempotency_table_config.write_capacity : null

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.environment == "prod" ? true : false
  }

  server_side_encryption {
    enabled = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-idempotency"
    Purpose     = "Idempotency key storage"
  }
}

# Token Quotas Table
resource "aws_dynamodb_table" "token_quotas" {
  name         = "${var.project_name}-${var.environment}-token-quotas"
  billing_mode = var.token_quota_table_config.billing_mode
  hash_key     = "pk"

  read_capacity  = var.token_quota_table_config.billing_mode == "PROVISIONED" ? var.token_quota_table_config.read_capacity : null
  write_capacity = var.token_quota_table_config.billing_mode == "PROVISIONED" ? var.token_quota_table_config.write_capacity : null

  attribute {
    name = "pk"
    type = "S"
  }

  ttl {
    attribute_name = "ttl"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = var.environment == "prod" ? true : false
  }

  server_side_encryption {
    enabled = true
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-token-quotas"
    Purpose = "AI token quota tracking"
  }
}

# Auto-scaling for PROVISIONED mode
resource "aws_appautoscaling_target" "rate_limits_read" {
  count              = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? 1 : 0
  max_capacity       = 100
  min_capacity       = var.rate_limit_table_config.read_capacity
  resource_id        = "table/${aws_dynamodb_table.rate_limits.name}"
  scalable_dimension = "dynamodb:table:ReadCapacityUnits"
  service_namespace  = "dynamodb"
}

resource "aws_appautoscaling_target" "rate_limits_write" {
  count              = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? 1 : 0
  max_capacity       = 100
  min_capacity       = var.rate_limit_table_config.write_capacity
  resource_id        = "table/${aws_dynamodb_table.rate_limits.name}"
  scalable_dimension = "dynamodb:table:WriteCapacityUnits"
  service_namespace  = "dynamodb"
}

resource "aws_appautoscaling_policy" "rate_limits_read" {
  count              = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? 1 : 0
  name               = "${var.project_name}-${var.environment}-rate-limits-read-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.rate_limits_read[0].resource_id
  scalable_dimension = aws_appautoscaling_target.rate_limits_read[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.rate_limits_read[0].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "DynamoDBReadCapacityUtilization"
    }
    target_value = 70.0
  }
}

resource "aws_appautoscaling_policy" "rate_limits_write" {
  count              = var.rate_limit_table_config.billing_mode == "PROVISIONED" ? 1 : 0
  name               = "${var.project_name}-${var.environment}-rate-limits-write-autoscaling"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.rate_limits_write[0].resource_id
  scalable_dimension = aws_appautoscaling_target.rate_limits_write[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.rate_limits_write[0].service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "DynamoDBWriteCapacityUtilization"
    }
    target_value = 70.0
  }
}

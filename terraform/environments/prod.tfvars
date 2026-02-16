# =============================================================================
# Production Environment Configuration
# =============================================================================

environment = "prod"

# ECS Configuration (production-ready)
ecs_desired_count = 2
ecs_cpu       = 512
ecs_memory    = 1024

# Kinesis Configuration (production-ready)
kinesis_shard_count     = 2
kinesis_retention_hours = 168 # 7 days

# Container image - update after pushing to ECR
# container_image = "123456789.dkr.ecr.us-east-1.amazonaws.com/rate-limiter-platform:v1.0.0"

# Optional: SNS topic for alarms
# alarm_sns_topic_arn = "arn:aws:sns:us-east-1:123456789:alerts"

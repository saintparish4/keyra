# =============================================================================
# Development Environment Configuration
# =============================================================================

environment = "dev"

# ECS Configuration (smaller for dev)
ecs_desired_count = 1
ecs_cpu       = 256
ecs_memory    = 512

# Kinesis Configuration (minimal for dev)
kinesis_shard_count     = 1
kinesis_retention_hours = 24

# Container image - update after pushing to ECR
# container_image = "123456789.dkr.ecr.us-east-1.amazonaws.com/rate-limiter-platform:latest"

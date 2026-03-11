# Kinesis module - Event streaming and analytics pipeline

# Kinesis Data Stream
resource "aws_kinesis_stream" "events" {
  name             = "${var.project_name}-${var.environment}-events"
  shard_count      = var.shard_count
  retention_period = var.retention_hours

  stream_mode_details {
    stream_mode = "PROVISIONED"
  }

  encryption_type = "KMS"
  kms_key_id      = "alias/aws/kinesis"

  tags = {
    Name    = "${var.project_name}-${var.environment}-events"
    Purpose = "Rate limit event streaming"
  }
}

# S3 bucket for Firehose delivery (only if enabled)
resource "aws_s3_bucket" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = var.s3_bucket_name != "" ? var.s3_bucket_name : "${var.project_name}-${var.environment}-events-${data.aws_caller_identity.current.account_id}"

  tags = {
    Name    = "${var.project_name}-${var.environment}-events"
    Purpose = "Event storage for analytics"
  }
}

resource "aws_s3_bucket_versioning" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "events" {
  count  = var.enable_firehose ? 1 : 0
  bucket = aws_s3_bucket.events[0].id

  rule {
    id     = "general-events-archive"
    status = "Enabled"

    transition {
      days          = 30
      storage_class = "GLACIER"
    }

    expiration {
      days = 90
    }

    filter {
      prefix = "events/"
    }
  }

  rule {
    id     = "audit-compliance-retention"
    status = var.enable_audit_compliance ? "Enabled" : "Disabled"

    transition {
      days          = var.audit_glacier_transition_days
      storage_class = "DEEP_ARCHIVE"
    }

    expiration {
      days = var.audit_retention_days
    }

    filter {
      prefix = "audit/"
    }
  }
}

# Firehose IAM Role
resource "aws_iam_role" "firehose" {
  count = var.enable_firehose ? 1 : 0
  name  = "${var.project_name}-${var.environment}-firehose"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "firehose.amazonaws.com"
        }
      }
    ]
  })
}

resource "aws_iam_role_policy" "firehose" {
  count = var.enable_firehose ? 1 : 0
  name  = "firehose-permissions"
  role  = aws_iam_role.firehose[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "s3:PutObject",
          "s3:GetBucketLocation",
          "s3:ListBucket"
        ]
        Resource = [
          aws_s3_bucket.events[0].arn,
          "${aws_s3_bucket.events[0].arn}/*"
        ]
      },
      {
        Effect = "Allow"
        Action = [
          "kinesis:DescribeStream",
          "kinesis:GetShardIterator",
          "kinesis:GetRecords",
          "kinesis:ListShards"
        ]
        Resource = aws_kinesis_stream.events.arn
      },
      {
        Effect = "Allow"
        Action = [
          "logs:PutLogEvents"
        ]
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "glue:GetTable",
          "glue:GetTableVersion",
          "glue:GetTableVersions"
        ]
        Resource = [
          "arn:aws:glue:*:*:catalog",
          "arn:aws:glue:*:*:database/${var.project_name}_${var.environment}_audit",
          "arn:aws:glue:*:*:table/${var.project_name}_${var.environment}_audit/*"
        ]
      }
    ]
  })
}

# Kinesis Firehose Delivery Stream
resource "aws_kinesis_firehose_delivery_stream" "events" {
  count       = var.enable_firehose ? 1 : 0
  name        = "${var.project_name}-${var.environment}-events-firehose"
  destination = "extended_s3"

  kinesis_source_configuration {
    kinesis_stream_arn = aws_kinesis_stream.events.arn
    role_arn           = aws_iam_role.firehose[0].arn
  }

  extended_s3_configuration {
    role_arn   = aws_iam_role.firehose[0].arn
    bucket_arn = aws_s3_bucket.events[0].arn
    prefix     = "events/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"

    buffering_size     = 5   # MB
    buffering_interval = 300 # seconds

    compression_format = "GZIP"

    cloudwatch_logging_options {
      enabled         = true
      log_group_name  = "/aws/firehose/${var.project_name}-${var.environment}"
      log_stream_name = "events"
    }
  }

  tags = {
    Name    = "${var.project_name}-${var.environment}-events-firehose"
    Purpose = "Event delivery to S3"
  }
}

# Glue catalog for Parquet schema (required by Firehose data format conversion)
resource "aws_glue_catalog_database" "audit" {
  count = var.enable_firehose && var.enable_audit_compliance ? 1 : 0
  name  = "${var.project_name}_${var.environment}_audit"
}

resource "aws_glue_catalog_table" "audit_events" {
  count         = var.enable_firehose && var.enable_audit_compliance ? 1 : 0
  name          = "audit_events"
  database_name = aws_glue_catalog_database.audit[0].name

  table_type = "EXTERNAL_TABLE"

  parameters = {
    classification = "parquet"
  }

  storage_descriptor {
    location      = "s3://${aws_s3_bucket.events[0].id}/audit/"
    input_format  = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat"
    output_format = "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat"

    ser_de_info {
      serialization_library = "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"
      parameters = {
        "serialization.format" = 1
      }
    }

    columns {
      name = "timestamp"
      type = "string"
    }

    columns {
      name = "event_type"
      type = "string"
    }

    columns {
      name = "request_id"
      type = "string"
    }

    columns {
      name = "api_key"
      type = "string"
    }

    columns {
      name = "client_id"
      type = "string"
    }

    columns {
      name = "decision"
      type = "string"
    }

    columns {
      name = "reason"
      type = "string"
    }

    columns {
      name = "endpoint"
      type = "string"
    }

    columns {
      name = "source_ip"
      type = "string"
    }

    columns {
      name = "tier"
      type = "string"
    }

    columns {
      name = "trace_id"
      type = "string"
    }
  }

  partition_keys {
    name = "year"
    type = "string"
  }

  partition_keys {
    name = "month"
    type = "string"
  }

  partition_keys {
    name = "day"
    type = "string"
  }
}

# Kinesis Firehose for audit events -> S3 Parquet (PCI DSS 4.0.1)
resource "aws_kinesis_firehose_delivery_stream" "audit" {
  count       = var.enable_firehose && var.enable_audit_compliance ? 1 : 0
  name        = "${var.project_name}-${var.environment}-audit-firehose"
  destination = "extended_s3"

  kinesis_source_configuration {
    kinesis_stream_arn = aws_kinesis_stream.events.arn
    role_arn           = aws_iam_role.firehose[0].arn
  }

  extended_s3_configuration {
    role_arn   = aws_iam_role.firehose[0].arn
    bucket_arn = aws_s3_bucket.events[0].arn
    prefix     = "audit/year=!{timestamp:yyyy}/month=!{timestamp:MM}/day=!{timestamp:dd}/"

    buffering_size     = 64  # MB — larger buffers = fewer Parquet files
    buffering_interval = 300 # seconds

    data_format_conversion_configuration {
      input_format_configuration {
        deserializer {
          open_x_json_ser_de {}
        }
      }

      output_format_configuration {
        serializer {
          parquet_ser_de {
            compression = "SNAPPY"
          }
        }
      }

      schema_configuration {
        database_name = aws_glue_catalog_database.audit[0].name
        table_name    = aws_glue_catalog_table.audit_events[0].name
        role_arn      = aws_iam_role.firehose[0].arn
      }
    }

    cloudwatch_logging_options {
      enabled         = true
      log_group_name  = "/aws/firehose/${var.project_name}-${var.environment}"
      log_stream_name = "audit"
    }
  }

  tags = {
    Name       = "${var.project_name}-${var.environment}-audit-firehose"
    Purpose    = "PCI DSS 4.0.1 audit trail"
    Compliance = "PCI-DSS-4.0.1"
  }

  depends_on = [
    aws_glue_catalog_table.audit_events
  ]
}

data "aws_caller_identity" "current" {}
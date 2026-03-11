output "stream_name" {
  value = aws_kinesis_stream.events.name
}

output "stream_arn" {
  value = aws_kinesis_stream.events.arn
}

output "s3_bucket_name" {
  value = var.enable_firehose ? aws_s3_bucket.events[0].id : null
}

output "s3_bucket_arn" {
  value = var.enable_firehose ? aws_s3_bucket.events[0].arn : null
}

output "firehose_name" {
  value = var.enable_firehose ? aws_kinesis_firehose_delivery_stream.events[0].name : null
}

output "audit_firehose_name" {
  value = var.enable_firehose && var.enable_audit_compliance ? aws_kinesis_firehose_delivery_stream.audit[0].name : null
}

output "glue_database_name" {
  value = var.enable_firehose && var.enable_audit_compliance ? aws_glue_catalog_database.audit[0].name : null
}
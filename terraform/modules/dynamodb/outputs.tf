output "rate_limit_table_name" {
  value = aws_dynamodb_table.rate_limits.name
}

output "rate_limit_table_arn" {
  value = aws_dynamodb_table.rate_limits.arn
}

output "idempotency_table_name" {
  value = aws_dynamodb_table.idempotency.name
}

output "idempotency_table_arn" {
  value = aws_dynamodb_table.idempotency.arn
}

output "token_quota_table_name" {
  value = aws_dynamodb_table.token_quotas.name
}

output "token_quota_table_arn" {
  value = aws_dynamodb_table.token_quotas.arn
}

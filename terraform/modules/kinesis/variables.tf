variable "project_name" {
  type = string
}

variable "environment" {
  type = string
}

variable "shard_count" {
  type    = number
  default = 1
}

variable "retention_hours" {
  type    = number
  default = 24
}

variable "enable_firehose" {
  type    = bool
  default = false
}

variable "s3_bucket_name" {
  type    = string
  default = ""
}

variable "enable_audit_compliance" {
  description = "Enable PCI DSS 4.0.1 audit trail with 7-year S3 retention"
  type        = bool
  default     = false
}

variable "audit_retention_days" {
  description = "Number of days to retain audit events in S3 (2557 = 7 years)"
  type        = number
  default     = 2557
}

variable "audit_glacier_transition_days" {
  description = "Days before audit events transition to Glacier Deep Archive"
  type        = number
  default     = 90
}
package core 

/**
  * Profile for leaky bucket rate limiting. Same numeric fields as RateLimitProfile: 
  * - capacity: bucket size (max "water" level)
  * - refillRatePerSecond: interpreted as leak rate (units per second leaving the bucket)
  * - ttlSeconds: TTL for stored state
  * Reuse RateLimitProfile at call sites; this type documents leaky-bucket semantics.
  */
  type LeakyBucketProfile = RateLimitProfile
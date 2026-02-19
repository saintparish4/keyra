package core

/** Typed errors from storage backends.
  *
  * Used to surface data-corruption failures explicitly rather than silently
  * dropping them or swallowing them as generic exceptions.
  */
sealed trait StoreError

object StoreError:
  /** A stored record could not be decoded — the data in the backend is
    * structurally invalid (unknown enum value, bad JSON, missing required
    * attribute).
    *
    * Callers should propagate this as a `503 Service Unavailable` rather than
    * an internal error, because it indicates a backend-state problem, not a
    * client mistake.
    */
  final case class CorruptRecord(key: String, detail: String) extends StoreError

/** Raised in the F monad when [[DynamoDBIdempotencyStore]] encounters a
  * [[StoreError.CorruptRecord]] while decoding a stored idempotency record.
  * Caught at the HTTP layer and mapped to 503.
  */
final class CorruptIdempotencyRecordException(
    val key: String,
    val detail: String,
) extends RuntimeException(s"Corrupt idempotency record key=$key: $detail")

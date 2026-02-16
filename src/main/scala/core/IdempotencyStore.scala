package core

import java.time.Instant

import cats.effect.*
import cats.syntax.all.*

/** Result of an idempotency check.
  */
sealed trait IdempotencyResult

object IdempotencyResult:
  /** First time seeing this idempotency key - proceed with the operation.
    */
  case class New(idempotencyKey: String, createdAt: Instant)
      extends IdempotencyResult

  /** Duplicate request detected - return cached response.
    */
  case class Duplicate(
      idempotencyKey: String,
      originalResponse: Option[StoredResponse],
      firstSeenAt: Instant,
  ) extends IdempotencyResult

  /** Operation is currently in progress.
    */
  case class InProgress(idempotencyKey: String, startedAt: Instant)
      extends IdempotencyResult

/** Stored response from a previous idempotent operation.
  */
case class StoredResponse(
    statusCode: Int,
    body: String,
    headers: Map[String, String],
    completedAt: Instant,
)

/** Idempotency record stored in the backend.
  */
case class IdempotencyRecord(
    idempotencyKey: String,
    clientId: String,
    status: IdempotencyStatus,
    response: Option[StoredResponse],
    createdAt: Instant,
    updatedAt: Instant,
    ttl: Long,
    version: Long,
)

sealed trait IdempotencyStatus
object IdempotencyStatus:
  case object Pending extends IdempotencyStatus
  case object Completed extends IdempotencyStatus
  case object Failed extends IdempotencyStatus

/** Trait for idempotency storage backends.
  *
  * Implements first-writer-wins semantics to ensure that only one instance of
  * an operation is executed, even under concurrent requests.
  *
  * @tparam F
  *   the effect type
  */
trait IdempotencyStore[F[_]]:
  /** Check if an operation with this idempotency key has been seen before.
    *
    * Uses first-writer-wins semantics:
    *   - If key doesn't exist: create it with Pending status, return New
    *   - If key exists with Pending: return InProgress
    *   - If key exists with Completed: return Duplicate with stored response
    *
    * @param idempotencyKey
    *   Unique key for this operation
    * @param clientId
    *   Client making the request (for audit/logging)
    * @param ttlSeconds
    *   Time-to-live for the record
    * @return
    *   Result indicating if this is new, duplicate, or in-progress
    */
  def check(
      idempotencyKey: String,
      clientId: String,
      ttlSeconds: Long,
  ): F[IdempotencyResult]

  /** Store the response for a completed operation.
    *
    * Should only be called after check() returns New and the operation has
    * completed successfully.
    *
    * @param idempotencyKey
    *   The key from the original check
    * @param response
    *   The response to store
    * @return
    *   true if stored successfully
    */
  def storeResponse(idempotencyKey: String, response: StoredResponse): F[Boolean]

  /** Mark an operation as failed.
    *
    * This allows the key to be retried (by deleting or marking as failed).
    *
    * @param idempotencyKey
    *   The key from the original check
    * @return
    *   true if marked successfully
    */
  def markFailed(idempotencyKey: String): F[Boolean]

  /** Get the current status of an idempotency key.
    *
    * @param idempotencyKey
    *   The key to check
    * @return
    *   The record if it exists
    */
  def get(idempotencyKey: String): F[Option[IdempotencyRecord]]

  /** Health check for the storage backend.
    */
  def healthCheck: F[Boolean]

object IdempotencyStore:
  /** Create an in-memory store for testing.
    */
  def inMemory[F[_]: Temporal]: F[IdempotencyStore[F]] =
    import cats.effect.Ref
    import cats.effect.Clock

    Ref.of[F, Map[String, IdempotencyRecord]](Map.empty).map { stateRef =>
      new IdempotencyStore[F]:
        override def check(
            idempotencyKey: String,
            clientId: String,
            ttlSeconds: Long,
        ): F[IdempotencyResult] =
          for
            now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
            result <- stateRef.modify { records =>
              records.get(idempotencyKey) match
                case Some(existing) => existing.status match
                    case IdempotencyStatus.Pending => (
                        records,
                        IdempotencyResult
                          .InProgress(idempotencyKey, existing.createdAt),
                      )
                    case IdempotencyStatus.Completed => (
                        records,
                        IdempotencyResult.Duplicate(
                          idempotencyKey,
                          existing.response,
                          existing.createdAt,
                        ),
                      )
                    case IdempotencyStatus.Failed =>
                      // Allow retry on failed
                      val newRecord = IdempotencyRecord(
                        idempotencyKey = idempotencyKey,
                        clientId = clientId,
                        status = IdempotencyStatus.Pending,
                        response = None,
                        createdAt = now,
                        updatedAt = now,
                        ttl = now.getEpochSecond + ttlSeconds,
                        version = existing.version + 1,
                      )
                      (
                        records + (idempotencyKey -> newRecord),
                        IdempotencyResult.New(idempotencyKey, now),
                      )

                case None =>
                  val newRecord = IdempotencyRecord(
                    idempotencyKey = idempotencyKey,
                    clientId = clientId,
                    status = IdempotencyStatus.Pending,
                    response = None,
                    createdAt = now,
                    updatedAt = now,
                    ttl = now.getEpochSecond + ttlSeconds,
                    version = 1,
                  )
                  (
                    records + (idempotencyKey -> newRecord),
                    IdempotencyResult.New(idempotencyKey, now),
                  )
            }
          yield result

        override def storeResponse(
            idempotencyKey: String,
            response: StoredResponse,
        ): F[Boolean] =
          for
            now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
            result <- stateRef.modify(records =>
              records.get(idempotencyKey) match
                case Some(existing)
                    if existing.status == IdempotencyStatus.Pending =>
                  val updated = existing.copy(
                    status = IdempotencyStatus.Completed,
                    response = Some(response),
                    updatedAt = now,
                  )
                  (records + (idempotencyKey -> updated), true)
                case _ => (records, false),
            )
          yield result

        override def markFailed(idempotencyKey: String): F[Boolean] =
          for
            now <- Clock[F].realTime.map(d => Instant.ofEpochMilli(d.toMillis))
            result <- stateRef.modify(records =>
              records.get(idempotencyKey) match
                case Some(existing) =>
                  val updated = existing
                    .copy(status = IdempotencyStatus.Failed, updatedAt = now)
                  (records + (idempotencyKey -> updated), true)
                case None => (records, false),
            )
          yield result

        override def get(idempotencyKey: String): F[Option[IdempotencyRecord]] =
          stateRef.get.map(_.get(idempotencyKey))

        override def healthCheck: F[Boolean] = Temporal[F].pure(true)
    }

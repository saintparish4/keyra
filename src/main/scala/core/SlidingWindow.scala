package core

import java.time.Instant

/** Pure sliding window math. No effects; all functions are total.
  *
  * The parent window is divided into `subWindowCount` sub-windows of equal
  * duration. Sub-window boundaries are epoch-aligned so every service instance
  * independently computes identical window boundaries
  *
  * ==Key invariants==
  *   - `currentSubWindowStart` is always <= nowMs.
  *   - `activeSubWindowStarts` always has exactly `subWindowCount` elements.
  *   - Counts for sub-windows not in the active list are stale and must be
  *     pruned before writing to keep the DynamoDB item small.
  */
object SlidingWindow:

  val DefaultSubWindowCount: Int = 10

  /** Duration of each sub-window in milliseconds. */
  def subWindowDurationMs(windowDurationMs: Long, subWindowCount: Int): Long =
    windowDurationMs / subWindowCount

  /** Epoch-aligned start of the sub-window that contains `nowMs`. */
  def currentSubWindowStart(nowMs: Long, subWindowDurationMs: Long): Long =
    nowMs / subWindowDurationMs * subWindowDurationMs

  /** All active sub-window start timestamps, newest first (index 0 = current
    * The oldest sub-window at index (subWindowCount - 1) expires at
    * `oldest + windowDurationMs`.
    */
  def activeSubWindowStarts(
      nowMs: Long,
      windowDurationMs: Long,
      subWindowCount: Int,
  ): List[Long] =
    val subDuration = subWindowDurationMs(windowDurationMs, subWindowCount)
    val current = currentSubWindowStart(nowMs, subDuration)
    (0 until subWindowCount).map(i => current - i * subDuration).toList

  /** Sum of counts across all active sub-windows. */
  def totalCount(counts: Map[Long, Long], activeStarts: List[Long]): Long =
    activeStarts.foldLeft(0L)(_ + counts.getOrElse(_, 0L))

  /** Requests remaining in the current window (clamped to >= 0). */
  def remaining(capacity: Int, total: Long): Int = math
    .max(0, capacity - total.toInt)

  /** The instant at which the window total will decrease, i.e., when the oldest
    * sub-window that holds any counts expires Used to populate
    * X-RateLimit-Reset and Retry-After.
    */
  def resetAt(
      counts: Map[Long, Long],
      activeStarts: List[Long],
      windowDurationMs: Long,
  ): Instant =
    // Walk from oldest to newest; oldest counted sub-window expires first.
    activeStarts.reverseIterator.find(sw => counts.getOrElse(sw, 0L) > 0)
      .map(oldest => Instant.ofEpochMilli(oldest + windowDurationMs)).getOrElse(
        // No counts yet -- reset is at end of current sub-window window.
        activeStarts.headOption
          .map(current => Instant.ofEpochMilli(current + windowDurationMs))
          .getOrElse(Instant.ofEpochMilli(windowDurationMs)),
      )

  /** Retry-After seconds (min 1) from now to the reset instant. */
  def retryAfterSeconds(resetAt: Instant, nowMs: Long): Int = math
    .max(1, ((resetAt.toEpochMilli - nowMs) / 1000).toInt)

  /** Remove counts for sub-windows no longer in `activeStarts`. Keeps the
    * DynamoDB item bounded to `subWindowCount` entries.
    */
  def pruneStale(
      counts: Map[Long, Long],
      activeStarts: List[Long],
  ): Map[Long, Long] =
    val active = activeStarts.toSet
    counts.filter { case (sw, _) => active.contains(sw) }

/** Persisted state for one rate-limited key in DynamoDB. */
case class SlidingWindowState(
    counts: Map[Long, Long], // sub_window_start_ms -> request count
    version: Long,
)

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl

import com.intellij.openapi.util.registry.RegistryManager
import org.jetbrains.annotations.TestOnly
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAX_DELAY_DOUBLINGS = 10

/**
 * Counts the automatic restarts of each server id in a rolling time window.
 * It refuses a restart when the count reaches the maximum.
 * An explicit stop clears the count of the server id.
 *
 * The class reads the limits on each call, so a new limit applies at once.
 * The client manager calls it only under its start-stop lock, so the class does no locking of its own.
 */
internal class LspAutoRestartLimit(
  private val maxRestarts: () -> Int,
  private val window: () -> Duration,
  private val baseDelay: () -> Duration,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
  private val restarts = HashMap<String, ArrayDeque<ComparableTimeMark>>()

  sealed interface Decision {
    /** The restart can occur after [delay]. */
    data class Granted(val delay: Duration) : Decision

    /** The server id has used all its restarts in the window. */
    data object Refused : Decision

    /** The maximum or the window is 0 or less, so no server gets an automatic restart. */
    data object Disabled : Decision
  }

  /**
   * Decides on a restart of [serverId] and counts a granted restart.
   * The call also drops the restarts that left the window, for every server id.
   * Restart number k in the window waits `baseDelay * 2^(k-1)`.
   * The delay stops doubling after [MAX_DELAY_DOUBLINGS] doublings, and it is never longer than the window.
   */
  fun grant(serverId: String): Decision {
    val maxRestarts = maxRestarts()
    val window = window()
    if (maxRestarts <= 0 || !window.isPositive()) return Decision.Disabled
    val now = timeSource.markNow()
    val entries = restarts.values.iterator()
    while (entries.hasNext()) {
      val marks = entries.next()
      while (marks.isNotEmpty() && now - marks.first() >= window) {
        marks.removeFirst()
      }
      if (marks.isEmpty()) entries.remove()
    }
    val marks = restarts.getOrPut(serverId) { ArrayDeque() }
    if (marks.size >= maxRestarts) return Decision.Refused
    marks.addLast(now)
    val delay = baseDelay().coerceAtLeast(Duration.ZERO) * (1 shl (marks.size - 1).coerceAtMost(MAX_DELAY_DOUBLINGS))
    return Decision.Granted(delay.coerceAtMost(window))
  }

  fun clear(serverId: String) {
    restarts.remove(serverId)
  }

  @TestOnly
  fun serverIdCount(): Int = restarts.size

  companion object {
    /** Reads the limits from the `lsp.server.auto.restart.*` registry keys. */
    fun fromRegistry(): LspAutoRestartLimit = LspAutoRestartLimit(
      maxRestarts = { registryValue("lsp.server.auto.restart.max.count") },
      window = { registryValue("lsp.server.auto.restart.window.seconds").seconds },
      baseDelay = { registryValue("lsp.server.auto.restart.delay.ms").milliseconds },
    )

    private fun registryValue(key: String): Int = RegistryManager.getInstance().get(key).asInteger()
  }
}

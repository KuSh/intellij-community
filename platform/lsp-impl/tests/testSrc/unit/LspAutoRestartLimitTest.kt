// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.unit

import com.intellij.platform.lsp.impl.LspAutoRestartLimit
import com.intellij.platform.lsp.impl.LspAutoRestartLimit.Decision
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

internal class LspAutoRestartLimitTest {
  private val timeSource = TestTimeSource()
  private var maxRestarts = 4
  private var window = 180.seconds
  private val limit = LspAutoRestartLimit({ maxRestarts }, { window }, { 1000.milliseconds }, timeSource)

  private fun granted(seconds: Int): Decision = Decision.Granted(seconds.seconds)

  @Test
  fun `the delay doubles for each restart in the window`() {
    assertEquals(listOf(granted(1), granted(2), granted(4), granted(8)), List(4) { limit.grant("a") })
  }

  @Test
  fun `the delay stops doubling after 10 doublings`() {
    maxRestarts = 100
    window = 1.hours
    val decisions = List(100) { limit.grant("a") }
    assertEquals(granted(1024), decisions[10])
    assertEquals(granted(1024), decisions.last())
  }

  @Test
  fun `the delay is never longer than the window`() {
    maxRestarts = 10
    window = 5.seconds
    assertEquals(listOf(granted(1), granted(2), granted(4), granted(5), granted(5)), List(5) { limit.grant("a") })
  }

  @Test
  fun `a restart after the maximum is refused`() {
    repeat(4) { limit.grant("a") }
    assertEquals(Decision.Refused, limit.grant("a"))
    timeSource += 179.seconds
    assertEquals(Decision.Refused, limit.grant("a"))
  }

  @Test
  fun `a restart is granted again when the first restart leaves the window`() {
    limit.grant("a")
    timeSource += 60.seconds
    repeat(3) { limit.grant("a") }
    assertEquals(Decision.Refused, limit.grant("a"))

    timeSource += 120.seconds
    assertEquals(granted(8), limit.grant("a"), "the three restarts that remain in the window set the delay")
    assertEquals(Decision.Refused, limit.grant("a"))
  }

  @Test
  fun `each server id has its own count`() {
    repeat(4) { limit.grant("a") }
    assertEquals(Decision.Refused, limit.grant("a"))
    assertEquals(granted(1), limit.grant("b"))
  }

  @Test
  fun `clear resets the count of the server id`() {
    repeat(4) { limit.grant("a") }
    limit.grant("b")
    limit.clear("a")
    assertEquals(granted(1), limit.grant("a"))
    assertEquals(granted(2), limit.grant("b"))
  }

  @Test
  fun `a server id without restarts in the window is dropped`() {
    limit.grant("a")
    limit.grant("b")
    timeSource += 180.seconds
    limit.grant("c")
    assertEquals(1, limit.serverIdCount())
  }

  @Test
  fun `a maximum of 0 turns the restart off`() {
    maxRestarts = 0
    assertEquals(Decision.Disabled, limit.grant("a"))
    assertEquals(0, limit.serverIdCount())
  }

  @Test
  fun `a window of 0 turns the restart off`() {
    window = Duration.ZERO
    assertEquals(Decision.Disabled, limit.grant("a"))
    assertEquals(0, limit.serverIdCount())
  }

  @Test
  fun `a new maximum applies at once`() {
    repeat(2) { limit.grant("a") }
    maxRestarts = 2
    assertEquals(Decision.Refused, limit.grant("a"))
    maxRestarts = 3
    assertEquals(granted(4), limit.grant("a"))
  }
}

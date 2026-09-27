// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManagerListener
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.common.FakeLspClientDescriptor
import com.intellij.platform.lsp.common.FakeLspIntegrationProvider
import com.intellij.platform.lsp.common.fakeLspIntegrationFixture
import com.intellij.platform.lsp.impl.LspClientImpl
import com.intellij.platform.lsp.impl.LspClientManagerImpl
import com.intellij.platform.lsp.impl.getServerId
import com.intellij.platform.lsp.testFramework.awaitFileOpenedByLspServer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.xml.breadcrumbs.BreadcrumbsXmlWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private const val MAX_COUNT_KEY = "lsp.server.auto.restart.max.count"
private const val DELAY_KEY = "lsp.server.auto.restart.delay.ms"

@TestApplication
internal class LspAutoRestartTest {
  companion object {
    private val tempDirFixture = tempPathFixture()
    private val projectFixture = projectFixture(tempDirFixture, openAfterCreation = true)
    private val project by projectFixture

    @Suppress("unused")
    private val moduleFixture = projectFixture.moduleFixture(tempDirFixture, addPathToSourceRoot = true)
  }

  private val codeInsightFixture by codeInsightFixture(projectFixture, tempDirFixture)

  @Suppress("unused")
  private val fakeLspIntegration by projectFixture.fakeLspIntegrationFixture(autoRestartSupport = true)

  private val manager: LspClientManagerImpl get() = LspClientManagerImpl.getInstanceImpl(project)

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a server killed after initialization is started again`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(30.seconds) {
    val file = openFile("killed.txt")
    val client = startClient(file)
    val events = CopyOnWriteArrayList<Pair<String, LspClient>>()
    manager.addListener(object : LspClientManagerListener {
      override fun clientAdded(lspClient: LspClient) {
        events.add("added" to lspClient)
      }

      override fun clientRemoved(lspClient: LspClient) {
        events.add("removed" to lspClient)
      }
    }, disposable, false)

    val restarted = awaitRestart(client, file) { kill(client) }

    assertEquals(listOf("removed" to client, "added" to restarted), events.toList())
    assertFalse(client in manager.getClients(FakeLspIntegrationProvider::class.java), "the stopped client must be removed")
    assertSame(client.descriptor, restarted.descriptor)
    assertEquals(client.getServerId(), restarted.getServerId())
    assertTrue(restarted.isFileOpened(file), "the new client must open the file")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a server killed in the initialization listener is started again`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val initializations = AtomicInteger()
      val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerKilledEarly") {
        override val lspServerListener = object : LspServerListener {
          override fun serverInitialized(params: InitializeResult) {
            if (initializations.incrementAndGet() == 1) server.destroy()
          }
        }
      }
      val running = runningClients(disposable)

      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)
      val first = running.receive()
      val second = running.receive()

      assertNotSame(first, second)
      assertEquals(LspServerState.ShutdownUnexpectedly, first.state)
      assertSame(second, manager.getClients(FakeLspIntegrationProvider::class.java).single())
      stopClientsAndWait()
    }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a server killed during the post-initialization work of the start is started again`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val initializations = AtomicInteger()
      val armed = AtomicBoolean()
      val killed = AtomicBoolean()
      val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerPostInit") {
        override val lspServerListener = object : LspServerListener {
          override fun serverInitialized(params: InitializeResult) {
            if (initializations.incrementAndGet() == 1) armed.set(true)
          }
        }
      }
      // the start thread publishes this topic after the Running state, before the start of the client completes
      ApplicationManager.getApplication().messageBus.connect(disposable).subscribe(BreadcrumbsXmlWrapper.FORCE_RELOAD_BREADCRUMBS, Runnable {
        if (!ApplicationManager.getApplication().isDispatchThread && armed.compareAndSet(true, false)) {
          descriptor.server.destroy()
          killed.set(true)
        }
      })
      val running = runningClients(disposable)

      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)
      val first = running.receive()
      val second = running.receive()

      assertTrue(killed.get(), "the server must be killed during the start")
      assertNotSame(first, second)
      assertEquals(LspServerState.ShutdownUnexpectedly, first.state)
      assertSame(second, manager.getClients(FakeLspIntegrationProvider::class.java).single())
      stopClientsAndWait()
    }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a double stop report schedules one restart`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("double.txt"))

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
    val job = manager.pendingAutoRestartJobs().single()
    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)

    assertSame(job, manager.pendingAutoRestartJobs().single(), "the second report must not schedule a restart")
    assertFalse(job.isCancelled)
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a failure in the IDE reported first prevents the restart`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("failureFirst.txt"))

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = false)
    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)

    assertEquals(LspServerState.ShutdownUnexpectedly, client.state)
    assertTrue(manager.pendingAutoRestartJobs().isEmpty(), "the first report decides")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a death reported first schedules one restart`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("deathFirst.txt"))

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = false)

    val job = manager.pendingAutoRestartJobs().single()
    assertFalse(job.isCancelled, "the first report decides")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = MAX_COUNT_KEY, value = "2")
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a crash loop stops at the limit`() = timeoutRunBlocking(30.seconds) {
    val file = openFile("loop.txt")
    val first = startClient(file)
    val second = awaitRestart(first, file) { kill(first) }
    val third = awaitRestart(second, file) { kill(second) }

    manager.handleMaybeUnexpectedServerStop(third, "test", serverGone = true)
    manager.pendingAutoRestartJobs().joinAll()

    assertEquals(LspServerState.ShutdownUnexpectedly, third.state)
    assertSame(third, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a failed start is not retried`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(30.seconds) {
    val failed = CompletableDeferred<LspClient>()
    manager.addListener(object : LspClientManagerListener {
      override fun serverStateChanged(lspClient: LspClient) {
        if (lspClient.state == LspServerState.ShutdownUnexpectedly) failed.complete(lspClient)
      }
    }, disposable, false)
    val descriptor = FakeLspClientDescriptor(project, LspCustomization(), { throw IllegalStateException("test") }, null)

    manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)!!.join()
    val client = failed.await()
    // the report thread of the failed start gives no signal after its last step, so the test waits for it
    delay(500.milliseconds)
    manager.pendingAutoRestartJobs().joinAll()

    assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the failed client must stay")
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  @RegistryKey(key = "lsp.server.connect.timeout", value = "1")
  fun `a start that fails in the IDE after the Running state is not retried`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val failed = CompletableDeferred<LspClient>()
      manager.addListener(object : LspClientManagerListener {
        override fun serverStateChanged(lspClient: LspClient) {
          if (lspClient.state == LspServerState.ShutdownUnexpectedly) failed.complete(lspClient)
        }
      }, disposable, false)
      // the exception stops the initialization after the Running state, so the start fails at the connect timeout
      val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerFailing") {
        override val lspServerListener = object : LspServerListener {
          override fun serverInitialized(params: InitializeResult) {
            throw IllegalStateException("test")
          }
        }
      }

      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)!!.join()
      val client = failed.await()
      // the report thread of the failed start gives no signal after its last step, so the test waits for it
      delay(500.milliseconds)
      manager.pendingAutoRestartJobs().joinAll()

      assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the failed client must stay")
      assertTrue(manager.pendingAutoRestartJobs().isEmpty())
      stopClientsAndWait()
    }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  @RegistryKey(key = "lsp.server.connect.timeout", value = "1")
  fun `a death reported before a failure in the IDE restarts the server`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val stoppedUnexpectedly = AtomicBoolean()
      val stoppedBeforeFailure = AtomicBoolean()
      manager.addListener(object : LspClientManagerListener {
        override fun serverStateChanged(lspClient: LspClient) {
          if (lspClient.state == LspServerState.ShutdownUnexpectedly) stoppedUnexpectedly.set(true)
        }
      }, disposable, false)
      val initializations = AtomicInteger()
      // the exception stops the initialization after the Running state, so the start fails at the connect timeout
      val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerKilledThenFailing") {
        override val lspServerListener = object : LspServerListener {
          override fun serverInitialized(params: InitializeResult) {
            if (initializations.incrementAndGet() != 1) return
            server.destroy()
            val deadline = TimeSource.Monotonic.markNow() + 10.seconds
            while (!stoppedUnexpectedly.get() && deadline.hasNotPassedNow()) Thread.sleep(10)
            stoppedBeforeFailure.set(stoppedUnexpectedly.get())
            throw IllegalStateException("test")
          }
        }
      }
      val running = runningClients(disposable)

      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)
      val first = running.receive()
      val second = running.receive()

      assertTrue(stoppedBeforeFailure.get(), "the death of the server must be reported before the failure in the IDE")
      assertNotSame(first, second)
      assertSame(second, manager.getClients(FakeLspIntegrationProvider::class.java).single())
      stopClientsAndWait()
    }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a server that stops before the Running state is not restarted`() = timeoutRunBlocking(30.seconds) {
    val processStarting = CompletableDeferred<Unit>()
    val release = CountDownLatch(1)
    val descriptor = FakeLspClientDescriptor(project, LspCustomization(), {
      processStarting.complete(Unit)
      release.await(10, TimeUnit.SECONDS)
    }, null, presentableName = "FakeLspServerSlowStart")
    manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)!!.join()
    processStarting.await()
    val client = manager.getClients(FakeLspIntegrationProvider::class.java).single()
    assertEquals(LspServerState.Initializing, client.state)

    // the IDE disconnects from the server only after the start releases the connector, so the report runs on its own thread
    val report = launch(Dispatchers.IO) { manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true) }
    while (client.state != LspServerState.ShutdownUnexpectedly) delay(10.milliseconds)
    release.countDown()
    report.join()

    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `an explicit stop during the delay cancels the restart`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("stop.txt"))
    kill(client)
    val job = awaitPendingRestart()

    stopAndWait { manager.stopRunningServer(client) }

    assertTrue(job.isCancelled)
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `stopping the clients of the provider during the delay cancels the restart`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("stopClients.txt"))
    kill(client)
    val job = awaitPendingRestart()

    stopClientsAndWait()

    assertTrue(job.isCancelled)
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of all clients from the listener of the stopped server prevents the restart`() = timeoutRunBlocking(30.seconds) {
    val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerStopping") {
      override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
          if (!shutdownNormally) manager.stopClients(FakeLspIntegrationProvider::class.java)
        }
      }
    }
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor) }

    stopAndWait { manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true) }

    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of the client from the listener of the stopped server prevents the restart`() = timeoutRunBlocking(30.seconds) {
    lateinit var client: LspClientImpl
    val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerStoppingItself") {
      override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
          if (!shutdownNormally) manager.stopRunningServer(client)
        }
      }
    }
    client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor) }

    stopAndWait { manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true) }

    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `the pending restart completes after the new client replaces the stopped client`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("pending.txt"))

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
    manager.pendingAutoRestartJobs().joinAll()

    val restarted = manager.getClients(FakeLspIntegrationProvider::class.java).single()
    assertNotSame(client, restarted)
    assertSame(client.descriptor, restarted.descriptor)
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a failing listener of the stopped server does not prevent the restart`() = timeoutRunBlocking(30.seconds) {
    val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerFailingListener") {
      override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
          if (!shutdownNormally) throw IllegalStateException("test")
        }
      }
    }
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor) }

    assertThrows<IllegalStateException> { manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true) }

    assertTrue(client.disconnected.isCompleted && !client.disconnected.isCancelled, "the release must succeed")
    assertFalse(manager.pendingAutoRestartJobs().single().isCancelled)
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = MAX_COUNT_KEY, value = "0")
  fun `an exception after the stop does not hide the exception of the stop`() = timeoutRunBlocking(30.seconds) {
    val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerTwoFailures") {
      override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
          if (!shutdownNormally) throw IllegalStateException("listener")
        }
      }
    }
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor) }

    val thrown = assertThrows<IllegalStateException> {
      client.ensureServerStopped(
        explicitStop = false,
        afterStop = { throw IllegalArgumentException("afterStop") },
        updateLspServerManagerState = {},
      )
    }

    assertEquals("listener", thrown.message)
    assertEquals(listOf("afterStop"), thrown.suppressed.map { it.message })
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a failed release of the stopped server drops the restart`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("failedRelease.txt"))
    val added = CopyOnWriteArrayList<LspClient>()
    manager.addListener(object : LspClientManagerListener {
      override fun clientAdded(lspClient: LspClient) {
        added.add(lspClient)
      }
    }, disposable, false)
    // the release completes `disconnected` only once, so an earlier failure stands for a failed release
    client.disconnected.completeExceptionally(IllegalStateException("test"))

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
    manager.pendingAutoRestartJobs().joinAll()

    assertTrue(added.isEmpty(), "a server that the IDE could not release must not be started again")
    assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of all clients while a client crashes prevents the restart`() = timeoutRunBlocking(30.seconds) {
    lateinit var second: LspClientImpl
    val scheduledDuringStop = CopyOnWriteArrayList<Job>()
    // the stop of all clients reaches the second client after the normal stop of the first client
    val firstDescriptor = descriptorWithNormalStopAction("FakeLspServerFirst") {
      manager.handleMaybeUnexpectedServerStop(second, "test", serverGone = true)
      scheduledDuringStop.addAll(manager.pendingAutoRestartJobs())
    }
    val secondDescriptor = FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerSecond")
    awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, firstDescriptor) }
    second = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, secondDescriptor) }

    withContext(Dispatchers.IO) { manager.stopClients(FakeLspIntegrationProvider::class.java) }

    assertTrue(scheduledDuringStop.isEmpty(), "the crash during the stop must not schedule a restart")
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of all clients in progress prevents a pending restart`() = timeoutRunBlocking(30.seconds) {
    lateinit var second: LspClientImpl
    var requestStamp = -1L
    val clientsAfterRestart = CopyOnWriteArrayList<List<LspClientImpl>>()
    // the stop of all clients reaches the second client after the normal stop of the first client
    val firstDescriptor = descriptorWithNormalStopAction("FakeLspServerFirst") {
      @Suppress("RAW_RUN_BLOCKING")
      runBlocking { manager.restartAfterUnexpectedStop(second, requestStamp)!!.join() }
      clientsAfterRestart.add(manager.getClients(FakeLspIntegrationProvider::class.java).toList())
    }
    val secondDescriptor = FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerSecond")
    awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, firstDescriptor) }
    second = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, secondDescriptor) }
    kill(second)
    awaitPendingRestart()
    requestStamp = manager.startRequestStamp()

    withContext(Dispatchers.IO) { manager.stopClients(FakeLspIntegrationProvider::class.java) }

    assertEquals(listOf(listOf(second)), clientsAfterRestart.toList(), "the stop in progress must drop the restart")
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a client stopped on request is not restarted by a newer start request`() = timeoutRunBlocking(30.seconds) {
    lateinit var second: LspClientImpl
    val clientsAfterRestart = CopyOnWriteArrayList<List<LspClientImpl>>()
    // the stop of all clients has marked the second client, and it stops the second client after the first client
    val firstDescriptor = descriptorWithNormalStopAction("FakeLspServerFirst") {
      @Suppress("RAW_RUN_BLOCKING")
      runBlocking { manager.restartAfterUnexpectedStop(second, manager.startRequestStamp())!!.join() }
      clientsAfterRestart.add(manager.getClients(FakeLspIntegrationProvider::class.java).toList())
    }
    val secondDescriptor = FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerSecond")
    awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, firstDescriptor) }
    second = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, secondDescriptor) }
    kill(second)
    awaitPendingRestart()

    withContext(Dispatchers.IO) { manager.stopClients(FakeLspIntegrationProvider::class.java) }

    assertEquals(listOf(listOf(second)), clientsAfterRestart.toList(), "a client stopped on request must not be restarted")
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of a replaced client cancels the pending restart of its server`() = timeoutRunBlocking(30.seconds) {
    val replaced = startClient(openFile("staleStop.txt"))
    stopAndWait { manager.stopRunningServer(replaced) }
    val current = awaitRunningClient { manager.ensureClientStarted(FakeLspIntegrationProvider::class.java, replaced.descriptor) }
    kill(current)
    val job = awaitPendingRestart()
    val requestStamp = manager.startRequestStamp()

    manager.stopRunningServer(replaced)

    assertTrue(job.isCancelled)
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    manager.restartAfterUnexpectedStop(current, requestStamp)!!.join()
    assertEquals(LspServerState.ShutdownUnexpectedly, current.state)
    assertSame(current, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a stop of a replaced client before the commit of the restart keeps the stopped client`() = timeoutRunBlocking(30.seconds) {
    val replaced = startClient(openFile("staleBeforeCommit.txt"))
    stopAndWait { manager.stopRunningServer(replaced) }
    val current = awaitRunningClient { manager.ensureClientStarted(FakeLspIntegrationProvider::class.java, replaced.descriptor) }
    kill(current)
    awaitPendingRestart()
    val requestStamp = manager.startRequestStamp()

    // the restart commits in a write action on the EDT, so the stop comes before the commit
    val restart = withContext(Dispatchers.EDT) {
      val restart = manager.restartAfterUnexpectedStop(current, requestStamp)
      manager.stopRunningServer(replaced)
      restart
    }
    restart!!.join()

    assertEquals(LspServerState.ShutdownUnexpectedly, current.state)
    assertSame(current, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `the client limit does not refuse the restart of a client`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(30.seconds) {
    val running = runningClients(disposable)
    val clients = List(10) { index ->
      val descriptor = FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServer$index")
      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)
      running.receive()
    }

    kill(clients[4])
    val restarted = running.receive()

    assertEquals(clients[4].getServerId(), restarted.getServerId())
    val expected = clients.toMutableList().also { it[4] = restarted }
    assertEquals(expected, manager.getClients(FakeLspIntegrationProvider::class.java).toList(), "the new client must take the position of the stopped client")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a stop of a replaced client does not block a later automatic restart`() = timeoutRunBlocking(30.seconds) {
    val file = openFile("replacedStop.txt")
    val replaced = startClient(file)
    val current = awaitRestart(replaced, file) { kill(replaced) }

    manager.stopRunningServer(replaced)

    assertSame(current, manager.getClients(FakeLspIntegrationProvider::class.java).single())
    awaitRestart(current, file) { kill(current) }
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a restart action on a replaced client does not block the automatic restart`() = timeoutRunBlocking(30.seconds) {
    val file = openFile("replaced.txt")
    val replaced = startClient(file)
    val current = awaitRestart(replaced, file) { kill(replaced) }

    manager.restartClient(replaced)

    assertSame(current, manager.getClients(FakeLspIntegrationProvider::class.java).single())
    awaitRestart(current, file) { kill(current) }
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = MAX_COUNT_KEY, value = "0")
  fun `a stop of all clients before the commit of the restart drops the restart`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val client = startClient(openFile("dropped.txt"))
      manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
      val requestStamp = manager.startRequestStamp()
      val added = CopyOnWriteArrayList<LspClient>()
      manager.addListener(object : LspClientManagerListener {
        override fun clientAdded(lspClient: LspClient) {
          added.add(lspClient)
        }
      }, disposable, false)

      // the restart commits in a write action on the EDT, so the stop comes before the commit
      val restart = withContext(Dispatchers.EDT) {
        val restart = manager.restartAfterUnexpectedStop(client, requestStamp)
        manager.stopClients(FakeLspIntegrationProvider::class.java)
        restart
      }
      restart!!.join()

      assertTrue(added.isEmpty(), "the stop must drop the start of the new client")
      assertTrue(manager.getClients(FakeLspIntegrationProvider::class.java).isEmpty())
    }

  @Test
  fun `a restart action on a stopped client starts a new client`() = timeoutRunBlocking(30.seconds) {
    val file = openFile("stopped.txt")
    val stopped = startClient(file)
    stopAndWait { manager.stopRunningServer(stopped) }

    awaitRestart(stopped, file) { manager.restartClient(stopped) }
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `the new client is initialized after the stopped client is disconnected`() = timeoutRunBlocking(30.seconds) {
    val events = CopyOnWriteArrayList<String>()
    val initializations = AtomicInteger()
    val initializedAgain = CompletableDeferred<Unit>()
    val descriptor = object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerOrdered") {
      override val lspServerListener = object : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
          events.add("initialized")
          if (initializations.incrementAndGet() == 2) initializedAgain.complete(Unit)
        }

        override fun serverStopped(shutdownNormally: Boolean) {
          events.add("stopping")
          // a slow listener gives a new client the time to start too early
          if (!shutdownNormally) Thread.sleep(300)
          events.add("stopped")
        }
      }
    }
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor) }

    // under read access, the IDE disconnects from the stopped server on a pooled thread
    ApplicationManager.getApplication().runReadAction { manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true) }
    initializedAgain.await()

    assertEquals(listOf("initialized", "stopping", "stopped", "initialized"), events.toList())
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a second restart of a replaced client does nothing`(@TestDisposable disposable: Disposable) = timeoutRunBlocking(30.seconds) {
    val file = openFile("unlisted.txt")
    val killed = startClient(file)
    kill(killed)
    awaitPendingRestart()
    val requestStamp = manager.startRequestStamp()
    val restarted = awaitRestart(killed, file) { assertNotNull(manager.restartAfterUnexpectedStop(killed, requestStamp)) }
    val added = CopyOnWriteArrayList<LspClient>()
    manager.addListener(object : LspClientManagerListener {
      override fun clientAdded(lspClient: LspClient) {
        added.add(lspClient)
      }
    }, disposable, false)

    manager.restartAfterUnexpectedStop(killed, requestStamp)!!.join()

    assertTrue(added.isEmpty(), "the second restart must not start a client")
    assertSame(restarted, manager.getClients(FakeLspIntegrationProvider::class.java).single())
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a client of an unloaded provider is not restarted`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("unloaded.txt"))
    kill(client)
    awaitPendingRestart()
    val requestStamp = manager.startRequestStamp()

    val unloaded = Disposer.newDisposable("LspAutoRestartTest")
    try {
      ExtensionTestUtil.maskExtensions(LspIntegrationProvider.EP_NAME, emptyList(), unloaded, fireEvents = false)
      assertNull(manager.restartAfterUnexpectedStop(client, requestStamp))
      assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    }
    finally {
      Disposer.dispose(unloaded)
    }
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = MAX_COUNT_KEY, value = "1")
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `an explicit restart clears the count`() = timeoutRunBlocking(30.seconds) {
    val file = openFile("clear.txt")
    val first = startClient(file)
    val second = awaitRestart(first, file) { kill(first) }
    val third = awaitRestart(second, file) { manager.restartClient(second) }

    val fourth = awaitRestart(third, file) { kill(third) }

    assertNotSame(third, fourth)
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = MAX_COUNT_KEY, value = "0")
  @RegistryKey(key = DELAY_KEY, value = "0")
  fun `a count of 0 turns the restart off`() = timeoutRunBlocking(30.seconds) {
    val client = startClient(openFile("off.txt"))
    val warnings = CopyOnWriteArrayList<String>()
    val warningCollector = object : LoggedErrorProcessor() {
      override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
        warnings.add(message)
        return true
      }
    }

    LoggedErrorProcessor.executeWith(warningCollector).use {
      manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
    }
    manager.pendingAutoRestartJobs().joinAll()

    assertEquals(LspServerState.ShutdownUnexpectedly, client.state)
    assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertTrue(warnings.none { "too often" in it }, "a turned-off restart must not log a warning: $warnings")
    stopClientsAndWait()
  }

  @Test
  fun `a client without autoRestartSupport is not restarted`() = timeoutRunBlocking(30.seconds) {
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project, optedIn = false)) }

    manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)

    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertEquals(LspServerState.ShutdownUnexpectedly, client.state)
    assertSame(client, manager.getClients(FakeLspIntegrationProvider::class.java).single(), "the stopped client must stay")
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a plugin that stops and starts its clients during the delay gets one client`() = timeoutRunBlocking(30.seconds) {
    val client = awaitRunningClient { manager.ensureStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project)) }
    kill(client)
    val job = awaitPendingRestart()

    manager.stopClients(FakeLspIntegrationProvider::class.java)
    val replacement = awaitRunningClient { manager.ensureClientStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project)) }

    assertTrue(job.isCancelled)
    assertTrue(manager.pendingAutoRestartJobs().isEmpty())
    assertNotSame(client, replacement)
    assertSame(replacement, manager.getClients(FakeLspIntegrationProvider::class.java).single())
    stopClientsAndWait()
  }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a plugin that stops and starts its clients before the commit of the restart gets one client`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val running = runningClients(disposable)
      manager.ensureStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project))
      val client = running.receive()
      kill(client)
      awaitPendingRestart()
      val requestStamp = manager.startRequestStamp()

      // the restart commits in a write action on the EDT, so the stop and the start come before the commit
      val restart = withContext(Dispatchers.EDT) {
        val restart = manager.restartAfterUnexpectedStop(client, requestStamp)
        manager.stopClients(FakeLspIntegrationProvider::class.java)
        manager.ensureClientStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project))
        restart
      }
      val replacement = running.receive()
      restart!!.join()

      assertNotSame(client, replacement)
      assertEquals(LspServerState.Running, replacement.state)
      assertSame(replacement, manager.getClients(FakeLspIntegrationProvider::class.java).single())
      stopClientsAndWait()
    }

  @Test
  @RegistryKey(key = DELAY_KEY, value = "60000")
  fun `a plugin that stops and starts its clients in serverStopped gets one client`(@TestDisposable disposable: Disposable) =
    timeoutRunBlocking(30.seconds) {
      val running = runningClients(disposable)
      val descriptor = PluginDescriptor(project, onUnexpectedStop = {
        manager.stopClients(FakeLspIntegrationProvider::class.java)
        manager.ensureClientStarted(FakeLspIntegrationProvider::class.java, PluginDescriptor(project))
      })
      manager.ensureStarted(FakeLspIntegrationProvider::class.java, descriptor)
      val client = running.receive()

      manager.handleMaybeUnexpectedServerStop(client, "test", serverGone = true)
      val replacement = running.receive()

      assertNotSame(client, replacement)
      assertTrue(manager.pendingAutoRestartJobs().isEmpty())
      assertSame(replacement, manager.getClients(FakeLspIntegrationProvider::class.java).single())
      stopClientsAndWait()
    }

  private fun openFile(name: String): VirtualFile = codeInsightFixture.configureByText(name, "hello").virtualFile

  private suspend fun startClient(file: VirtualFile): LspClientImpl {
    awaitFileOpenedByLspServer(project, file)
    return manager.getClients(FakeLspIntegrationProvider::class.java).single()
  }

  private fun kill(client: LspClientImpl) {
    (client.descriptor as FakeLspClientDescriptor).server.destroy()
  }

  /** Runs [action] and returns the new client once it has opened [expectedFile]. */
  private suspend fun awaitRestart(oldClient: LspClientImpl, expectedFile: VirtualFile, action: () -> Unit): LspClientImpl {
    val added = CompletableDeferred<LspClient>()
    val opened = CompletableDeferred<LspClient>()
    val disposable = Disposer.newDisposable("LspAutoRestartTest")
    try {
      manager.addListener(object : LspClientManagerListener {
        override fun clientAdded(lspClient: LspClient) {
          if (lspClient !== oldClient) added.complete(lspClient)
        }

        override fun fileOpened(lspClient: LspClient, file: VirtualFile) {
          if (lspClient !== oldClient && file == expectedFile) opened.complete(lspClient)
        }
      }, disposable, false)
      action()
      val newClient = added.await()
      assertSame(newClient, opened.await())
      return newClient as LspClientImpl
    }
    finally {
      Disposer.dispose(disposable)
    }
  }

  private fun descriptorWithNormalStopAction(name: String, onNormalStop: () -> Unit): FakeLspClientDescriptor =
    object : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = name) {
      override val lspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
          if (shutdownNormally) onNormalStop()
        }
      }
    }

  private suspend fun awaitRunningClient(start: () -> Unit): LspClientImpl {
    val running = CompletableDeferred<LspClient>()
    val disposable = Disposer.newDisposable("LspAutoRestartTest")
    try {
      manager.addListener(object : LspClientManagerListener {
        override fun serverStateChanged(lspClient: LspClient) {
          if (lspClient.state == LspServerState.Running) running.complete(lspClient)
        }
      }, disposable, false)
      start()
      return running.await() as LspClientImpl
    }
    finally {
      Disposer.dispose(disposable)
    }
  }

  /** Receives each client that reaches the Running state. */
  private fun runningClients(disposable: Disposable): Channel<LspClientImpl> {
    val running = Channel<LspClientImpl>(Channel.UNLIMITED)
    manager.addListener(object : LspClientManagerListener {
      override fun serverStateChanged(lspClient: LspClient) {
        if (lspClient.state == LspServerState.Running) running.trySend(lspClient as LspClientImpl)
      }
    }, disposable, false)
    return running
  }

  private suspend fun awaitPendingRestart(): Job {
    while (true) {
      manager.pendingAutoRestartJobs().singleOrNull()?.let { return it }
      delay(10.milliseconds)
    }
  }

  private suspend fun stopClientsAndWait(): Unit =
    stopAndWait { manager.stopClients(FakeLspIntegrationProvider::class.java) }

  private suspend fun stopAndWait(stop: () -> Unit) {
    val removed = CompletableDeferred<Unit>()
    val disposable = Disposer.newDisposable("LspAutoRestartTest")
    try {
      manager.addListener(object : LspClientManagerListener {
        override fun clientRemoved(lspClient: LspClient) {
          removed.complete(Unit)
        }
      }, disposable, false)
      stop()
      removed.await()
    }
    finally {
      Disposer.dispose(disposable)
    }
  }
}

/** A descriptor of a plugin that can restart its server itself. Each instance has the same server id. */
private class PluginDescriptor(
  project: Project,
  private val optedIn: Boolean = true,
  private val onUnexpectedStop: () -> Unit = {},
) : FakeLspClientDescriptor(project, LspCustomization(), null, null, presentableName = "FakeLspServerPlugin") {
  override val autoRestartSupport: Boolean get() = optedIn

  override val lspServerListener = object : LspServerListener {
    override fun serverStopped(shutdownNormally: Boolean) {
      if (!shutdownNormally) onUnexpectedStop()
    }
  }
}

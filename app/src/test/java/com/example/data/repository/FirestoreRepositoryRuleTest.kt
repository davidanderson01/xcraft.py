package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreRepositoryRuleTest : FirestoreEmulatorTestBase() {

  @Test
  fun testCreateAndReadScript_succeedsForOwner() = runBlocking {
    val uid = signInTestUser("user1@example.com")
    val repository = FirestoreRepository(firestore)

    val scriptId = withTimeout(5000L) {
      repository.saveScript(
        title = "Router Demo",
        code = "print('Hello from Xcraft')",
        description = "Agent router test",
        category = "agent"
      )
    }

    assertNotNull(scriptId)
    assertTrue(scriptId.isNotEmpty())

    val scripts = withTimeout(5000L) {
      repository.observeScripts().first()
    }
    assertTrue(scripts.any { it.title == "Router Demo" })
  }

  @Test
  fun testExecutionLog_succeedsForOwner() = runBlocking {
    val uid = signInTestUser("user2@example.com")
    val repository = FirestoreRepository(firestore)

    val logId = withTimeout(5000L) {
      repository.logExecution(
        command = "craftxpy.plugins.dns_validator google.com",
        output = "IP: 142.250.190.46",
        status = "SUCCESS",
        durationMs = 125.0
      )
    }

    assertNotNull(logId)
    val logs = withTimeout(5000L) {
      repository.observeExecutionLogs().first()
    }
    assertTrue(logs.any { it.command.contains("dns_validator") })
  }
}

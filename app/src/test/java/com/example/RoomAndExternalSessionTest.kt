package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AgentExecutionLogEntity
import com.example.data.local.AgentScriptEntity
import com.example.data.local.HierarchyNodeEntity
import com.example.data.local.HierarchyNodeType
import com.example.data.local.XcraftDatabase
import com.example.data.repository.SovereignWorkspaceRepository
import com.example.domain.security.InMemoryAesKeyProvider
import com.example.domain.telemetry.ExternalSessionTelemetryBridge
import com.example.domain.terminal.EncryptedEnvelopeCodec
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomAndExternalSessionTest {

    private lateinit var context: Context
    private lateinit var db: XcraftDatabase
    private lateinit var repository: SovereignWorkspaceRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = XcraftDatabase.createInMemory(context)
        repository = SovereignWorkspaceRepository(
            nodeDao = db.hierarchyNodeDao(),
            scriptDao = db.agentScriptDao(),
            logDao = db.agentExecutionLogDao(),
            firestoreRepository = null,
            auditLogger = null
        )
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `room entities persist hierarchy scripts logs and cascade delete cleanly`() = runBlocking {
        val rootNode = HierarchyNodeEntity(
            id = "n_root",
            parentId = null,
            name = "xcraftpy",
            nodeType = HierarchyNodeType.ROOT,
            relativePath = "xcraftpy/",
            humanExplanation = "Workspace root",
            agentSchemaRole = "ROOT_NAMESPACE"
        )
        db.hierarchyNodeDao().upsertNode(rootNode)

        val script = AgentScriptEntity(
            id = "s_1",
            nodeId = "n_root",
            hierarchyPath = "xcraftpy/agent.py",
            title = "Router Agent",
            description = "Routes prompts",
            code = "print('ok')",
            category = "agent"
        )
        db.agentScriptDao().upsertScript(script)

        val log = AgentExecutionLogEntity(
            id = "l_1",
            scriptId = "s_1",
            scriptTitle = "Router Agent",
            command = "run",
            maskedOutput = "ok",
            status = "SUCCESS",
            executionSurface = "PLAYGROUND",
            sessionId = "sess-1"
        )
        db.agentExecutionLogDao().insertLog(log)

        val rel = db.agentScriptDao().observeScriptsWithLogs().first()
        assertEquals(1, rel.size)
        assertEquals("Router Agent", rel[0].script.title)
        assertEquals(1, rel[0].logs.size)

        val manifest = repository.buildAgentSchemaManifest(
            nodes = db.hierarchyNodeDao().observeAllNodes().first(),
            scripts = db.agentScriptDao().observeAllScripts().first(),
            logs = db.agentExecutionLogDao().observeAllLogs().first()
        )
        assertTrue(manifest.contains("ROOT_NAMESPACE"))
        assertTrue(manifest.contains("Router Agent"))

        // Cascade delete check
        db.agentScriptDao().deleteScriptById("s_1")
        val remainingLogs = db.agentExecutionLogDao().observeAllLogs().first()
        assertTrue(remainingLogs.isEmpty())
    }

    @Test
    fun `encrypted envelope codec masks secrets enforces AES-GCM and detects tampering`() {
        val codec = EncryptedEnvelopeCodec(InMemoryAesKeyProvider())
        val rawSecret = "AIzaSyD-1234567890ABCDEFGHIJKLMNOPQRSTUVWX"
        val envelope = codec.seal(
            sessionId = "term-aes-1",
            sourceOrigin = "GITHUB",
            title = "Secret Script",
            rawPayload = "api_key = '$rawSecret'\nprint('ready')"
        )

        assertFalse(envelope.ciphertextBase64.contains(rawSecret))
        assertTrue(envelope.redactionsCount >= 1)

        val unsealed = codec.unseal(envelope)
        assertTrue(unsealed.sha256Verified)
        assertTrue(unsealed.gcmAuthenticated)
        assertFalse(unsealed.maskedPayload.contains(rawSecret))
        assertTrue(unsealed.maskedPayload.contains("[MASKED_GOOGLE_API_KEY]"))

        val tampered = envelope.copy(sha256Digest = "0000deadbeef")
        var failed = false
        try {
            codec.unseal(tampered)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun `external terminal launch and closing app transmits SESSION_ENDED back to terminal 1`() = runBlocking {
        val receivedEvents = CopyOnWriteArrayList<JSONObject>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/telemetry") { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            receivedEvents.add(JSONObject(body))
            val resp = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, resp.size.toLong())
            exchange.responseBody.use { it.write(resp) }
        }
        server.start()

        try {
            val port = server.address.port
            val codec = EncryptedEnvelopeCodec(InMemoryAesKeyProvider())
            val bridge = ExternalSessionTelemetryBridge(
                workspaceRepository = repository,
                codec = codec
            )

            val launchIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("xcraft://summon?session_id=term-live-77&source=LOCAL&target=Router&callback_port=$port")
            )
            bridge.attachSession(launchIntent)
            delay(350)

            assertTrue(bridge.sessionState.value.isActive)
            assertTrue(bridge.sessionState.value.launchedFromTerminal)
            assertEquals("term-live-77", bridge.sessionState.value.sessionId)

            bridge.recordActivityTelemetry(
                surface = "PLAYGROUND",
                action = "SCRIPT_RUN",
                scriptTitle = "Router Agent",
                command = "token=ghp_1234567890abcdefghijklmnop",
                rawOutput = "Executed cleanly",
                status = "SUCCESS",
                durationMs = 22.0
            )
            delay(350)

            val finalState = bridge.endSessionAndTransmit("WINDOW_CLOSED")
            assertFalse(finalState.isActive)
            assertTrue(finalState.endedAtMillis > 0L)

            val events = receivedEvents.map { it.getString("event") }
            assertTrue("Expected SESSION_STARTED in $events", events.contains("SESSION_STARTED"))
            assertTrue("Expected TELEMETRY_HEARTBEAT in $events", events.contains("TELEMETRY_HEARTBEAT"))
            assertTrue("Expected SESSION_ENDED in $events", events.contains("SESSION_ENDED"))

            val endJson = receivedEvents.last { it.getString("event") == "SESSION_ENDED" }
            assertEquals("term-live-77", endJson.getString("sessionId"))
            assertTrue(endJson.getString("message").contains("has ended"))

            val dbLogs = db.agentExecutionLogDao().observeAllLogs().first()
            assertTrue(dbLogs.any { it.status == "SESSION_ENDED" && it.sessionId == "term-live-77" })
            assertTrue(dbLogs.none { it.command.contains("ghp_1234567890abcdefghijklmnop") })
        } finally {
            server.stop(0)
        }
    }
}

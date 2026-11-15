# Xcraft.py — Sovereign Python AI Scripting & Agent Framework (Android)

Xcraft is a hardened Android workspace built with **Kotlin**, **Jetpack Compose**, **Material 3**, **Room**, and **Firebase**. It pairs a multi-model AI scripting studio and zero-vulnerability Python sandbox with a local Room persistence layer synced to Firestore, AES-256-GCM encrypted audit logging, and an external terminal launcher that pulls manifests directly from **GitHub** and receives live session telemetry.

---

## Key Capabilities

- **AI Studio (`ChatScreen`)**: Multi-model assistant routing (`Gemini 4 Argon` with High Thinking, `Gemini 3.8 Flash` with Google Search grounding, `Gemini 3.1 Flash-Lite`, image/video generation, and multimodal vision analysis) protected by **AI Agent Sovereign Armor**.
- **AI Agent Firewall (`AIAgentFirewall`)**: Cryptographic nonce boundary framing (`<user_untrusted_input nonce="...">`), invisible/RTL Unicode steganography stripping, prompt-injection neutralization, and post-flight secret redaction.
- **Python Script Playground (`PlaygroundScreen`)**: Sandboxed script editor and runner (`PythonSandboxEngine`) featuring static CVE/injection auditing (`eval`, `exec`, `pickle`, `os.system`, `shell=True`, `yaml.load`, zip-slip checks), DNS resolution, SSL/TLS certificate inspection, and strict command whitelisting.
- **Two-Sided Hierarchy & File Structure Planner (`CodebaseScreen` → `HierarchyPlannerScreen`)**:
  - Backed by Room (`hierarchy_nodes`, `agent_scripts`, `agent_execution_logs`) and synchronized with Firestore.
  - Guides users with layout presets (`Sovereign Modular`, `FastAPI Service`, `CLI Security Toolkit`), plain-English node explanations, and a dual-perspective view:
    - **Side 1 (Agent & Terminal Schema)**: Machine-readable JSON relational blueprint.
    - **Side 2 (Human Visual Guide)**: Collapsible plain-language folder tree with linked scripts, sync status, and execution counts.
- **Encrypted Activity Vault (`EncryptedActivityLogger` & `SecurityAuditScreen`)**:
  - Pre-masks API keys, GitHub PATs, JWTs, credentials, and PII with `SensitiveDataMasker` before encryption.
  - Seals records in tamper-evident, SHA-256 chained **AES-256-GCM** envelopes protected by Android Keystore (`RandomizedEncryptionRequired(true)` with Keystore-generated IVs).
- **External Terminal Summoner (`cli/` + `ExternalSessionTelemetryBridge`)**:
  - Opens the **full native Xcraft Android app cleanly** from a desktop terminal (`xcraft://summon?...`) after pulling and verifying repository metadata from **GitHub** (or the local Room/Firestore vault) over HTTPS TLS 1.3.
  - Continuously collects masked transmission telemetry while Xcraft is active and notifies Terminal 1 with a verified `[SESSION_ENDED]` event when the app window closes.

---

## Architecture

```text
├── app/src/main/java/com/example/
│   ├── MainActivity.kt                         # Attaches xcraft:// intents & reports lifecycle close
│   ├── data/
│   │   ├── local/
│   │   │   ├── XcraftEntities.kt               # Room entities: HierarchyNode, AgentScript, AgentExecutionLog
│   │   │   ├── XcraftDaos.kt                   # Reactive Flow DAOs + cascade relations
│   │   │   └── XcraftDatabase.kt               # Room database v1
│   │   ├── model/                              # Firestore models
│   │   ├── remote/GeminiService.kt             # Gemini multi-model router & firewall integration
│   │   └── repository/
│   │       ├── FirestoreRepository.kt          # Authenticated per-user Firestore persistence
│   │       └── SovereignWorkspaceRepository.kt # Room <-> Firestore sync + dual hierarchy manifests
│   ├── domain/
│   │   ├── codebase/                           # Embedded restored Xcraft.py package sources
│   │   ├── sandbox/PythonSandboxEngine.kt      # Static CVE auditor & sandboxed runner
│   │   ├── security/
│   │   │   ├── AIAgentFirewall.kt              # Prompt injection & output leak guard
│   │   │   ├── EncryptedActivityLogger.kt      # AES-256-GCM SHA-256 chained audit vault
│   │   │   └── SensitiveDataMasker.kt          # Deterministic secret & PII masker
│   │   ├── telemetry/
│   │   │   └── ExternalSessionTelemetryBridge.kt # Continuous session telemetry + SESSION_ENDED signal
│   │   └── terminal/
│   │       ├── EncryptedEnvelopeCodec.kt       # AES-256-GCM envelope seal/unseal + SHA-256
│   │       └── TerminalSummonService.kt        # GitHub HTTPS manifest fetcher & local vault streamer
│   └── ui/
│       ├── auth/AuthScreen.kt                  # Google Sign-In (Credential Manager) & Sovereign Offline Mode
│       ├── chat/ChatScreen.kt                  # Multi-model AI Studio
│       ├── codebase/CodebaseScreen.kt          # Restored source viewer & Hierarchy Planner toggle
│       ├── hierarchy/                          # HierarchyPlannerScreen + HierarchyPlannerViewModel
│       ├── library/ScriptLibraryScreen.kt      # Cloud & Room script library
│       ├── navigation/AppNavigation.kt         # 5-tab Material 3 navigation shell
│       ├── playground/PlaygroundScreen.kt      # Sandbox editor, auditor & runner
│       └── security/SecurityAuditScreen.kt     # Firewall tester, CVE list, encrypted logs & live bridge stats
└── cli/
    ├── xcraft_summon.py                        # Cross-platform GitHub terminal summoner & telemetry server
    ├── xcraft_summon.ps1                       # Windows PowerShell launcher wrapper
    └── xcraft_summon.sh                        # macOS / Linux launcher wrapper
```

---

## Summoning Xcraft from an External Terminal (GitHub Only)

Rather than embedding a simulated terminal inside the app, Xcraft launches natively via Android `ACTION_VIEW` deep links (`xcraft://summon`) from your desktop terminal.

### Prerequisites
- Android device or emulator connected over **ADB** (`adb` on `PATH`) with Xcraft installed (`com.aistudio.xcraft.pycore`).
- **Python 3.10+** (standard library only — no third-party pip packages required).
- Optional: [`scrcpy`](https://github.com/Genymobile/scrcpy) on `PATH` to spawn an adjustable mirrored Android window alongside your summoning terminal.

### Usage

**Linux / macOS:**
```bash
./cli/xcraft_summon.sh --source github --repo elevatecraft/xcraftpy
```

**Windows (PowerShell):**
```powershell
.\cli\xcraft_summon.ps1 -Source github -Target elevatecraft/xcraftpy
```

**Local Room/Firestore Vault Target:**
```bash
python3 cli/xcraft_summon.py --source local --target "Sovereign AgentRouter"
```

### How Session Launch & End Telemetry Works
1. **GitHub Fetch & Verify**: Terminal 1 queries `https://api.github.com/repos/<owner>/<repo>` over HTTPS, redacts any sensitive patterns, and calculates a SHA-256 digest of the manifest.
2. **Loopback Telemetry Bridge**: Terminal 1 binds a local HTTP listener on `127.0.0.1:<port>` and forwards it with `adb reverse tcp:<port> tcp:<port>` (permitted strictly for loopback addresses in `res/xml/network_security_config.xml`), alongside an `adb logcat -s XcraftSessionTelemetry` fallback watcher.
3. **Full App Launch**: `adb shell am start` invokes `xcraft://summon?...`, opening Xcraft normally at `AuthScreen` (`Sign in with Google` or `Explore Sovereign Mode`).
4. **Live Telemetry & Window Close Signal**: Every AI chat, audit, sandbox run, and Room/Firestore save transmits secret-masked telemetry packets. Closing/finishing the app triggers `ExternalSessionTelemetryBridge.endSessionAndTransmit`, persisting `SESSION_ENDED` in Room/Firestore and signaling Terminal 1:
   ```text
   [TERMINAL 1] Xcraft session 'xcraft-a1b2c3d4' has ended. Final transmission verified.
   ```

---

## Setup & Configuration

1. **Secrets & Environment**:
   - Copy `.env.example` to `.env` locally (or configure environment variables via your CI/Secrets manager). Never commit `.env`, `google-services.json`, or release keystores containing live secrets.
   - Set `GEMINI_API_KEY` if using direct Gemini API configuration alongside Firebase AI Logic.
2. **Firebase Authentication & Firestore**:
   - Users authenticate via **Google Sign-In (Jetpack Credential Manager)** backed by Firebase Auth.
   - `firestore.rules` enforces per-user isolation under `/users/{userId}/scripts` and `/users/{userId}/executions`.
   - Exploring without signing in operates cleanly in **Sovereign Mode (Offline)** using the local Room database and Android Keystore encrypted vault.

---

## Building & Testing

```bash
# Build debug APK
gradle :app:assembleDebug

# Run unit and Robolectric tests (Room, AES-256-GCM codec, Firewall, ActivityLogger, Session Bridge)
gradle :app:testDebugUnitTest

# Check CLI launcher options
python3 cli/xcraft_summon.py --help
```

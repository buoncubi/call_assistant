# Dialogue Manager (Finite-State Voice Dialogue) — Call Assistant Package

This package is part of a larger voice-call assistant architecture where an LLM can **handle phone calls in real time** using:
- **Speech-to-Text (S2T)** to transcribe the caller,
- **LLM** to generate responses,
- **Text-to-Speech (T2S)** to speak back to the caller.

This module specifically implements the **dialogue manager** as a **finite-state interaction loop** (listen → think → speak) with a key real-time constraint:

> **User always has priority**: if the user starts speaking while the assistant is speaking or the LLM is generating, the assistant is interrupted and the system immediately returns to listening (“barge-in”).

The core files are:
- `LLMDialogue.kt`: LLM dialogue utilities + AWS Bedrock implementation with summarization.
- `DialogueManager.kt`: orchestrates the end-to-end dialogue flow across S2T, LLM, and T2S.

---

## Main Features

- **Finite State Machine Dialogue Management:** Orchestrates the conversation between user and LLM, handling state transitions for listening, reasoning, and speaking.
- **User-First Priority (Barge-In):** If the user speaks while the LLM is responding, the LLM and TTS are immediately interrupted and the system switches to listening mode.
- **LLM-Driven Conversation:** The LLM greets the user, waits for input, and generates context-aware responses using prompt templates.
- **Speech-to-Text and Text-to-Speech Integration:** Seamlessly integrates with external STT and TTS services for real-time voice interaction.
- **Prompt and Message Management:** Maintains conversation history and dynamically manages system prompts, including context summarization for long conversations.
- **Rich Metadata Tracking:** Captures timing, confidence, interruption events, and token usage for each conversational turn.
- **Graceful Startup and Shutdown:** Handles activation, deactivation, and cleanup of all underlying services and callbacks.
- **Extensible and Testable:** Designed with extensibility in mind, allowing for easy adaptation to different LLMs or service providers.

---

## High-level Flow (Finite-State Interaction)

Even though there is no explicit `enum` state machine, the runtime behavior is a finite-state loop driven by callbacks:

1. **OPEN**
    - Activate services (S2T, T2S, LLM).
    - Speak a greeting (`"Pronto?"`).
    - Start S2T listening.

2. **LISTENING**
    - S2T detects speech start → triggers *barge-in handling* (stop T2S/LLM if active).
    - S2T produces a transcription → proceed to evaluation.

3. **EVALUATE TRANSCRIPTION**
    - If transcription confidence is **low** (< `0.4`), speak a hardcoded clarification message and return to listening.
    - Otherwise, forward the message to the LLM.

4. **THINKING (LLM)**
    - LLM is invoked with system prompts + message history.
    - Optionally triggers asynchronous summarization after enough turns.

5. **SPEAKING (T2S)**
    - LLM response is spoken via T2S.
    - When T2S finishes, metadata is finalized and the system returns to listening.

6. **INTERRUPT (BARGE-IN)**
    - At any time, if the user starts speaking:
        - stop LLM generation (if running),
        - stop T2S playback (if speaking),
        - record interruption metadata,
        - return to listening / processing user input.

The package is centered around two main classes:

1. **`LlmDialogue` (and its AWS implementation `AwsDialogue`):**
    - Abstracts the logic for interacting with an LLM service.
    - Manages prompt formatting, message history, and the lifecycle of LLM requests.
    - Handles conversation context summarization to keep the LLM informed without exceeding token limits.

2. **`DialogueManager`:**
    - Acts as the orchestrator, coordinating the STT, LLM, and TTS services.
    - Implements the FSM that governs the dialogue flow:
        - **Start:** Greets the user and begins listening.
        - **User Speaks:** Transcribes user speech; if confidence is low, asks for repetition.
        - **LLM Reasoning:** Sends user input and context to the LLM; handles response.
        - **Assistant Speaks:** Converts LLM response to speech and plays it.
        - **Barge-In Handling:** At any time, if the user starts speaking, interrupts the assistant and returns to listening.
    - Manages all callbacks, metadata, and error handling for each service.

**Typical Flow:**
1. System greets the user via TTS.
2. Listens for user's speech via STT.
3. On user input, transcribes and sends to LLM.
4. LLM generates a response; response is spoken via TTS.
5. User can interrupt at any time, immediately returning to listening mode.
6. Conversation continues until termination is detected or the call is closed.

---

## Package Structure

### `LlmDialogue<M, L : LlmService<*, *>>` (abstract)
Located in `LLMDialogue.kt`.

Responsibilities:
- Owns:
    - `PromptsManager` (system prompts formatting)
    - `MessagesManager<M>` (conversation history)
    - `LlmService` (async compute, callbacks, lifecycle)
- Provides:
    - `addUserMessage(...)`, `addAssistantMessage(...)`
    - lifecycle: `activate()`, `deactivate()`, `stop()`, `cancelScope()`
    - callback registration: `addOnResultCallback`, `addOnErrorCallback`
    - `store()` logs prompts and messages (todo !: persist somewhere)

Key method:
- `abstract fun converse()`: called when a new user message arrives; must invoke the LLM.

---

### `SystemPrompts`
A small configuration holder:

```kotlin
data class SystemPrompts(
  val promptsManager: PromptsManager,
  val callSections: List<String>,
  val summarySections: List<String>,
)
```

- `callSections`: prompt sections used for normal conversation turns
- `summarySections`: prompt sections used for summarization

---

### `AwsDialogue` (AWS Bedrock implementation)
Located in `LLMDialogue.kt`.

Responsibilities:
- Concrete `LlmDialogue` using:
    - `AwsBedrock` for main conversation (`llmService`)
    - another `AwsBedrock` instance for summarization (`llmSummary`)
- `converse()`:
    - formats prompts for conversation
    - sends message history to Bedrock
    - triggers summarization after `messages.size > 3` (current heuristic)

Summarization behavior:
- `doSummary()` builds a summarization input from `messagesManager.getSummaryInfo()`
- `invokeSummary()` calls the summary LLM asynchronously
- `onSummary()`:
    - stores the summary in `messagesManager.addSummary(...)`
    - updates `promptsManager.messageSummary` so future calls include the summary

Limitations / todo !s explicitly present:
- throttling handling: **AWS throttling errors not managed** (“troutling” typo in code)
- summarization parameters should be configurable and possibly use separate env vars
- concurrency: several mutex/runBlocking blocks are commented out; synchronization is incomplete
- `llmSummary` should be private (currently `protected`)
- message manager type complexity: todo ! suggests moving to string-only messages and converting to AWS objects here

---

## `DialogueManager` (Orchestrator)

Located in `DialogueManager.kt`.

### Responsibilities
- Coordinates:
    - `Speech2Text` (continuous listening + transcription callbacks)
    - `LlmDialogue` (conversation & history)
    - `Text2Speech` (playback)
- Implements barge-in: user speech start interrupts assistant speech and/or LLM generation.
- Records per-message metadata (timings, interruptions, confidence, token usage, latency).

### Construction (Factory)
Use the factory method to build an instance:

```kotlin
val manager = DialogueManager.build(
  speech2textBuilder = { /* Speech2Text impl */ },
  llmDialogueBuilder = { /* e.g., AwsDialogue(SystemPrompts(...)) */ },
  text2speechBuilder = { /* Text2Speech impl */ }
)
```

This pattern keeps dependencies private inside `DialogueManager`.

### Lifecycle
#### `open()`
`suspend fun open(sourceTag: String = UNKNOWN_SOURCE_TAG)`

- Activates S2T, T2S, and LLM dialogue.
- Speaks a greeting (`"Pronto?"`, todo !: parametrize).
- Waits for T2S completion (`text2speech.wait()`, todo !: add timeout).
- Starts S2T listening via `speech2text.computeAsync()` (todo !: add timeout).

#### `close()`
Stops computations, deactivates services, removes callbacks, cancels scopes.

Important behavior:
- Stops S2T first (it may run continuously).
- If T2S is stopped due to closure, it marks speech interruption metadata.
- If LLM is stopped due to closure, it marks LLM interruption metadata.

#### `store()`
Delegates to `llmDialogue.store()` which currently logs prompts/messages and has a todo ! to persist.

---

## Callback-Driven Dialogue Logic

### User starts speaking (barge-in)
Triggered by `speech2text.onStartTranscribingCallbacks`:

- `onUserStartsSpeaking()` → `userIsPotentialInterrupting()`:
    - if LLM computing: stop + mark `LLM_INTERRUPTED`
    - if T2S speaking: stop + mark `SPEECH_INTERRUPTED`
    - reset current assistant metadata pointer

### User message received
Triggered by `speech2text.onResultCallbacks`:

- Adds user message to `llmDialogue` history.
- Stores transcription timings and confidence in metadata.
- If confidence < `0.4`:
    - speaks: `"Non ho capito, può ripetere per favore?"`
    - does **not** call the LLM
- Else:
    - records `llmStartTime`
    - calls `llmDialogue.converse()`

### LLM response received
Triggered by LLM result callback:

- Adds assistant message to history.
- Stores:
    - LLM start/end timings
    - latency and token usage (`llmResponse.responseLatency`, `inputToken`, `outputToken`)
- Checks for termination marker `-!TERMINATED!-`:
    - assistant will not speak content after the marker
    - extracts a `callReport` (currently printed; todo !: handle properly)
- Speaks via T2S.

### T2S playback completed
Triggered by `text2speech.onResultCallbacks`:

- stores `SPEECH_END`
- resets current assistant metadata pointer

---

## Metadata Captured

`DialogueManager.MessageMetadata` attaches metadata to the *current* user/assistant message via `MessageWrapper.metadata`:

- Timings (`MetaTiming`):
    - `LISTEN_START`, `LISTEN_END`
    - `LLM_START`, `LLM_END`
    - `SPEECH_START`, `SPEECH_END`
- Attributes (`MetaAttribute`):
    - `HARDCODED` (greeting / low-confidence prompts)
    - `LLM_INTERRUPTED`
    - `SPEECH_INTERRUPTED`
- Data fields:
    - `transcriptionConfidence`
    - `llmLatency`
    - `llmInputToken`
    - `llmOutputToken`

---

## Configuration / Constants

In `DialogueManager`:
- `TRANSCRIPTION_CONFIDENCE_THRESHOLD = 0.4`
- low-confidence fallback message: `"Non ho capito, può ripetere per favore?"`
- termination key: `-!TERMINATED!-`

In `AwsDialogue`:
- Bedrock request parameters such as `temperature`, `maxTokens`, `topP`, `modelName` are stated to be provided via **environment variables** (exact names are defined elsewhere in the project).

---

## Usage Example (Typical Integration)

```kotlin
val promptsManager: PromptsManager = /* build/load prompt templates */
val systemPrompts = SystemPrompts(
  promptsManager = promptsManager,
  callSections = listOf("call_intro", "call_policy"),
  summarySections = listOf("summary_instruction")
)

val manager = DialogueManager.build(
  speech2textBuilder = { /* Speech2Text implementation (e.g., AWS Transcribe wrapper) */ },
  llmDialogueBuilder = { AwsDialogue(systemPrompts) },
  text2speechBuilder = { /* Text2Speech implementation */ }
)

// In a coroutine scope:
manager.open(sourceTag = "phone-call-123")

// ... run until external call termination ...
// manager.close()
// manager.store()
```

Notes:
- The module currently does **not** implement full call closure logic (explicit todo !).
- Termination detection exists via `TERMINATION_KEY`, but it only affects what is spoken and prints a report; it does not automatically close the call.

---

## Known Limitations / todo !s (Do Not Ignore)

From source comments and current behavior:

### Concurrency / Synchronization
- The project currently mixes threading concepts (`synchronized`) and coroutines.
- Multiple mutexes and `runBlocking` blocks are commented out.
- todo !: remove `synchronized` across the project and use structured concurrency (parent-child scopes).

### Timeouts / Robustness
- `text2speech.wait()` has todo ! to add timeout.
- `speech2text.computeAsync()` has todo ! to add timeout.
- Known S2T error to manage:
    - AWS Transcribe timeout when no audio is received for 15 seconds.

### AWS Bedrock Throttling
- todo !: handle throttling when LLM calls are too frequent (both for conversation and summarization).

### Summarization
- Summarization is triggered when `messages.size > 3` (hardcoded heuristic).
- todo !: parameterize this and consider skipping summary when transcription confidence is low (comment hints at this).
- todo !: separate env vars / configuration for summarization model parameters.

### Storage
- `LlmDialogue.store()` only logs prompts/messages.
- todo !: persist prompts/messages somewhere and ensure synchronization.

### Call Termination
- todo !: implement call closure.
- Termination marker parsing exists but is incomplete:
    - `callReport` is printed and not stored/returned.
    - no automatic shutdown is triggered.

---

## Extension Points

- Implement another `LlmDialogue` subclass for a different provider/model.
- Override in `AwsDialogue` for testing:
    - `invokeLlm(...)`
    - `invokeSummary(...)`
- Replace prompts and message management strategies via `PromptsManager` and `MessagesManager`.

---

## Development Notes

- `AwsDialogue` is `open` “only for testing purposes”.
- Several todo !s indicate planned refactors (message manager simplification, better logging, moving logs to a `.ktp` file, etc.).

---

## Summary

This package provides the **real-time dialogue orchestration** needed for voice calls with an LLM:
- callback-driven finite-state loop,
- barge-in support (user priority),
- message history + prompt formatting,
- optional summarization to keep context compact,
- detailed metadata capture for monitoring and analysis.

Before production use, prioritize the todo !s around **timeouts**, **throttling**, **call termination**, and **coroutine-safe synchronization**.
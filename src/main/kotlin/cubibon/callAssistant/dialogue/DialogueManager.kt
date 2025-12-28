package cubibon.callAssistant.dialogue

import cubibon.callAssistant.*
import cubibon.callAssistant.llm.LlmResponse
import cubibon.callAssistant.llm.message.*
import cubibon.callAssistant.speech2text.Speech2Text
import cubibon.callAssistant.speech2text.Transcription
import cubibon.callAssistant.text2speech.Text2Speech


// todo remove all `synchronized` blocks in the whole project. You should do not mix thread and coroutines

// todo adjust logs and move to `DialogueManager.ktp` file.

// todo manage error "[AwsTranscribe  ]:: Service experienced 'a computation error'....Your request timed out because no new audio was received for 15 seconds"


/**
 * Orchestrates a complete voice-based dialogue between a user and a Large Language Model (LLM).
 *
 * This class acts as the central controller, managing the flow of conversation by coordinating
 * three key services:
 * 1.  [Speech2Text]: Transcribes the user's spoken words into text.
 * 2.  [LlmDialogue]: Manages the conversation state and generates responses from the LLM based on user input.
 * 3.  [Text2Speech]: Converts the LLM's text responses into audible speech for the user.
 *
 * It handles the lifecycle of these services, including their activation, deactivation, and
 * asynchronous computation. The manager also handles complex interaction logic, such as:
 * - Greeting the user at the start of the dialogue.
 * - Interrupting the assistant's speech (barge-in) when the user starts speaking.
 * - Managing low-confidence transcriptions by asking the user to repeat.
 * - Capturing and storing rich metadata for each message exchange, including timings, latencies,
 *   and interruption events.
 *
 * The class is designed to be instantiated via the `build` factory method in its companion object
 * to ensure proper encapsulation of its components.
 *
 * @param speech2text The speech-to-text service instance.
 * @param text2speech The text-to-speech service instance.
 * @param llmDialogue The LLM dialogue management service instance.
 */
class DialogueManager private constructor(
    private val speech2text: Speech2Text,
    private val text2speech: Text2Speech,
    private val llmDialogue: LlmDialogue<*, *>,
) : Loggable() {

    /**
     * An instance of [CallbacksId].
     */
    private val callbacksId: CallbacksId


    /**
     * An instance of [MessageMetadata].
     */
    private val metadata = MessageMetadata()


    /**
     * Timestamp for the start of the LLM reasoning process.
     */
    private var llmStartTime: Long? = null // Used to store metadata on message manager.


    //private val servicesMutex = Mutex() // Used to assure proper service deactivation.


    /**
     * Initializes the [CallbacksId] instance by assigning speech-to-text, text-to-speech, and
     * LLM services.
     */
    init {
        // Add `speech2text` callbacks.
        val s2tStartId = speech2text.onStartTranscribingCallbacks.add { onUserStartsSpeaking() }
        val s2tResultId = speech2text.onResultCallbacks.add(::onUserMessage)
        val s2tErrorId = speech2text.onErrorCallbacks.add(::onSpeech2TextError)
        // Add `text2speech` callbacks.
        val t2sResultId =text2speech.onResultCallbacks.add{ onAssistantMessagePlayed() }
        val t2sErrorId = text2speech.onErrorCallbacks.add( ::onText2SpeechError)
        // Add `llmService` callbacks.
        val llmResultId = llmDialogue.addOnResultCallback( ::onLlmResponse)
        val llmErrorId = llmDialogue.addOnErrorCallback( ::onLlmError)
        // Store callback ID such to be able to remove it later.
        callbacksId = CallbacksId(s2tResultId, s2tStartId, s2tErrorId, t2sResultId, t2sErrorId, llmResultId, llmErrorId)
    }


    /**
     * Opens the dialogue by activating all services. And initialise the phone calls by making a greeting.
     */
    suspend fun open(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) {
        // Activate all services.
        speech2text.activate(sourceTag)
        text2speech.activate(sourceTag)
        llmDialogue.activate(sourceTag)

        // Greeting the user.
        val greeting = "Pronto?" //todo parametrize
        speechHardcodedMessage(greeting)
        logInfo("Dialogue opened.")
        text2speech.wait() //todo add timeout

        // Start listening the user.
        speech2text.computeAsync(sourceTag = sourceTag) // todo add timeout
    }


    /*
    suspend fun wait(timeoutSpec: Timeout? = null, sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) {
        speech2text.wait(timeoutSpec, sourceTag) // It must be the first one since it runs continuously !!! ???
        text2speech.wait(timeoutSpec, sourceTag)
        llmDialogue.wait(timeoutSpec, sourceTag)
    }
    // todo implement call termination*/


    /**
     * Stops all ongoing processes and releases all resources associated with the dialogue.
     *
     * This function performs a graceful shutdown of the dialogue manager by:
     * 1.  Stopping any active computations for speech-to-text, text-to-speech, and the LLM.
     * 2.  Deactivating all underlying services.
     * 3.  Removing all registered callbacks to prevent memory leaks and unexpected behavior.
     * 4.  Canceling the coroutine scopes of all services to ensure all background tasks are terminated.
     *
     * It is crucial to call this method when the dialogue is finished to ensure proper cleanup.
     *
     * @param sourceTag A string tag to identify the source of the call, used for logging and tracking.
     */
    fun close(sourceTag: String = ServiceInterface.UNKNOWN_SOURCE_TAG) {
        //servicesMutex.withLock {
            // Stop all
            if (speech2text.isComputing.get()) { // It must be the first one since it runs continuously !!! ???
                speech2text.stop(sourceTag)
            }
            if (text2speech.isComputing.get()) {
                text2speech.stop(sourceTag)
                metadata.setSpeechInterruptedAttribute()
            }
            if (llmDialogue.isComputing()) {
                llmDialogue.stop(sourceTag)
                metadata.setLlmInterruptedAttribute()
            }
        //}

        // Deactivate all services.
        speech2text.deactivate(sourceTag)
        text2speech.deactivate(sourceTag)
        llmDialogue.deactivate(sourceTag)

        // todo simplify by removing all callback with `clear` also on `LlmDialogue`
        // Remove all speech-to-text callbacks.
        speech2text.onResultCallbacks.remove(callbacksId.speech2textOnResult)
        speech2text.onStartTranscribingCallbacks.remove(callbacksId.speech2textOnStart)
        speech2text.onErrorCallbacks.remove(callbacksId.speech2textOnError)
        // Remove all text-to-speech callbacks.
        text2speech.onResultCallbacks.remove(callbacksId.text2speechOnResult)
        text2speech.onErrorCallbacks.remove(callbacksId.text2speechOnError)
        // Remove all LLM callbacks.
        llmDialogue.removeOnResultCallback(callbacksId.llmOnResult)
        llmDialogue.removeOnErrorCallback(callbacksId.llmOnError)

        // Cancel all coroutine scopes.
        speech2text.cancelScope()
        text2speech.cancelScope()
        llmDialogue.cancelScope()
        logInfo("Dialogue closed.")
    }


    /**
     * A shortcut for [LlmDialogue.store].
     */
    fun store() = llmDialogue.store()


    /**
     * Callback invoked when the user starts speaking.
     *
     * This function is triggered by the [Speech2Text] service as soon as it detects user speech,
     * even before the full transcription is available. Its primary role is to handle barge-in
     * scenarios by immediately stopping any ongoing assistant speech or LLM processing to ensure
     * the user is heard without delay.
     */
    // Callback invoked when the user started speaking even if the transcription is not ready yet.
    private fun onUserStartsSpeaking() {
        //servicesMutex.withLock {
            userIsPotentialInterrupting()
        //}
    }


    /**
     * Handles the transcribed user message from the speech-to-text service.
     *
     * This callback is invoked when a user has finished speaking and a transcription is available.
     * It orchestrates the next steps in the dialogue flow:
     * 1.  It first checks for and handles potential user interruptions (barge-in), stopping any
     *     ongoing assistant speech or LLM generation.
     * 2.  The user's transcribed message is added to the dialogue history.
     * 3.  Rich metadata, including transcription confidence and listening timings, is recorded
     *     for the user's message.
     * 4.  If the transcription confidence is below a defined threshold ([TRANSCRIPTION_CONFIDENCE_THRESHOLD]),
     *     it triggers a pre-defined "please repeat" message instead of querying the LLM.
     * 5.  If the confidence is sufficient, it initiates the LLM conversation process to generate a response.
     *
     * @param transcription The [Transcription] object containing the user's spoken text, confidence score,
     *                      and timing information.
     */
    // Called when speech-to-text process produces some outcomes, i.e., the user spoke.
    private fun onUserMessage(transcription: Transcription) {
        //servicesMutex.withLock {

            // Add the user message to the message manager.
            val metaUserMessage = llmDialogue.addUserMessage(transcription.message)
            if (metaUserMessage == null) {
                logError("Cannot make LLM reasoning on a `null` message.")
                // todo do something on this case
                return
            }

            // Stop the assistant (and manage metadata) if the user sent a new message.
            userIsPotentialInterrupting()

            // Set the metadata for the user message.
            metadata.setCurrentUser(metaUserMessage)
            metadata.setListenStartTiming(transcription.startTime)
            metadata.setListenEndTiming(transcription.endTime)
            metadata.setUserData(METADATA_TAG_CONFIDENCE, transcription.confidence)
            metadata.resetCurrentUser()

            // If the transcription is low, ask the user to repeat.
            if (transcription.confidence < TRANSCRIPTION_CONFIDENCE_THRESHOLD) {
                logInfo("User message transcription is not confident enough.")
                speechHardcodedMessage(LOW_CONFIDENCE_ASSISTANT_MESSAGE)
                return
            }

            // Start the LLM reasoning process.
            llmStartTime = System.currentTimeMillis() // It will be stored on the message metadata later.
            llmDialogue.converse()
        //}
    }


    /**
     * Handles user interruptions (barge-in).
     *
     * This function is called when the user starts speaking. It checks if the LLM is currently
     * generating a response or if the assistant is speaking. If either is active, this function
     * stops them to give priority to the user's new input. It also records the interruption
     * events and timings in the message metadata.
     */
    private fun userIsPotentialInterrupting() {
        // If LLM is serving a request stop it to give priority to the new user message.
        if (llmDialogue.isComputing()) {
            llmDialogue.stop()
            metadata.setLlmInterruptedAttribute()
            metadata.setLlmEndTiming()
        }

        // If the assistant is speaking, stop it to give priority to the new user message.
        if (text2speech.isComputing.get()) {
            text2speech.stop()
            metadata.setSpeechInterruptedAttribute()
            metadata.setSpeechEndTiming()
        }

        metadata.resetCurrentAssistant()
    }


    /**
     * Handles the response from the Large Language Model.
     *
     * This callback is invoked when the LLM service successfully generates a response. It performs
     * several key actions:
     * 1.  Adds the assistant's message to the dialogue history.
     * 2.  Records rich metadata for the message, including LLM start/end timings, latency, and
     *     token usage.
     * 3.  Checks the response content for a special `TERMINATION_KEY` to detect when the
     *     assistant intends to end the conversation. If found, it extracts the call report and
     *     prepares to close the dialogue.
     * 4.  Initiates the text-to-speech process to speak the assistant's message to the user.
     *
     * @param llmResponse The response object from the LLM, containing the message content and
     *                    performance metrics.
     */
    // Called when the LLM produces some outcomes.
    private fun onLlmResponse(llmResponse: LlmResponse) {
        //servicesMutex.withLock {
            val metaAssistantMessage = llmDialogue.addAssistantMessage(llmResponse.message)
            if (metaAssistantMessage == null) {
                logError("Cannot add LLM response to the dialogue since it is `null`.")
                return // todo do something in this case
            }

            // Store metadata to the Assistant's message.
            metadata.setCurrentAssistant(metaAssistantMessage)
            metadata.setLlmEndTiming()
            if (llmStartTime == null) {
                logError(
                    "Cannot set LLM start timing to metadata since it is `null`. Message ID: '{}'",
                    metaAssistantMessage.id
                )
            } else {
                metadata.setLlmStartTiming(llmStartTime!!)
                llmStartTime = null
            }
            metadata.setAssistantData(METADATA_TAG_LLM_LATENCY, llmResponse.responseLatency)
            metadata.setAssistantData(METADATA_TAG_LLM_INPUT_TOKEN, llmResponse.inputToken)
            metadata.setAssistantData(METADATA_TAG_LLM_OUTPUT_TOKEN, llmResponse.outputToken)

            // Detect when assistant want to terminate the call.
            // todo to test and terminate the call also when the user stop speaking
            val messageStr = messageToString(metaAssistantMessage)
            val terminationIdx = messageStr.indexOf(TERMINATION_KEY) - 1
            val toSay: String
            if (terminationIdx >= 0) {
                // Do not make the assistant say nothing after the `TERMINATION_KEY`
                toSay = messageStr.substring(0, terminationIdx)

                // Retrieve the report of the conversation.
                val callReport = messageStr.substring(terminationIdx)
                print("call report:\n$callReport")  // todo do somenthing with `callReport`
            } else {
                toSay = messageStr // The assistant says all the message
            }
            // Make the assistant speak normally.
            speech(toSay)
        //}
    }


    /**
     * Callback invoked when the text-to-speech process finishes playing the assistant's message.
     *
     * This function marks the completion of the assistant's speech turn by recording the
     * `SPEECH_END` timing in the current assistant message's metadata. It then resets the
     * `currentAssistant` message, preparing the metadata manager for the next conversational turn.
     */
    // Called when text-to-speech process has been played to the user.
    private fun onAssistantMessagePlayed() {
        //servicesMutex.withLock {
            metadata.setSpeechEndTiming()
            metadata.resetCurrentAssistant()
        //}
    }


    /**
     * Makes the assistant speak a predefined, hardcoded message.
     *
     * This method is used for system-driven messages that are not generated by the LLM,
     * such as greetings, error messages, or requests for clarification (e.g., when transcription
     * confidence is low).
     *
     * It adds the message to the dialogue history as an assistant message, marks it with a
     * `HARDCODED` attribute in its metadata, and then initiates the text-to-speech process.
     *
     * @param message The string message for the assistant to speak.
     */
    private fun speechHardcodedMessage(message: String) {
        val metaMessage = llmDialogue.addAssistantMessage(message)
        metadata.setCurrentAssistant(metaMessage)
        metadata.setHardcodedAttribute()
        speech(metaMessage)
    }


    /**
     * Converts a [MessageWrapper] to a string.
     */
    private fun messageToString(metaMessage: MessageWrapper<*>?): String {
        if (metaMessage == null) {
            logError("Cannot speak a `null` message.")
            return ""
        }

        val toSay = metaMessage.contents.joinToString(separator = ". ")

        return toSay
    }


    /**
     * Invokes [speech] where the input parameter is converted through [messageToString].
     *
     * @param metaMessage the message to be spoken.
     */
    private fun speech(metaMessage: MessageWrapper<*>?) {
        speech(messageToString(metaMessage))
    }


    /**
     * Asynchronously converts the given text to speech and plays it.
     *
     * This function initiates the text-to-speech process. If the process starts successfully,
     * it records the speech start time in the current assistant message's metadata.
     * It handles empty strings by simply returning without taking any action.
     *
     * @param toSay The string of text to be converted to speech. If empty, the function does nothing.
     */
    private fun speech(toSay: String) {

        if (toSay.isEmpty())
            return

        val speakingStarted = text2speech.computeAsync(toSay) // todo add timeout

        if (speakingStarted) {
            metadata.setSpeechStartTiming()
        }
    }


    /*
     * Callback to handle errors in the text-to-speech service.
     */
    private fun onText2SpeechError(serviceError: ServiceError) {
        //servicesMutex.withLock {
            // todo do something in this case (add on message metadata)
        //}
    }


    /*
     * Callback to handle errors in the LLM service.
     */
    private fun onLlmError(serviceError: ServiceError) {
        //servicesMutex.withLock {
            // todo do something in this case (add on message metadata)
        //}
    }


    /*
     * Callback to handle errors in the speech-to-text service.
     */
    private fun onSpeech2TextError(serviceError: ServiceError) {
        //servicesMutex.withLock {
            // todo do something in this case (add on message metadata)
        //}
    }


    /**
     * A data class to hold the unique identifiers for all callbacks registered
     * with the various services ([Speech2Text], [Text2Speech], [LlmDialogue]).
     *
     * This allows for easy removal of all registered callbacks when the [DialogueManager]
     * is closed, preventing memory leaks and unintended behavior.
     *
     * @property speech2textOnResult The ID for the `onResult` callback of the [Speech2Text] service.
     * @property speech2textOnStart The ID for the `onStartTranscribing` callback of the [Speech2Text] service.
     * @property speech2textOnError The ID for the `onError` callback of the [Speech2Text] service.
     * @property text2speechOnResult The ID for the `onResult` callback of the [Text2Speech] service.
     * @property text2speechOnError The ID for the `onError` callback of the [Text2Speech] service.
     * @property llmOnResult The ID for the `onResult` callback of the [LlmDialogue] service.
     * @property llmOnError The ID for the `onError` callback of the [LlmDialogue] service.
     */
    private data class CallbacksId(
        val speech2textOnResult: String, val speech2textOnStart: String, val speech2textOnError: String,
        val text2speechOnResult: String, val text2speechOnError: String,
        val llmOnResult: String, val llmOnError: String
    )


    /**
     * A helper class for managing and attaching metadata to user and assistant messages.
     *
     * This class acts as a stateful manager, holding references to the metadata of the
     * "current" user and assistant messages being processed in the dialogue. It provides a
     * convenient API to add specific types of metadata, such as:
     * - Timestamps for key events (e.g., `LISTEN_START`, `SPEECH_END`, `LLM_START`).
     * - Attributes for special states (e.g., `HARDCODED`, `LLM_INTERRUPTED`).
     * - Arbitrary data (e.g., transcription confidence, LLM token counts).
     *
     * It ensures that metadata is correctly associated with the appropriate message
     * (either user or assistant) and handles logging for cases where the target message
     * is not set.
     *
     * @property currentUser The metadata for the user message currently being processed.
     * @property currentAssistant The metadata for the assistant message currently being processed.
     */
    class MessageMetadata {

        /**
         * The metadata for the user message currently being processed.
         */
        private var currentUser: MetaData? = null


        /**
         * The metadata for the assistant message currently being processed.
         */
        private var currentAssistant: MetaData? = null


        /**
         * Sets the metadata for the user message.
         *
         * @param metaData The metadata to be associated with the user message.
         * @return The updated metadata for the user message.
         */
        fun setCurrentUser(metaData: MessageWrapper<*>?) {
            currentUser = storeMetaMessage(metaData, currentUser, USER_LOG_TAG)
        }


        /**
         * Sets the metadata for the assistant message.
         *
         * @param metaData The metadata to be associated with the assistant message.
         * @return The updated metadata for the assistant message.
         */
        fun setCurrentAssistant(metaData: MessageWrapper<*>?) {
            currentAssistant = storeMetaMessage(metaData, currentAssistant, ASSISTANT_LOG_TAG)
        }


        /**
         * Resets the metadata for the user message. It sets [currentUser] to `null`.
         */
        fun resetCurrentUser() {
            currentUser = null
        }


        /**
         * Resets the metadata for the assistant message. It sets [currentAssistant] to `null`.
         */
        fun resetCurrentAssistant() {
            currentAssistant = null
        }

        /**
         * Adds a timestamp to the current assistant message to identify when the message start to
         * be spoken.
         *
         * @return The updated metadata for the assistant message by the mean of [addTiming].
         */
        fun setSpeechStartTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.SPEECH_START, time, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a timestamp to the current assistant message to identify when the message end to
         * be spoken.
         *
         * @return The updated metadata for the assistant message by the mean of [addTiming].
         */
        fun setSpeechEndTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.SPEECH_END, time, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a timestamp to the current user message to identify when the message start to
         * be listened.
         *
         * @return The updated metadata for the user message by the mean of [addTiming].
         */
        fun setListenStartTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.LISTEN_START, time, currentUser, USER_LOG_TAG)


        /**
         * Adds a timestamp to the current user message to identify when the message end to
         * be listened.
         *
         * @return The updated metadata for the user message by the mean of [addTiming].
         */
        fun setListenEndTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.LISTEN_END, time, currentUser, USER_LOG_TAG)


        /**
         * Adds a timestamp to the current assistant message to identify when the LLM start to
         * be called.
         *
         * @return The updated metadata for the assistant message by the mean of [addTiming].
         */
        fun setLlmStartTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.LLM_START, time, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a timestamp to the current assistant message to identify when the LLM end to
         * be called.
         *
         * @return The updated metadata for the assistant message by the mean of [addTiming].
         */
        fun setLlmEndTiming(time: Long = System.currentTimeMillis()) =
            addTiming(MetaTiming.LLM_END, time, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a `HARDCODED` attribute to the current assistant message.
         *
         * @return The updated metadata for the assistant message by the mean of [setAttribute].
         */
        fun setHardcodedAttribute() = setAttribute(MetaAttribute.HARDCODED, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a `LLM_INTERRUPTED` attribute to the current assistant message.
         *
         * @return The updated metadata for the assistant message by the mean of [setAttribute].
         */
        fun setLlmInterruptedAttribute() = setAttribute(MetaAttribute.LLM_INTERRUPTED, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds a `SPEECH_INTERRUPTED` attribute to the current assistant message.
         *
         * @return The updated metadata for the assistant message by the mean of [setAttribute].
         */
        fun setSpeechInterruptedAttribute() = setAttribute(MetaAttribute.SPEECH_INTERRUPTED, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Adds data to the current user message.
         *
         * @param dataId The identifier for the data.
         * @param data The data to be added.
         * @return The updated metadata for the user message by the mean of [setData].
         */
        fun setUserData(dataId: String, data: Any) = setData(dataId, data, currentUser, USER_LOG_TAG)


        /**
         * Adds data to the current assistant message.
         *
         * @param dataId The identifier for the data.
         * @param data The data to be added.
         * @return The updated metadata for the assistant message by the mean of [setData].
         */
        fun setAssistantData(dataId: String, data: Any) = setData(dataId, data, currentAssistant, ASSISTANT_LOG_TAG)


        /**
         * Companion object for [DialogueManager].
         *
         * Provides a factory method `build` for creating [DialogueManager] instances and holds
         * private constants used throughout the class. Using a factory method ensures proper
         * encapsulation of the manager's components.
         */
        companion object: Loggable(DialogueManager::class.java) {

            /**
             * The log tag for user messages, i.e., `"user message"`.
             */
            private const val USER_LOG_TAG = "user message"


            /**
             * The log tag for assistant messages, i.e., `"assistant message"`.
             */
            private const val ASSISTANT_LOG_TAG = "assistant message"


            /**
             * Safely extracts and returns the [MetaData] from a [MessageWrapper].
             *
             * This function handles the assignment of metadata to a target variable (`toStore`).
             * It includes checks to prevent data loss by logging a warning if the target
             * `toStore` variable is already populated. It also handles null `metaMessage` inputs
             * gracefully by logging a warning and returning the existing `toStore` value.
             *
             * @param metaMessage The [MessageWrapper] from which to extract the metadata. Can be `null`.
             * @param toStore The existing [MetaData] object that is about to be overwritten. Used for logging potential data loss.
             * @param logTag A descriptive tag (e.g., "user message") for logging purposes.
             * @return The extracted [MetaData] object from `metaMessage`, or the original `toStore` value if `metaMessage` is `null`.
             */
            private fun storeMetaMessage(metaMessage: MessageWrapper<*>?, toStore: MetaData?, logTag: String): MetaData?{
                if (metaMessage == null) {
                    logWarn("Cannot store metadata for $logTag since it is `null`.")
                    return toStore
                }
                if (toStore != null) { //todo place it in a common function since it occurs several time
                    logWarn("Overwriting current metadata for $logTag. Lost data: '{}'", toStore)
                }
                return metaMessage.metadata // Returns `null` if `metaMessage` is `null`.
            }


            /**
             * Adds a timestamped event to a message's metadata.
             *
             * This function is a robust wrapper around [MetaData.addTiming]. It checks if the target
             * [MetaData] object (`toStore`) is non-null before attempting to add the timing. If `toStore`
             * is null, it logs a warning and returns without throwing an error, preventing crashes.
             *
             * @param tag The [MetaTiming] tag identifying the event (e.g., `SPEECH_START`).
             * @param now The timestamp for the event, defaulting to the current system time.
             * @param toStore The [MetaData] instance to which the timing should be added. This is typically
             *   the metadata of the current user or assistant message.
             * @param logTag A descriptive string (e.g., "user message") used in log messages to identify
             *   which message's metadata was being targeted.
             */
            private fun addTiming(tag: MetaTiming, now: Long = System.currentTimeMillis(), toStore: MetaData?, logTag: String) {
                if (toStore == null) {
                    logWarn("Cannot add timing metadata to $logTag since it is `null`.")
                    return
                }
                toStore.addTiming(tag, now)
            }


            /**
             * Adds a specific attribute to a message's metadata.
             *
             * This function handles the logic of adding an attribute, such as `MetaAttribute.HARDCODED`
             * or `MetaAttribute.LLM_INTERRUPTED`, to the provided metadata store. It includes a null check
             * to prevent errors if the metadata store (`toStore`) is not set, logging a warning in such cases.
             *
             * @param attribute The [MetaAttribute] to add to the metadata.
             * @param toStore The [MetaData] object to which the attribute should be added.
             * @param logTag A descriptive string (e.g., "user message") used in log messages for context.
             */
            private fun setAttribute(attribute: MetaAttribute, toStore: MetaData?, logTag: String) {
                if (toStore == null) {
                    logWarn("Cannot add attribute metadata to $logTag since it is `null`.")
                    return
                }
                toStore.addAttributes(attribute)
            }


            /**
             * Adds a key-value data pair to the specified metadata object.
             *
             * This function is a generic way to attach arbitrary data, such as transcription
             * confidence scores or LLM token counts, to a message's metadata. If the target
             * metadata object (`toStore`) is `null`, it logs a warning and does nothing.
             *
             * @param dataId The key (a string) to identify the data.
             * @param data The value of the data to be stored.
             * @param toStore The [MetaData] object to which the data should be added.
             * @param logTag A descriptive tag (e.g., "user message") used for logging purposes.
             */
            private fun setData(dataId: String, data: Any, toStore: MetaData?, logTag: String) {
                if (toStore == null) {
                    logWarn("Cannot add data to $logTag since it is `null`.")
                }
                toStore?.addData(dataId, data)
            }
        }
    }


    /**
     * Factory for creating [DialogueManager] instances and container for constants.
     *
     * This companion object provides the `build` method, which is the designated way to
     * construct a [DialogueManager]. This pattern ensures that the internal components
     * ([Speech2Text], [LlmDialogue], [Text2Speech]) are properly encapsulated and
     * instantiated only when a `DialogueManager` is created.
     *
     * It also holds private constants used throughout the `DialogueManager` for configuration,
     * such as transcription confidence thresholds, metadata keys, and special control strings.
     */
    companion object {

        /**
         * Factory method for creating a [DialogueManager] instance.
         *
         * This function ensures proper encapsulation of the [DialogueManager]'s components
         * by constructing it with services created from the provided builder lambdas.
         * Using this factory method is the designated way to instantiate a [DialogueManager].
         *
         * @param speech2textBuilder A lambda function that returns an instance of [Speech2Text].
         * @param llmDialogueBuilder A lambda function that returns an instance of [LlmDialogue].
         * @param text2speechBuilder A lambda function that returns an instance of [Text2Speech].
         * @return A new instance of [DialogueManager] configured with the provided services.
         */
        // Done to assure that properties are private within DialogueManager and not accessible from the class that instance it.
        fun build(
            speech2textBuilder: () -> Speech2Text,
            llmDialogueBuilder: () -> LlmDialogue<*, *>,
            text2speechBuilder: () -> Text2Speech
        ): DialogueManager {
            return DialogueManager(speech2textBuilder(), text2speechBuilder(), llmDialogueBuilder())
        }

        /**
         * The confidence threshold for transcription success. By default it is `0.4`.
         */
        private const val TRANSCRIPTION_CONFIDENCE_THRESHOLD = 0.4

        /**
         * The key for transcription confidence in metadata. By default it is `transcriptionConfidence`.
         */
        private const val METADATA_TAG_CONFIDENCE = "transcriptionConfidence"

        /**
         * The message to be spoken when the transcription confidence is low. By default it is
         * `"Non ho capito, può ripetere per favore?"`
         */
        private const val LOW_CONFIDENCE_ASSISTANT_MESSAGE = "Non ho capito, può ripetere per favore?"

        /**
         * The key for LLM latency in metadata. By default it is `"llmLatency"`.
         */
        private const val METADATA_TAG_LLM_LATENCY = "llmLatency"

        /**
         * The key for LLM input token count in metadata. By default it is `"llmInputToken"`.
         */
        private const val METADATA_TAG_LLM_INPUT_TOKEN = "llmInputToken"

        /**
         * The key for LLM output token count in metadata. By default it is `"llmOutputToken"`.
         */
        private const val METADATA_TAG_LLM_OUTPUT_TOKEN = "llmOutputToken"

        /**
         * The key for call termination in metadata. By default it is `"-!TERMINATED!-"`.
         */
        private const val TERMINATION_KEY = "-!TERMINATED!-"
    }

}

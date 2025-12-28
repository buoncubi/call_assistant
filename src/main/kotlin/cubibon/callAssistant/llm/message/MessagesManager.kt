package digital.boline.callAssistant.llm.message

import digital.boline.callAssistant.CentralizedLogger
import digital.boline.callAssistant.Loggable
import digital.boline.callAssistant.Utils
import kotlin.math.log


/**
 * The manager of a collection of messages between the user and the LLM-based assistant. It is designed to be used with
 * different LLM providers, since messages type `M` can be customized.
 *
 * It also implements a summarization mechanism, which is used to reduce the number of messages sent to the LLM, while
 * maintaining the context related to previous messages. At the same time, it conserves the references to all messages
 * exchanged between the user and the assistant for logging purposes. For this reason it manages a single list of
 * message, but provides two different views of it.
 *
 * Each message is represented through a list of string [MessageWrapper.contents], a [MessageWrapper.role] and
 * [MessageWrapper.metadata]. The `contents` are the actual message text, `metadata` contains data for logging purposes,
 * while `role` can be of type:
 *  - [MetaRole.USER]: when the message is given by the `user`.
 *  - [MetaRole.ASSISTANT]: when the message is given by the LLM-based `assistant`.
 *  - [MetaRole.SUMMARY]: a synthetic message that summarize previous `user` and `assistant` messages, and that should be
 *    given to the LLM model through its prompt.
 * Note that typical LLM provides requires messages to alternate only between `user` (which should be the first one) and
 * `assistant`. Also, note that the contests of the `summary` can be obtained with the help of the LLM instructed with
 * a specific prompt, which is different from the one used to converse with the user.
 *
 * To implement this behaviour, this class manages an internal [mutableMessages] (i.e., a list of
 * [MessageWrapper.Builder]), which contains all information about messages, their role, and metadata. Such a list is
 * paired with an index (i.e., [firstLlmMessageIdx]) that points to the first message that should be given to the
 * LLM model, which coincide with the last summarization message, and it is updated over time. By exploiting this
 * representation, the `MessageManager` can indeed provide two different set of message, which are:
 *  - [metaMessages]: is an immutable reference to [mutableMessages] which contains all information for logging
 *    purposes, and it can be stored on AWS DynamoDB though [toDynamoDB];
 *  - [messages]: is an immutable copy of [mutableMessages] which contains only the messages that should be given to the
 *    LLM model. This list would neither contain the `summary` messages nor elements related to messages previously
 *    summarized.
 *
 * For generality, this class leverage the factory paradigm and a lambda function to allow specifying the type of
 * messages that are constructed and managed. For this reason, the constructor of this class should be something similar
 * to (see [LlmMessage.Builder] [MessageWrapper.Builder] for more):
 * ```
 * val manager = MessageManager { logger: CentralizedLogger ->
 *         MyWrapper<M>.build(MyMessage.build(logger), logger)
 *     }
 * ```
 * Note that this lambda function is also in charge to propagate the logger from the `MessageManager` to the messages.
 *
 * Messages to be managed can be introduced with the [addUser] and [addAssistant] methods, and the
 * implementation is such to assure that:
 * - `user` and `assistant` message are always alternated. If two consecutive messages have the same role,
 *   then they will be merged into a single message.
 * - the first message has the `user` role, even after a summarization process. If the very first message is of
 *   type `assistant`, then a fake message (of type `user` and contents equal to [FAKE_MESSAGE_CONTENTS]) is added, but
 *   it will not be considered during summarization.
 * - the last `user` message that has not been addressed by the LLM yet (i.e., there is no following
 *   messages of type `assistant`) is never summarized.
 *
 * To perform the summarization process, you should follow these steps:
 *  1. use [getSummaryInfo] to get the information about the messages that should be summarized
 *  2. use the LLM model to generate the message summarization
 *  3. use [addSummary] to addNewMessage the summarization message and update the list of messages accordingly.
 *
 * Note that the [addUser], [addAssistant] and [addUser] returns a pointer to the new constructed
 * message which should be used to store related metadata.
 *
 * Each message is associated with a unique identifier, and contains some metadata, which encompass
 *  - a list of [MetaAttribute] to tag fake messages, and messages that have been merged,
 *  - a map of [MetaTiming], which contains time instants for some events (e.g., creation, play, etc.),
 *  - a list of identifiers, which occurs on summary messages to identify which messages have been summarized,
 *  - a `Map<String, Any>`, which contains auxiliary data that can be set from the classes that uses the
 *    `MessagesManager`.
 * Check also [MetaMessage] for more information about metadata.
 *
 * The [metaMessages] can be converted to a map with [toMessagesMap], which should be used to store information on a
 * NoSQL database for logging purposes (e.g., see [DynamoDBMessage] to convert the message map into a map that can be
 * stored on AWS Dynamo DB). Such a map can be given for all messages or in an incremental manner. Also, such a map can
 * include or exclude the last message, which might be merged with future messages (i.e., if it is not excluded and
 * merged, then some information can be lost).
 *
 * @constructor Requires a factory-based lambda function that is able to create instances of [MessageWrapper.Builder],
 * which in turn build instances of [LlmMessage.Builder]. This lambda function is used all the times a new message is
 * added to the list of messages through [addUser], [addAssistant], [addSummary]. Also note that this lambda function
 * should propagate the logger from the `MessageManager` to the instantiated messages.
 *
 * @param M The type of message as required by the LLM provider
 *
 * @property messageBuilder The builder for creating new messages. However, this property is private, and used by
 * [addUser], [addAssistant], [addSummary].
 *
 * @property mutableMessages The internal list of messages as a list of [MessageWrapper.Builder]. It contains all
 * information manged by this class. However, this list is private and not directly accessible to external objects,
 * but only through the [metaMessages] and [messages] properties.
 *
 * @property firstLlmMessageIdx The index that points to the fist message to be given to the LLM. In other words, it is
 * -1 when of the message list is empty. It is 0 when there are no summary message, points to the first not summarized
 * message otherwise. This index is updated over time by [addUser], [addAssistant], [addSummary]. In some case, (e.g.,
 * in case of consecutive summarization) it might point out of list bound, thus, be mindfully of index range.
 * Nevertheless, this index is private and internally managed by this class. When printing this object, this index is
 * denoted with the symbol `*`.
 *
 * @property lastSummaryMessageIdx The index within the [mutableMessages] list of the last message that is the result
 * of a summarization process. It is -1 when no summarization occurred jet. This index is updated over time by
 * [addSummary]. Nevertheless, this index is private and internally managed by this class. When
 * printing this object, this index is denoted with the symbol `^`.
 *
 * @property toMessagesMap The index of elements in [messageMapIdx] that have been mapped into a map for storing on NoSQL
 * database through [toMessagesMap] with `incremental` flag set to `true`. When printing this object, this index is
 * denoted with the symbol `-`. This property is private and internally managed by this class.
 *
 * @property metaMessages An immutable reference to [mutableMessages] which contains all information for logging
 * purposes, and it can be converted to a map with [toMessagesMap].
 *
 * @property messages An immutable copy of [mutableMessages] which contains only the messages that should be given to
 * the LLM model (i.e., messages of type `M` computed through [MessageWrapper.rawMessage], which in turn wraps
 * [LlmMessage.getRawMessage]). This list would neither contain the `summary` messages nor elements related to messages
 * previously summarized. In other words, it is related to the elements of [mutableMessages] within the range
 * `[firstLlmMessageIdx, mutableMessages.size)`.
 *
 * @see MessageWrapper
 * @see MessageWrapper.Builder
 * @see LlmMessage
 * @see LlmMessage.Builder
 * @see MessageWrapper
 * @see AwsMessage
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class MessagesManager<M>(private val messageBuilder: (logger: CentralizedLogger) -> MessageWrapper.Builder<M>) : Loggable() {

    companion object {
        /**
         * The fake message contents that is used when the first message is of type `assistant`.
         */
        const val FAKE_MESSAGE_CONTENTS = "..."
    }


    /* It is update when messages are added, and it is -1, when `mutableMessages` is empty; 0, where no summarization
       has been yet performed; points to the last not summarized message otherwise. In some case, (e.g., in case of
       consecutive summarization, or when no new messages have been added after summarization?) it might point out of
       list bound; thus, be mindfully of index range.
     */
    private var firstLlmMessageIdx = -1


    // Used by the `toMessagesMap` function for storing data in a NoSQL database.
    private var messageMapIdx = -1


    // The list where all data is actually stored.
    private val mutableMessages = mutableListOf<MessageWrapper.Builder<M>>()


    // The pointer to the last summarization message or -1 if no summarization occurred jet.
    private var lastSummaryMessageIdx = -1


    // The immutable list of all messages, which is maintained for logging purposes.
    val metaMessages: List<MessageWrapper<M>>
        get() = mutableMessages.map { it.public() } // It does not return a copy


    /* Am immutable copy of some messages in `mutableMessage` that can be given to the LLM model.
       This list would never contain summarising messages, it has to start with the user role, and it must alternate
       user and assistant role.
     */
    val messages: List<M>
        get() {
            if (firstLlmMessageIdx < 0) {
                logWarn("No messages have been added yet. Returning empty list.")
                return emptyList()
            } else if (firstLlmMessageIdx >= mutableMessages.size) {
                // It can occur after summarization (or it might be an internal error?).
                logWarn("No new messages after summarization.")
                return emptyList()
            } else {
                return mutableMessages.subList(firstLlmMessageIdx, mutableMessages.size).mapNotNull {
                    if (it.public().role != MetaRole.SUMMARY)
                        return@mapNotNull it.public().rawMessage
                    null
                }
            }
        }


    // TODO simplify and remove dependency from M, the DialogueManager (or LLMRequest object) will transform it for a specific AWS provider.
    val messagesStr: List<MessageWrapper<*>>
        get() {
            if (firstLlmMessageIdx < 0) {
                logWarn("No messages have been added yet. Returning empty list.")
                return emptyList()
            } else if (firstLlmMessageIdx >= mutableMessages.size) {
                // It can occur after summarization (or it might be an internal error?).
                logWarn("No new messages after summarization.")
                return emptyList()
            } else {
                return mutableMessages.subList(firstLlmMessageIdx, mutableMessages.size).mapNotNull {
                    if (it.public().role != MetaRole.SUMMARY)
                        return@mapNotNull it.public()  // TODO make sure that it returns a copy
                    null
                }
            }
        }


    /**
     * Converts [metaMessages] message to a formatted string. Also, it shows the position of [firstLlmMessageIdx] with
     * `"*"`, and  [messageMapIdx] with `"-". If these two symbols are missing it means that they are pointing to the
     * next element, which does not exist yet.
     * @return String
     */
    override fun toString(): String {
        return StringBuilder().apply {
            /*append(
                message2string(
                    prefix = "\tAWS Bedrock messages:$NEW_LINE\t\t",
                    msg = messages, separator = ", $NEW_LINE\t\t",
                    postfix = "."
                )
            )*/
            var cnt = -1
            val title = "META MESSAGES ('*' -> first message to LLM, '-' -> next message to convert into a Map, '^' -> last summary message):"
            var idx1Printed = false
            var idx2Printed = false
            var idx3Printed = false
            if (metaMessages.isNotEmpty()){
                append(
                    message2string(
                        prefix = "${NEW_LINE}\t$title$NEW_LINE",
                        msg = metaMessages, separator = ",$NEW_LINE",
                        postfix = ".", toStr = {
                            val idx1 = if (metaMessages.indexOf(it) == firstLlmMessageIdx) {
                                idx1Printed = true
                                "*"
                            } else ""
                            val idx2 = if (metaMessages.indexOf(it) == messageMapIdx) {
                                idx2Printed = true
                                "-"
                            } else ""
                            val idx3 = if (metaMessages.indexOf(it) == lastSummaryMessageIdx) {
                                idx3Printed = true
                                "^"
                            } else ""
                            cnt++
                            "$cnt\t$idx1$idx2$idx3\t$it"
                        }
                    )
                )
            } else {
                append("\t\t---")
            }
            val outIndex = StringBuilder()
            if (!idx1Printed) outIndex.append("'*' -> index $firstLlmMessageIdx")
            if (!idx2Printed) {
                if (outIndex.isNotEmpty()) outIndex.append(", ")
                outIndex.append("'-' -> index $messageMapIdx")
            }
            if (!idx3Printed) {
                if (outIndex.isNotEmpty()) outIndex.append(", ")
                outIndex.append("'^' -> index $lastSummaryMessageIdx")
            }
            if (outIndex.isNotEmpty())
                append("$NEW_LINE .  $outIndex.")
        }.toString()
    }


    /**
     * Add a new message of type [MetaRole.USER] (see [addMessage] for more info). The message cannot be added with empty
     * `contents`.
     *
     * @param contents The set of strings associated with the message to addNewMessage.
     * @return The new message added, or `null` if the message cannot be added. The returned value should be used to
     * update the [MessageWrapper.metadata] associated with this message.
     */
    fun addUser(contents: List<String>) = addMessage(MetaRole.USER, contents)


    /**
     * Add a new message of type [MetaRole.ASSISTANT] (see [addMessage] for more info). The message cannot be added with
     * empty `contents`.
     *
     * @param contents The set of strings associated with the message to addNewMessage.
     * @return The new message added, or `null` if the message cannot be added. The returned value should be used to
     * update the [MessageWrapper.metadata] associated with this message.
     */
    fun addAssistant(contents: List<String>) = addMessage(MetaRole.ASSISTANT, contents)


    /**
     * Add a new message of type [MetaRole.USER] (see [addMessage] for more info). The message cannot be added with
     * empty `contents`.
     *
     * @param contents The string associated with the message to addNewMessage.
     * @return The new message added, or `null` if the message cannot be added. The returned value should be used to
     * update the [MessageWrapper.metadata] associated with this message.
     */
    fun addUser(contents: String) = addMessage(MetaRole.USER, listOf(contents))


    /**
     * Add a new message of type [MetaRole.ASSISTANT] (see [addMessage] for more info). The message cannot be added with
     * empty `contents`.
     *
     * @param contents The string associated with the message to addNewMessage.
     * @return The new message added, or `null` if the message cannot be added. The returned value should be used to
     * update the [MessageWrapper.metadata] associated with this message.
     */
    fun addAssistant(contents: String) = addMessage(MetaRole.ASSISTANT, listOf(contents))


    /**
     * Add a new message to the [mutableMessages] list, which will have the given `role` and string `contents`. This
     * function is publicly available through [addAssistant] and [addUser].
     *
     * The new message is constructed through the factory-based lambda function provided to the constructor as
     * [messageBuilder]. See [MessageWrapper.Builder] and [LlmMessage.Builder] for more.
     *
     * It is designed to assure that:
     *  - `user` and `assistant` message are always alternated. If two consecutive messages have the same role,
     *    then they will be merged into a single message.
     *  - the first message has the `user` role, even after a summarization process. If the very first message is of
     *    type `assistant`, then a fake message (of type `user` and contents `"..."`) is added, but it will not be
     *    considered during summarization.
     *
     *
     * Note that a message cannot be added if the `role` is of type [MetaRole.SUMMARY] (see [addSummary] for adding
     * these types of messages); in this case the function returns `null`. Also, note that blank content in the
     * `contents` list will not be added to the message.
     *
     * @param role The [MetaRole] to be assigned with the new message. It cannot be of type [MetaRole.SUMMARY].
     * @param contents The string-based set of contents for the new message. It cannot be empty, and blank elements
     * would not be added.
     * @return The new message added to [mutableMessages]. The returning value should be used to set the associated
     * [MessageWrapper.metadata].
     */
    fun addMessage(role: MetaRole, contents: List<String>): MessageWrapper<M>? {

        if (contents.isEmpty()) {
            logError("Cannot addNewMessage message with empty contents.")
            return null
        }

        if (role == MetaRole.SUMMARY) {
            logError("Cannot addNewMessage summary message explicitly.")
            return null
        }

        val addedMessage =
            if (mutableMessages.isEmpty() || (mutableMessages.size < firstLlmMessageIdx)) {
                // If it is the very first message or the first message after summarization Note that after
                // summarization firstLlmMessageIdx point to the next element which might not be in the list yet.
                addFistMessage(role, contents)
            } else {
                addFollowingMessage(role, contents)
            }

        return addedMessage.public()
    }


    /**
     * Helping function for [addMessage], which takes care of the very first message, or the first message after the
     * summarization process.
     *
     * Also, it is charge to increment the [firstLlmMessageIdx] such to always point to the very first message or to the
     * first message after the summarization process (i.e., to the first message to be given to the LLM model through
     * [messages] over time).
     *
     * It assures that the first message is of type `user`. Otherwise, it creates a fake message with contents `"..."`,
     * which is not considered during summarization.
     *
     * @param role The [MetaRole] to be assigned with the new message. It would never be of type [MetaRole.SUMMARY].
     * @param contents The string-based set of contents for the new message. It would never be empty.
     * @return The new message added to [mutableMessages].
     */
    private fun addFistMessage(role: MetaRole, contents: List<String>): MessageWrapper.Builder<M> {
        if (role == MetaRole.ASSISTANT) {
            logWarn("The first message cannot be of type 'ASSISTANT', fake message added.")

            // Adding fake message.
            //firstLlmMessageIdx += 1 // TODO? Avoiding adding fake messages to the list of messages for the LLM model.
            val fakeMessage = addNewMessage(MetaRole.USER, listOf(FAKE_MESSAGE_CONTENTS))
            fakeMessage.public().metadata.addAttributes(MetaAttribute.FAKE)
        }

        // Adding the actual message normally and return the just created message
        firstLlmMessageIdx += 1
        return addNewMessage(role, contents)
    }


    /**
     * Helping function for [addMessage], which takes care of the messages following the very first message, or
     * following the first message after the summarization process.
     *
     * It assures that the messages role always alternates between [MetaRole.USER] and [MetaRole.ASSISTANT]. If two consecutive
     * messages have the same role, then it merge them in a single message.
     *
     * @param role The [MetaRole] to be assigned with the new message. It would never be of type [MetaRole.SUMMARY].
     * @param contents The string-based set of contents for the new message. It would never be empty.
     * @return The new message added to [mutableMessages], or the message where `contents` has been merged.
     */
    private fun addFollowingMessage(role: MetaRole, contents: List<String>): MessageWrapper.Builder<M> {
        // Get last not summary message.
        val previousMessageIdx = getLasMessage { it.public().role != MetaRole.SUMMARY }
        // If there is a last not summary message.
        if (previousMessageIdx > 0) {

            // If the last not summary message has the same `role` as the given one.
            if (mutableMessages[previousMessageIdx].public().role == role) {
                logWarn("Appending message to previous one since role must alternate.")

                // Merge the given `contents` with previous message.
                val previousMessage = mutableMessages[previousMessageIdx]
                previousMessage.addContents(contents)
                previousMessage.public().metadata.addAttributes(MetaAttribute.MERGED)

                // Return the message where `contents` has been merged.
                return previousMessage
            }
        }

        // Add a new message normally and return the just created message.
        return addNewMessage(role, contents)
    }


    /**
     * Creates and add a new message to [mutableMessages]. This method is called by [addFistMessage], [addFistMessage],
     * and [addSummary]
     *
     * It uses [messageBuilder] to instance a new message and sets its `role` with [MessageWrapper.Builder.setRole],
     * and `contents` with [MessageWrapper.Builder.addContents] (note that the latter does not add strings to the
     * `contents` if they are empty).
     *
     * @param role The [MetaRole] to be assigned with the new message.
     * @param contents The string-based set of contents for the new message. It would never be empty.
     * @param index The index in which the new message should be added. If `null`, the message is added at the end of
     * the list.
     * @return The new message added to [mutableMessages].
     */
    private fun addNewMessage(role: MetaRole, contents: List<String>, index: Int? = null): MessageWrapper.Builder<M> {
        // Add message normally.
        val message = messageBuilder(logger)  // Create new builder instance for each message
            .setRole(role)
            .addContents(contents)
        message.public().metadata.addTiming(MetaTiming.CREATION)
        if (index == null)
            mutableMessages.add(message)
        else
            mutableMessages.add(index, message)
        logInfo("Adding new message '{}'.", message)
        return message
    }


    /**
     * Returns the last message before than [firstLlmMessageIdx] that matches the pattern defined by `matcher`.
     * This function is used by add [addFollowingMessage] and [getSummaryInfo].
     *
     * @param matcher A lambda function that takes a [MessageWrapper.Builder] and returns a boolean. The first time it
     * returns `true`, the function returns the index of the related item in [mutableMessages]. Note that the message is
     * traverse in the reverse order.
     * @return The last index of an element in [mutableMessages] identified by the `matcher`. It returns -1 if the
     * match does not exist in the range `[firstLlmMessageIdx, mutableMessages)`.
     */
    private fun getLasMessage(matcher: (MessageWrapper.Builder<M>) -> Boolean): Int {
        for (index in mutableMessages.indices.reversed()) {
            if (index < firstLlmMessageIdx)
                return -1
            if (matcher(mutableMessages[index]))
                return index
        }
        return -1
    }


    /**
     * Retrieves data to be used to invoke [addSummary]. This method is provided to allow you computing the summary
     * message based on some previous messages, before to add a new summary to he [mutableMessages] list.
     *
     * The returning value includes the last summary message if available. It does neither include fake messages
     * (see [addFistMessage]), nor the last message, if it is of type `user` (i.e., the last user message that the LLM
     * did not address, which should not be summarized).
     *
     * Note that this implementation assumes that no messages are never deleted from [mutableMessages].
     *
     * @return Information about the messages to summarize, including previous summary if available. See [Summarizing],
     * [Summarizing.SummarizingMessage] and [Summarizing.Formatter] for more.
     */
    fun getSummaryInfo(): Summarizing {

        // Get the last assistant message, i.e., potentially exclude user message if it is the very last one.
        val lastAssistantMessage = getLasMessage { it.public().role == MetaRole.ASSISTANT }
        // Check if there is something new to summarize.
        if (lastAssistantMessage < 0 || (lastAssistantMessage <= firstLlmMessageIdx)) {
            logWarn("No messages to summarize.")
            return Summarizing(emptyList())
        }

        // Add the information about last summarized message if available.
        val summaryInfo =
            if (lastSummaryMessageIdx >= 0)
                Summarizing.SummarizingMessage(mutableMessages[lastSummaryMessageIdx], lastSummaryMessageIdx)
            else null

        // Retrieve the sublist of `mutableMessage` that represent the messages to be summarized.
        // It also takes track of the related index for applying the summary message later with `addMessage`.
        // This approach assumes that messages are never deleted from the `mutableMessage` list.
        val toSummarize = mutableListOf<Summarizing.SummarizingMessage>()
        mutableMessages.subList(firstLlmMessageIdx, lastAssistantMessage + 1).forEachIndexed { idx, msg ->
            if (msg.public().role != MetaRole.SUMMARY)
                toSummarize.add(Summarizing.SummarizingMessage(msg, firstLlmMessageIdx + idx))
        }

        // Returns the info to be used for computing the summarizing message and add it with `addSummary`.
        val summarizing = Summarizing(toSummarize, summaryInfo)
        if (logger.isInfoEnabled())
            logInfo("Providing summary info: '${Utils.escapeCharacters(summarizing)}'.")
        return summarizing
    }


    /**
     * Add a summary message to [mutableMessages], and modify consequently what the [messages] contains by updating the
     * [firstLlmMessageIdx].
     *
     * @param summary The string containing the summarization of the messages represented in `summaryInfo`, which refer
     * to previous messages.
     * @param summarizing A list of previous messages to be summarized, it is given by [getSummaryInfo].
     * @return The created summary messages, which is added to [mutableMessages] and it should be used to update related
     * metadata. It returns `null` if there is nothing to summarize, i.e., `summaryInfo` is empty.
     */
    fun addSummary(summary: String, summarizing: Summarizing) = addSummary(listOf(summary), summarizing)


    /**
     * Add a summary message to [mutableMessages], and modify consequently what the [messages] contains by updating the
     * [firstLlmMessageIdx].
     *
     * @param summary A set of string containing the summarization of the messages represented in `summaryInfo`, which
     * refer to previous messages.
     * @param summarizing A list of previous messages to be summarized, it is given by [getSummaryInfo].
     * @return The created summary messages, which is added to [mutableMessages] and it should be used to update related
     * metadata. It returns `null` if there is nothing to summarize, i.e., `summaryInfo` is empty.
     */
    fun addSummary(summary: List<String>, summarizing: Summarizing): MessageWrapper<M>? {

        if (summarizing.messages.isEmpty()) {
            logWarn("No messages' info to summarize.")
            return null
        }

        // Create a new summary message and add a list of summary ids
        lastSummaryMessageIdx = summarizing.messages.last().index + 1
        val message = addNewMessage(MetaRole.SUMMARY, summary, lastSummaryMessageIdx)
        message.public().metadata.summaryIds = summarizing.getIndexes().map { mutableMessages[it].public().id }

        // Set the index to point to the next new message (it might be out of bound).
        firstLlmMessageIdx = lastSummaryMessageIdx + 1

        // Return the just created message.
        return message.public()
    }


    /**
     * Returns the last summarization message. It returns `null` if no summarization as been performed yet.
     * @return The last summarization message, or `null` if it is not available.
     */
    fun getLastSummary(): MessageWrapper<M>? {
      if (lastSummaryMessageIdx < 0)
          return null
      return mutableMessages[lastSummaryMessageIdx].public()
    }


    /**
     * Converts the [mutableMessages] into a list of maps for being stored ina NoSQL database (e.g., AWS DynamoDB), this
     * is done through [MessageWrapper.Builder.toMap].
     *
     * If [incremental] is `true`, it maps the elements of [mutableMessages] without considering the messages previously
     * mapped by this function.
     *
     * If [excludeLast] is `true`, the last element of [mutableMessages] is not mapped. This is done since the next
     * message might be merged with the last one if both have the same role.
     *
     * The data encoded in the returning data structure should be of primitive data types, such as: `String`, `Boolean`,
     * `Number`, `ByteArray`, `null`, `Set<String>`, `Set<Number>`, `Set<ByteArray>`, `List<Any?>` (where elements are
     * of previous types included nested list or map), and `Map<String, Any?>` (where values are of previous types
     * included nested list or map).
     *
     * @param incremental If `true` the conversion occurs incrementally, otherwise all the [mutableMessages] is
     * converted. Default value is `true`.
     * @param  excludeLast If `true` the last element of [mutableMessages] is not mapped. Default value is `true`.
     * @return A `List<Map<String, Any>>` that represents the data encoded in [mutableMessages] through primitive data
     * types.
     */
    fun toMessagesMap(incremental: Boolean = true, excludeLast: Boolean = true): List<Map<String, Any>> {
        val toConvert: List<MessageWrapper.Builder<M>>
        if (incremental) {
            val incrementalIdx = if (messageMapIdx < 0) 0 else messageMapIdx
            if (incrementalIdx >= mutableMessages.size) {
                logError("Incremental conversion requested but the are no new messages.")
                return emptyList()
            }
            val lastIdx = if (excludeLast) mutableMessages.size - 1 else mutableMessages.size
            if (lastIdx < incrementalIdx || lastIdx <= 0) {
                logError("Incremental conversion requested but an internal error occurred.")
                return emptyList()
            }
            toConvert = mutableMessages.subList(incrementalIdx, lastIdx)
            messageMapIdx = lastIdx
        } else
            toConvert = mutableMessages
        return toConvert.map { it.toMap() }
    }
}



/**
 * The data class used to represent the messages to be summarized. A list of this class it encoded in [Summarizing],
 * given by [MessagesManager.getSummaryInfo], and required by [MessagesManager.addSummary]. It uses
 * [Summarizing.format] to format the messages to be summarized.
 *
 * @property messages The list of messages to be summarized. role of the message to be summarized.
 * @property previousSummary The contents of the previous summarization, if it exists; otherwise it is `null` (i.e.,
 * the default value).
 *
 * @constructor Creates a new instance by initializing all class properties.
 *
 * @see MessagesManager
 * @see Summarizing.Formatter
 * @see Summarizing.SummarizingMessage
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
data class Summarizing(val messages: List<SummarizingMessage>,
                       val previousSummary: SummarizingMessage? = null)
{


    /**
     * Formats the messages to be summarized. The output will be structured based on the [Formatter] as
     * ```
     * $preamble
     * // The next line is ignored if `includePreviousSummary = false`.
     * ${Formatter.prefix}${Formatter.SUMMARY_TAG}${Formatter.suffix} $content1. ... $contentN.${Formatter.summaryClosure}
     * ${Formatter.prefix}${Formatter.USER_TAG}${Formatter.suffix} $content1. ... $contentN.${Formatter.closure}
     * ${Formatter.prefix}${Formatter.ASSISTANT_TAG}${Formatter.suffix} $content1. ... $contentN.${Formatter.closure}
     * ```
     *
     * @param preamble The preamble to be added at the beginning of the message. Default value is an empty string.
     * @param includePreviousSummary If `true` the [previousSummary] will be included in the message; if it exists.
     * If `false`[previousSummary] will always be ignored. Default value is `false`.
     * @return The formatted message to be summarized.
     */
    fun format(preamble: String = "", includePreviousSummary: Boolean = false): String {
        return StringBuilder().apply {
            // Add preamble
            if (preamble.isNotEmpty()) {
                append(preamble)
            }

            // Format the summary message if it exists.
            if (includePreviousSummary && previousSummary != null) {
                append(previousSummary.format())
                append(Formatter.summaryClosure)
            }

            // Format all the other messages.
            if (messages.isNotEmpty())
                append(messages.joinToString(separator = Formatter.closure) { it.format() })
        }.toString()
    }


    /**
     * Returns message formatted as a string with the [format] method.
     * @return The formatted message contents.
     */
    override fun toString() = format()


    /**
     * Returns the indexes of the messages to be summarized. If [previousSummary] exists, it will be the first index in
     * the list.
     * @return The indexes of the messages to be summarized including [previousSummary] if it exists.
     */
    fun getIndexes(): List<Int> = buildList {
        previousSummary?.index?.let { add(it) }
        addAll(messages.map { it.index })
    }

    /**
     * Returns whether this class contains information or not.
     * @return `true`if [messages] is empty and [previousSummary] si `null`; `false` otherwise.
     */
    fun isEmpty() = messages.isEmpty()  && previousSummary == null


    /**
     * The data class used to represent the summary information of a message. A list of this class it encoded in
     * [Summarizing], given by [MessagesManager.getSummaryInfo], and required by [MessagesManager.addSummary].
     *
     * @property role The role of the message to be summarized.
     * @property contents The contents of the message to be summarized as a `List<String>`.
     * @property index The index of this message as stored in [MessagesManager.mutableMessages].
     * @property id The identifier of this message as stored in [MessagesManager.mutableMessages].
     *
     * @constructor Creates a new instance of [SummarizingMessage] by specifying [role], [contents] and [index]. An auxiliary
     * contractor allows to initialize all the class properties with a [MessageWrapper.Builder<*>] and the [index].
     *
     * @see MessagesManager
     * @see Summarizing
     * @see Summarizing.Formatter
     *
     * @author Luca Buoncompagni © 2025
     * @version 1.0
     */
    data class SummarizingMessage(val role: MetaRole, val contents: List<String>, val index: Int, val id: String) {

        constructor(message: MessageWrapper.Builder<*>, index: Int) : this(
            message.public().role, message.public().contents, index, message.public().id
        )


        /**
         * Formats the message as a string including the [role] and [contents]. The output will be structured based
         * on the [Formatter] as `"${formatter.prefix}${formatter.roleTag(role)}${formatter.suffix} {content1},
         * {content2}, ... {contentN}"` (e.g., `**User**: My first message content. Another
         * content.` or `**Previous Summary**: A previous summary.`).
         *
         * @return The formated message.
         */
        fun format(): String {
            return StringBuilder().apply {
                // Format role.
                append("${Formatter.prefix}${Formatter.roleTag(role)}${Formatter.postfix}")
                // Format contents
                append(contents.filter { it.isNotEmpty() }
                    .joinToString(separator = Formatter.separator)
                    { Formatter.transformer(it) }
                )
            }.toString()
        }
    }


    /**
     * Set up the message content format. It is used by [Summarizing.format] to create a string with all messages to be
     * summarized.
     *
     * @property userTag The tag used to represent the user role. By default, it is [USER_TAG].
     * @property assistantTag The tag used to represent the assistant role. By default, it is [ASSISTANT_TAG].
     * @property summaryTag The tag used to represent the summary role. By default, it is [SUMMARY_TAG].
     * @property prefix The prefix used to wrap the [userTag], [assistantTag] or [summaryTag] on the left-hand side.
     * By default, is it [PREFIX].
     * @property postfix The suffix used to wrap the [userTag], [assistantTag] or [summaryTag] on the right-hand side.
     * By default, is it [POSTFIX].
     * @property summaryClosure The closure used at the end of the previous summary message. By default, it is
     * [SUMMARY_CLOSURE].
     * @property closure The closure used at the end of the formatted message contents. By default, it is [CLOSURE].
     * @property transformer A lambda function that transforms the message contents. By default, it is [TRANSFORMER].
     *
     * @see MessagesManager
     * @see Summarizing
     * @see Summarizing.SummarizingMessage
     *
     * @author Luca Buoncompagni © 2025
     * @version 1.0
     */
    object Formatter {

        /**  The default tags associated with [userTag], it is equal to `"User "`. */
        private const val USER_TAG = "User"

        /**  The default tags associated with [assistantTag], it is equal to `"Assistant "`. */
        private const val ASSISTANT_TAG = "Assistant"

        /**  The default tags associated with [summaryTag], it is equal to `"Previous Dialogue "`. */
        private const val SUMMARY_TAG = "Previous Dialogue"

        /**  The default value associated with [prefix], it is equal to `"["`. */
        private const val PREFIX = "["

        /**
         * The default value associated with [separator], it should be a lambda function that takes a string
         * and returns a string. The input will be each message's `contents`. By default, it appends `"."` if
         * the input does not already end with a dot, or a question mark, or an exlamative mark.
         */
        private val TRANSFORMER: (String) -> String = { it ->
            if (it.endsWith(".") || it.endsWith("?") || it.endsWith("!"))
                it
            else "$it."
        }

        /**  The default separator between message's `contents`, it is equal to `" "`. */
        private const val SEPARATOR = " "

        /**  The default tags associated with [postfix], it is equal to `"]:"`. */
        private const val POSTFIX = "]: "

        /**  The default tags associated with [summaryClosure], it is equal to `"\n\n"`. */
        private val SUMMARY_CLOSURE: String = System.lineSeparator() + System.lineSeparator()

        /**  The default tags associated with [closure], it is equal to `"\n"`. */
        private val CLOSURE: String = System.lineSeparator()


        // See documentation above.
        var userTag: String = USER_TAG
        var assistantTag: String = ASSISTANT_TAG
        var summaryTag: String = SUMMARY_TAG
        var prefix: String = PREFIX
        var postfix: String = POSTFIX
        var summaryClosure: String = SUMMARY_CLOSURE
        var closure: String = CLOSURE
        var separator: String = SEPARATOR
        var transformer: (String) -> String = TRANSFORMER


        /**
         * Returns the tag associated to the given [role].
         * @param role The role to be formatted.
         * @return Returns [userTag], if `role` is [MetaRole.USER], [assistantTag] if `role` is [MetaRole.ASSISTANT], and
         * [summaryTag] if `role` is [MetaRole.SUMMARY].
         */
        fun roleTag(role: MetaRole) = when (role) {
            MetaRole.USER -> userTag
            MetaRole.ASSISTANT -> assistantTag
            MetaRole.SUMMARY -> summaryTag
        }
    }
}

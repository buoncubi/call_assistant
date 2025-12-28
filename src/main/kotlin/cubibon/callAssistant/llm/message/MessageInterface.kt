package cubibon.callAssistant.llm.message


/**
 * Extend function to convert a string in the CamelCase convention. It is mainly used to store enumerator on NoSQL
 * database or jason in a standard format, e.g., [MetaRole], [MetaAttribute], and [MetaTiming].
 *
 * @receiver It adds this function to a `String`.
 * @return The receiver `String` converted into the CamelCase convention.
 */
private fun String.toCamelCase(): String {
    return this.lowercase()
        .split('_')
        .mapIndexed { index, word ->
            if (index == 0) word
            else word.replaceFirstChar { it.uppercase() }
        }
        .joinToString("")
}



/**
 * Utility function to convert a message (represented as a collection of `T`) to a string to be printed.
 *
 * @param T The element's type inferred by this function.
 *
 * @param msg The message (i.e., `Collection<T>`) to be printed
 * @param toStr A lambda function that converts each message's element into a string. By default, it uses
 * `T.toString()`.
 * @param prefix A string to be appended before all message's element. By default, it is empty, i.e., `""`.
 * @param postfix A string to be appended after all message's element. By default, it is empty, i.e., `""`.
 * @param separator A string to be appended within each message's element. By default, it is `", "`.
 * @return Returns a [StringBuilder] with the string representation of the message.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
internal fun <T> message2string(msg: Collection<T>, toStr: (T) -> String = {it.toString()},
                                prefix: String = "", postfix: String = "", separator: String = ", ",
): StringBuilder {
    return StringBuilder().apply {
        msg.joinTo(this, separator = separator, prefix = prefix, postfix = postfix) { toStr(it) }
    }
}



/**
 * Enumeration of possible message types in the conversation, which can be:
 *  - `USER`, when the message is sent by the user;
 *  - `ASSISTANT`, when the message is sent by the assistant;
 *  - `SUMMARY`, when the message is a summary of the previous conversation (see [MessagesManager] for more info about
 *  the summarization procedure).
 *
 *  Note that the [MessageWrapper.role] returned by [MetaData.toMap] are the lowercase of the enum's [name] with the
 *  camelcase standard; as returned by [toString].
 *
 * @see LlmMessage
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
enum class MetaRole {USER, ASSISTANT, SUMMARY;
    override fun toString(): String {
        return super.toString().toCamelCase() // this is used by  `MetaData.toMap`
    }
}



/**
 * Interface for creating LLM messages of type ´M´. Thi implementation allows using specific message format required by
 * different LLM providers.
 *
 * This interface should be used in combination with the Factory paradigm implemented through a `build` method in the
 * companion class, which instance and return instances of [LlmMessage.Builder]. The latter is used by [MessagesManager],
 * which has access to all functionalities defined by [LlmMessage] and [LlmMessage.Builder], and gives access to other
 * classes access only to [LlmMessage]. In other words, [LlmMessage.Builder] defines private functionalities, while
 * [LlmMessage] defines public functionalities. See [LlmMessage.Builder] for more.
 *
 * @param M The type of the underlying raw message.
 *
 * @see LlmMessage.Builder
 * @see MessageWrapper
 * @see MessagesManager
 * @see AwsMessage
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface LlmMessage<out M> {
    // It does not extend LoggableInterface, since it get logger from building classes.

    /**
     * Creates a raw message for the LLM provider.
     * @param role The role of the message sender.
     * @param contents List of message content strings.
     * @return A provider-specific message of type ´M´
     */
    fun getRawMessage(role: MetaRole, contents: List<String>): M


    /**
     * Builder interface for creating [LlmMessage] instances through the factory paradigm. This interface allow defining
     * methods that are accessible only within the [MessagesManager]; use [LlmMessage] to define methods that are
     * publicly accessible.
     *
     * The basic implementation of an `LlmMessage`, and relate instantiation (which is done by [MessagesManager]),
     * can be:
     * ```
     * // import `LlmMessageProvider` which is the message class required by a specific LLM provider
     *
     * class MyLlmMessage private constructor() : LlmMessage<LlmMessageProvider> {
     *
     *    ... // Implement here publicly available functionalities
     *
     *    private inner class Builder : LlmMessage.Builder<LlmMessageProvider> {
     *         override fun public() = this@AwsMessage
     *
     *         ... // Implement here functionalities accessible only within `MessagesManager`.
     *    }
     *
     *    companion object {
     *       // Implementation of the factory paradigm.
     *       fun build(): LlmMessage.Builder<Message> = AwsMessage().Builder()
     *    }
     * }
     *
     * // Create a new instance.
     * val message : MyLlmMessage.Builder  = MyLlmMessage.build()
     *
     * // Get a reference to the public object.
     * val publicMessage : MyLlmMessage = message.public()
     * ```
     *
     * @param M The type of the underlying raw message.
     *
     * @see LlmMessage
     * @see AwsMessage
     * @see MessagesManager
     *
     * @author Luca Buoncompagni © 2025
     * @version 1.0
     */
    interface Builder<M> {

        /**
         * Returns the message with a format `M` specific for an LLM provider.
         * @return The message representation.
         */
        fun public(): LlmMessage<M>
    }
}



/**
 * The attributes that can appear in a message metadata, i.e., in [MessageWrapper.metadata].
 *
 * Note that the [MetaData.attributes] returned by [MetaData.toMap] are the lowercase of the enum's [name] with the camelcase
 * standard; as returned by [toString].
 *
 * @property FAKE If this message is fake since it has been generated because the very first message was from the
 * assistant instead than the user. The message that generates the fake message is always the one that follow the
 * message with the `FAKE` attribute.
 *
 * @property MERGED If this message has been merged with other messages since the user and assistant role were not
 * alternating. If a message has been merged `n`-th times, than there will be `n` `MERGED` attributes.
 *
 * @see MetaData
 * @see MessageData
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
enum class MetaAttribute{FAKE, MERGED, LLM_INTERRUPTED, SPEECH_INTERRUPTED, HARDCODED;
    // todo document INTERRUPTED (also on readme), i.e., when the user interrupt the assistant while the llm is reasoning or while is playing
    // todo document HARDCODED (also on readme), i.e., when the assist says an hardcoded sentence

    override fun toString(): String {
        return super.toString().toCamelCase() // this is used by  `MetaData.toMap`
    }
}



/**
 * The timestamp associated with a message metadata, i.e., in [MessageWrapper.metadata].
 *
 * Note that the instance should be represented in milliseconds, e.g., as [System.currentTimeMillis]. Also, note that
 * the [MetaData.timing] map returned by [MetaData.toMap] are the lowercase of the enum's [name] with the camelcase
 * standard; as returned by [toString].
 *
 * @property CREATION The instant in which the message was created.
 * @property SPEECH_START The instant in which the message playback started.
 * @property SPEECH_END The instant in which the message playback ended.
 * @property LISTEN_START The instant in which the message listening task started.
 * @property LISTEN_END The instant in which the message listening task ended.
 * @property LLM_START The instant in which the LLM-based evaluation started.
 * @property LLM_END The instant in which the LLM-based evaluation ended.
 *
 * @see MetaData
 * @see MessageData
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
enum class MetaTiming{CREATION, SPEECH_START, SPEECH_END, LISTEN_START, LISTEN_END, LLM_START, LLM_END;

    override fun toString(): String {
        return super.toString().toCamelCase() // this is used by  `MetaData.toMap`
    }
}



/**
 * The message metadata container. It is associated with a message exchange between the LLM-based assistant and the user
 * by [MessageWrapper]. The metadata it represents will be stored in a NoSQL database for logging purposes (e.g., on AWS
 * DynamoDB), and the implementation of this interface can further extend the stored data.
 *
 * Note that the properties below might occur in the map returned by [toMap] if they are not `null`.
 *
 * @property summaryIds The IDs of the messages that are summarized by this message.
 * @property attributes Some [MetaAttribute] associated with this message.
 * @property timing A map of timestamps associated with this message, which is associated with a [MetaTiming] key.
 * @property data A map of data associated with this message, which will be appended as-is in the [toMap] outcome.
 *
 * @see MessageWrapper
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface MetaData {
    // It does not extend LoggableInterface, since it get logger from building classes.

    var summaryIds: List<String>?
    val attributes: List<MetaAttribute>?
    val timing: Map<MetaTiming, Long>?
    val data: Map<String, Any>?

    /**
     * Add a new timestamp to be added to the [timing] map for annotating the message metadata.
     * @param key A tag that should identify the timestamp. The sting associated with [MetaTiming.name] will be used as
     * the actual key in the [timing] map.
     * @param time The timestamp associated with the `key` in the timing map. It should represent the timestamp in
     * milliseconds. Default value [System.currentTimeMillis].
     */
    fun addTiming(key: MetaTiming, time: Long = System.currentTimeMillis()) // It should not be possible to remove time


    /**
     * Add a new data to be added to the [data] map for annotating the message metadata.
     * @param key A tag that should identify the data. The sting associated with [key] will be used as the actual key in
     * the [data] map.
     * @param data The data associated with the `key` in the data map.
     */
    fun addData(key: String, data: Any) // It should not be possible to remove data


    /**
     * Add a new [MetaAttribute] to the message metadata.
     * @param attributes The attribute to add.
     */
    fun addAttributes(attributes: MetaAttribute) // It should not be possible to remove attributes


    /**
     * Returns a map containing the metadata, which must have string keys and [Any] values. This function is called by
     * [MessageWrapper.Builder.toMap], and results are returned by [MessagesManager.toMessagesMap].
     *
     * In particular, the values should be of primitive type, e.g., `String`, `Number`, `Boolean`, `ByteArray`,
     * `Set<String>`, `Set<Number>`, `Set<ByteArray>`, heterogeneous `List` of previous types, `Map<String, Any?>`,
     * where keys are of previous types, as well as nested list and maps. The returned value will be stored in a NoSQL
     * database, e.g., on AWS DynamoDB.
     *
     * Note that all the properties of this class that are different from `null` are included within the returned map,
     * see [MetaData] for the list of possible properties.
     *
     * @return a ´Map<String, Any?>` with the data associated with a message.
     */
    fun toMap(): Map<String, Any?>


    /**
     * The [MetaData] interface requires to define the [Any.toString] method.
     * @return A string representation of the message's data
     */
    override fun toString(): String


    /**
     * The [MetaData] interface requires to define the [Any.hashCode] method.
     * @return A hash code value for the message's data
     */
    override fun hashCode(): Int


    /**
     * The [MetaData] interface requires to define the [Any.equals] method.
     * @return Returns `true` if the two objects are equal, `false` otherwise
     */
    override fun equals(other: Any?): Boolean
}



/**
 * Wrapper interface for LLM messages that adds metadata and role information together with the representation of the
 * message itself, which can be specialized for different LLM models based on the [LlmMessage] interface.
 *
 * This interface should be used in combination with the Factory paradigm implemented through a `build` method in the
 * companion class, which instance and return instances of [MessageWrapper.Builder]. The latter is used by
 * [MessagesManager], which and has access to all functionalities defined by [MessageWrapper] and
 * [MessageWrapper.Builder], and gives access to other classes access only to [MessageWrapper]. In other words,
 * [MessageWrapper.Builder] defines private functionalities, while [MessageWrapper] defines public functionalities.
 * See [MessageWrapper.Builder] for more.
 *
 * @param M The type of the underlying raw message.
 *
 * @property id The message id as a string.
 * @property role The role associated with this message. See [MetaRole] for more information about possible roles.
 * @property contents The message contents as a `List<String>`.
 * @property rawMessage The message contents of the type `M`. This property should be derived form [contents] for a
 * specific for an LLM provider. Also, for decoupling purposes, it should be based to a copy of the strings encoded in
 * [contents].
 * @property metadata The data associated with this message. See [MessageData] for more information about stored data.
 *
 * @see MessageWrapper.Builder
 * @see LlmMessage
 * @see MessageData
 * @see MetaRole
 * @see MetaMessage
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface MessageWrapper<out M> {
    // It does not extend LoggableInterface, since it get logger from building classes.

    val id: String
    val role: MetaRole
    val contents: List<String>
    val rawMessage: M
    val metadata: MetaData


    /**
     * The [MessageWrapper] interface requires to define the [Any].`toString` method.
     * @return A string-based representation the wrapped message and auxiliary data.
     */
    override fun toString(): String


    /**
     * The [MessageWrapper] interface requires to define the [Any].`hashCode` method.
     * @return Returns a hash code value for the object
     */
    override fun hashCode(): Int


    /**
     * The [MessageWrapper] interface requires to define the [Any].`equals` method.
     * @param other The object to compare with.
     * @return `true` if the objects are equal, `false` otherwise.
     */
    override fun equals(other: Any?): Boolean


    /**
     * Builder interface for creating [MessageWrapper] instances through the factory paradigm. This interface allow
     * defining methods that are accessible only within the [MessagesManager].
     *
     * Builder interface for creating [MessageWrapper]  instances through the factory paradigm. This interface allow
     * defining methods that are accessible only within the [MessagesManager]; use [MessageWrapper] to define methods
     * that are publicly accessible.
     *
     * The basic implementation of a `MessageWrapper`, and relate instantiation (which is done by [MessagesManager]),
     * can be:
     * ```
     * // import `LlmMessageProvider` which is the message class required by a specific LLM provider
     *
     * class MyMetaMessage private constructor(private val llmMessage: LlmMessage<LlmMessageProvider>) : MessageWrapper<LlmMessageProvider> {
     *
     *    ... // Implement here publicly available functionalities
     *
     *    private inner class Builder : MessageWrapper.Builder<M> {
     *
     *       override fun public(): MyMetaMessage = this@MyMetaMessage
     *
     *       ... // Implement here functionalities accessible only within `MessagesManager`.
     *    }
     *
     *    companion object {
     *
     *       // Implementation of the factory paradigm.
     *       fun <M> build(llmMessageBuilder: LlmMessage.Builder<LlmMessageProvider>): MessageWrapper.Builder<LlmMessageProvider> =
     *          MyMetaMessage(llmMessageBuilder.public()).Builder()
     *    }
     * }
     *
     * // Create a new instance.
     * val messageWrapper : MyMetaMessage.Builder  = MyMetaMessage.build(MyLlmMessage.build())
     *
     * // Get a reference to the public object.
     * val publicMessageWrapper : MyMetaMessage = messageWrapper.public()
     *
     * // Get the reference to the encoded `LlmMessage`.
     * val myLlmMessage: MyLlmMessage = publicMessageWrapper.rawMessage
     * ```
     *
     * @param M The type of the underlying raw message.
     *
     * @see MessageWrapper
     * @see MetaMessage
     * @see MessagesManager
     *
     * @author Luca Buoncompagni © 2025
     * @version 1.0
     */
    interface Builder<M> {

        /**
         * Set the [MessageWrapper.role], this is only done by [MessagesManager], and it is not done while constructing
         * instances of [MessageWrapper] to simplify the building mechanism.
         * @param role The [MetaRole] of the message.
         * @return `this` builder to be used in an operation chain.
         */
        fun setRole(role: MetaRole): Builder<M>


        /**
         * Add a list of string to the [MessageWrapper.contents], this is only done by [MessagesManager], and it is not
         * done while constructing instances of [MessageWrapper] to simplify the building mechanism.
         *
         * @param contents The list of contents to be added to the message. Note blank elements in `contents` should not
         * be added.
         * @return `this` builder to be used in an operation chain.
         */
        fun addContents(contents: List<String>): Builder<M>


        /**
         * Set the [MessageWrapper.id], this is mainly used for testing purposes, e.g., by [buildAwsMetaMessage].
         * @param id The message identifier.
         * @return `this` builder to be used in an operation chain.
         */
        fun setId(id: String): Builder<M>


        /**
         * Returns the message with a role, metadata, string contents, and the same contents to be used by a specific
         * LLM provider (i.e., of type `M`).
         * @return The message representation and auxiliary data.
         */
        fun public(): MessageWrapper<M>


        /**
         * Returns a map containing the metadata, which must have string keys and [Any] values. In particular, the
         * values should be of primitive type, e.g., `String`, `Number`, `Boolean`, `ByteArray`, `Set<String>`,
         * `Set<Number>`, `Set<ByteArray>`, heterogeneous `List` of previous types, `Map<String, Any?>`, where keys are
         * of previous types, as well as nested list and maps. The returned value will be stored in a NoSQL database,
         * e.g., on AWS DynamoDB.
         *
         * As an example, it returns a map structured as
         * ```
         *    {
         *        "id": "...47DK...",
         *        "message": {
         *            "role": role.toString(),  // which can be "USER", "ASSISTANT", or "SUMMARY".
         *            "contents": [  // it is the `contents` list
         *              "Some message content",
         *              "Some other message content",
         *              ...
         *            ]
         *        },
         *        "metadata": {
         *            ...   // It is the `metadata.toMap()`
         *        }
         *    }
         * ```
         * See the [MetaData.toMap] message for more about the formatting of the `metadata` key. Note that this function
         * is called by [MessagesManager.toMessagesMap].
         *
         * @return a ´Map<String, Any?>` with the data associated with a message.
         */
        fun toMap(): Map<String, Any> {
            return mapOf(
                MAP_ID_KEY to public().id,
                MAP_MESSAGE_KEY to mapOf(
                    MAP_ROLE_KEY to public().role.toString(),
                    MAP_CONTENTS_KEY to public().contents
                ),
                MAP_METADATA_KEY to public().metadata.toMap(),
            )
        }


        companion object {

            /** The key in the map returned by [toMap] associated with the [id]. */
            const val MAP_ID_KEY = "id"

            /**
             * The key in the map returned by [toMap] associated with the nested map to represent the message `contents`
             * and role.
             */
            const val MAP_MESSAGE_KEY = "message"

            /** The key in the map returned by [toMap] associated with the [role]. */
            const val MAP_ROLE_KEY = "role"

            /** The key in the map returned by [toMap] associated with the [contents]. */
            const val MAP_CONTENTS_KEY = "contents"

            /** The key in the map returned by [toMap] associated with the [metadata]. */
            const val MAP_METADATA_KEY = "metadata"
        }
    }
}
package digital.boline.callAssistant.llm.message

import com.github.f4b6a3.ulid.UlidCreator
import digital.boline.callAssistant.CentralizedLogger
import digital.boline.callAssistant.Utils


/**
 * The message metadata container, it is associated with a message exchange between the LLM-based assistant and the user
 * by [MetaMessage]. The metadata it represents will be stored in a NoSQL database for logging purposes (e.g., on AWS
 * DynamoDB), and the implementation of this interface can further extend the stored data.
 *
 * Note that the properties below might occur in the map returned by [toMap] if they are not `null`.
 *
 * @constructor It initialises the attributes to empty or `null`, and it also sets the [logger], which is given by the
 * [MessagesManager].
 *
 * @property summaryIds The IDs of the messages that are summarized by this message. In the [toMap] outcomes, it will be
 * associated with the [SUMMARY_IDS_KEY].
 * @property attributes Some of the [MetaAttribute] associated with this message. In the [toMap] outcomes, it will be
 * associated with the [ATTRIBUTES_KEY].
 * @property timing A list of time instants annotated with a key among the [MetaTiming] enumerator. In the [toMap]
 * outcomes, it will be associated with the [TIMING_KEY].
 * @property data An auxiliary map of data. In the [toMap] outcomes, it will be associated with the [DATA_KEY].
 * @property logger A `private` logger that is given from [MetaMessage] which, in turn, is given by [MessagesManager].
 *
 * @see MessageWrapper
 * @see MetaMessage
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class MessageData(private val logger: CentralizedLogger): MetaData {

    override var summaryIds: List<String>? = null
        set(value) {
            if (value == null){
                logger.warn("Cannot set `summaryIds` to null.")
            } else if (value.isEmpty()) {
                logger.warn("Cannot set `SummaryIds` to empty")
            } else if (summaryIds != null) {
                logger.warn("Cannot set `SummaryIds` more than once")
            } else {
                field = value
            }
        }

    private var mutableAttributes: MutableList<MetaAttribute>? = null
    override val attributes: List<MetaAttribute>?
        get() = mutableAttributes // Does not make a copy

    override fun addAttributes(attributes: MetaAttribute) {
        if (mutableAttributes == null)
            mutableAttributes = mutableListOf()
        mutableAttributes!!.add(attributes)
    }


    private var mutableData: MutableMap<String, Any>? = null
    override val data: Map<String, Any>?
        get() = mutableData // Does not make a copy

    override fun addData(key: String, data: Any)  {
        if (mutableData == null)
            mutableData = mutableMapOf()
        mutableData!![key] = data
    }


    private var mutableTiming: MutableMap<MetaTiming, Long>? = null
    override val timing: Map<MetaTiming, Long>?
        get() = mutableTiming // Does not make a copy

    override fun addTiming(key: MetaTiming, time: Long) {
        if (mutableTiming == null)
            mutableTiming = mutableMapOf()
        mutableTiming!![key] = time
    }


    // See documentation of the `MetaData` interface.
    override fun toMap(): Map<String, Any?> = buildMap {
        summaryIds?.let { ids -> put(SUMMARY_IDS_KEY, ids) }
        mutableAttributes?.let { attributes -> put(ATTRIBUTES_KEY, attributes.map { it.toString() }) }
        mutableData?.let { put(DATA_KEY, data) }
        mutableTiming?.let { timing -> put(TIMING_KEY, timing.map { (key, value) -> mapOf(key.toString() to value)})}
    }


    /**
     * Returns a string representation of the result of [toMap].
     * @return a string representation of message's metadata.
     */
    override fun toString() = Utils.escapeCharacters(toMap())


    /**
     * Compares the results of [toMap] against the `other` object for equality, which can be an instance of `MetaData`,
     * or a `Map<String, Any?>`.
     *
     * @param other The object to compare with.
     * @return `true` if the objects are equal, `false` otherwise.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is MessageData)
            return toMap() == other.toMap()
        if (other is Map<*,*>)
            return toMap() == other
        return false
    }


    /**
     * Returns the hash code value associated with [toMap].
     * @return The hash code value.
     */
    override fun hashCode() = toMap().hashCode()


    companion object {
        /** The key associated with [summaryIds] in the map returned by [toMap]. It is set to `"summaryIds"`. */
        const val SUMMARY_IDS_KEY = "summaryIds"

        /** The key associated with [attributes] in the map returned by [toMap]. It is set to `"attributes"`. */
        const val ATTRIBUTES_KEY = "attribute"

        /** The key associated with [data] in the map returned by [toMap]. It is set to `"aux"`. */
        const val DATA_KEY = "aux"

        /** The key associated with [timing] in the map returned by [toMap]. It is set to `"timing"`. */
        const val TIMING_KEY = "timing"
    }
}


/**
 * Implementation of [MessageWrapper] that encompass an [LlmMessage], a [MetaRole], a [MessageData].
 *
 * @constructor This class is constructed through the [MetaMessage.Builder], which is based on [MessageWrapper.Builder],
 * and the [build] factory. See [MessageWrapper] for more. Note, that this class requires an implementation of the
 * [LlmMessage] interface. This constructor also sets the [logger], which is given by the [MessagesManager].
 *
 * @param M The type of the underlying raw message specific for a LLM provided, as defined by [LlmMessage].
 *
 * @property role See [MessageWrapper.role].
 * @property contents See [MessageWrapper.contents].
 * @property rawMessage See [MessageWrapper.rawMessage].
 * @property metadata See [MessageWrapper.metadata].
 * @property id See [MessageWrapper.id]. It is set to an `Ulid` identifier (see [UlidCreator.getMonotonicUlid]).
 * @property logger A `private` logger that is given from [MessagesManager].
 *
 * @see MetaMessage.Builder
 * @see MessageWrapper
 * @see MessageWrapper.Builder
 * @see LlmMessage
 * @see MessagesManager
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class MetaMessage<M> private constructor(private val llmMessage: LlmMessage<M>, private val logger: CentralizedLogger) : MessageWrapper<M> {

    /**
     * The mutable implementation of [contents], which is only used internally to this class.
     */
    private val mutableContents = mutableListOf<String>() // The container of the actual message contents

    override val contents: List<String>
        get() = mutableContents.toList() // A copy of message contents to be give to external objects.

    override lateinit var role: MetaRole // The role of this message
        private set

    override val metadata = MessageData(logger) // The metadata, it creates an identifier.

    override var id = UlidCreator.getMonotonicUlid().toString() // The message identifier.
        private set

    override val rawMessage: M
        // A copy of the message in the format specified by an LLM provider.
        get() = llmMessage.getRawMessage(role, contents)


    override fun toString(): String {
        return StringBuilder().apply {
            append(
                message2string(
                    prefix = "[$role] {",
                    msg = contents, separator = " ", toStr = { "'${Utils.escapeCharacters(it)}'" },
                    postfix = "} -- "
                )
            )
            append("'$id' $metadata")
        }.toString()
    }


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetaMessage<*>) return false

        if (role != other.role) return false
        if (contents != other.contents) return false
        if (metadata != other.metadata) return false

        return true
    }


    override fun hashCode(): Int {
        var result = role.hashCode()
        result = 31 * result + contents.hashCode()
        result = 31 * result + metadata.hashCode()
        return result
    }


    /**
     * The class instantiated by [MessagesManager], which defines private functionality related to [MetaMessage], and has
     * access to public functionalities as well. See [MessageWrapper.Builder] for more.
     *
     * @see MetaMessage
     * @see MessageWrapper
     * @see MessageWrapper.Builder
     * @see LlmMessage
     * @see MessagesManager
     *
     * @author Luca Buoncompagni © 2025
     * @version 1.0
     */
    private inner class Builder : MessageWrapper.Builder<M> {

        // See documentation on `MessageWrapper.Builder`.
        override fun setRole(role: MetaRole): Builder {
            this@MetaMessage.role = role
            return this
        }


        // See documentation on `MessageWrapper.Builder`.
        override fun addContents(contents: List<String>): Builder {
            for (content in contents) {
                if (content.isBlank()) {
                    logger.warn("A message cannot contain blank contents.")
                    continue
                }
                this@MetaMessage.mutableContents.add(content)
            }
            return this
        }


        // See documentation on `MessageWrapper.Builder`.
        override fun setId(id: String): MessageWrapper.Builder<M> {
            this@MetaMessage.id = id
            return this
        }


        // See documentation on `MessageWrapper.Builder`.
        override fun public(): MetaMessage<M> {
            return this@MetaMessage // Does not make a copy
        }


        /**
         * Returns the string representation of the [MetaMessage].
         * @return [MetaMessage.toString]
         */
        override fun toString() = public().toString()


        /**
         * Returns the hash code of the [MetaMessage].
         * @return [MetaMessage.hashCode]
         */
        override fun hashCode() = public().hashCode()


        /**
         * Returns the equality of the [MetaMessage].
         * @return [MetaMessage.equals]
         */
        override fun equals(other: Any?) = public() == other

    }


    companion object {

        /**
         * Implementation of the [MessageWrapper] factory, which also sets its logger.
         * @param llmMessageBuilder The builder for message based on a specific LLM provider.
         * @param logger The logger assigned to the instantiated message that gets returned.
         * @return A new [MetaMessage] with both public and private functionalities.
         */
        fun <M> build(llmMessageBuilder: LlmMessage.Builder<M>, logger: CentralizedLogger): MessageWrapper.Builder<M> =
            MetaMessage(llmMessageBuilder.public(), logger).Builder()
    }
}
package digital.boline.callAssistant.llm.message

import digital.boline.callAssistant.Loggable
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.dynamodb.model.AttributeValue


/**
 * Build a new [MessagesManager] based on [AwsMessage]. In particular, it returns:
 * ```
 * MessagesManager { MetaMessage.build(AwsMessage.build()) }
 * ```
 * @return A new [MessagesManager] for AWS.
 */
fun buildAwsMessagesManager():  MessagesManager<Message> = MessagesManager { MetaMessage.build(AwsMessage.build()) }


/**
 * Build a new [MessageWrapper] based on [AwsMessage]. In is mainly used for testing purposes, and it returns:
 * ```
 * MetaMessage.build(AwsMessage.build()).setRole(role).addContents(contents).public()
 * ```
 *
 * Note that the returned object also provides an instance of the associated [LlmMessage] by the
 * [MessageWrapper.rawMessage] property.
 *
 * @param role The role of the message.
 * @param contents The contents of the message.
 * @return A new [MessageWrapper] for AWS.
 */
fun buildAwsMetaMessage(role: MetaRole, contents: List<String>, id: String? = null): MessageWrapper<Message> =
    if (id == null)
        MetaMessage.build(AwsMessage.build()).setRole(role).addContents(contents).public()
    else
        MetaMessage.build(AwsMessage.build()).setRole(role).addContents(contents).setId(id).public()


/**
 * AWS-specific implementation of [LlmMessage] that creates Bedrock-compatible [Message].
 *
 * @constructor This class is constructed through the [AwsMessage.Builder], which is based on [LlmMessage.Builder], and
 * the [build] factory. See [LlmMessage] for more.
 *
 * @see AwsMessage.Builder
 * @see LlmMessage
 * @see LlmMessage.Builder
 * @see MessagesManager
 *
 * @author Luca Buoncompagni © 2025
 */
class AwsMessage private constructor() : LlmMessage<Message>, Loggable() {

    // See documentation on `LlmMessage`
    override fun getRawMessage(role: MetaRole, contents: List<String>): Message {
        // Convert role to AWS-specific format
        val awsRole: ConversationRole = when (role) {
            MetaRole.USER -> ConversationRole.USER
            MetaRole.ASSISTANT -> ConversationRole.ASSISTANT
            else -> {
                logError("A raw LLM message cannot be of type '{}'.", role)
                throw IllegalArgumentException("A raw LLM message cannot be of type '$role'.")
            }
        }

        // Convert contents to AWS ContentBlock format
        val awsContents = contents.mapTo(ArrayList()) { ContentBlock.fromText(it) }
        return Message.builder().role(awsRole).content(awsContents).build()
    }

    /**
     * The class instantiated by [MessagesManager], which defines private functionality related to [AwsMessage], and has
     * access to public functionalities as well. See [LlmMessage.Builder] for more.
     *
     * @see AwsMessage
     * @see LlmMessage
     * @see LlmMessage.Builder
     * @see MessagesManager
     *
     * @author Luca Buoncompagni © 2025
     */
    private inner class Builder : LlmMessage.Builder<Message> {

        // See documentation on `LlmMessage`
        override fun public() = this@AwsMessage
    }

    companion object {

        /**
         * Implementation of the [LlmMessage] factory.
         * @return A new [AwsMessage] with both public and private functionalities.
         */
        fun build(): LlmMessage.Builder<Message> = AwsMessage().Builder()
    }
}


/**
 * Object to convert [MessagesManager] data into AWS DynamoDB format.
 *
 * @see MessagesManager.toMessagesMap
 * @see MessageData.toMap
 * @see MetaMessage.Builder.toMap
 *
 * @author Luca Buoncompagni © 2025
 */
object DynamoDBMessage: Loggable() {

    /**
     * Converts a generic `List<Map<String, Any?>>` into a list of AWS DynamoDB [AttributeValue] format. It invokes
     * [toAttributeValue] for each element in the map, see it to know more about the handled types.
     *
     * @param list The input list to convert.
     * @return The input list converted in a format compatible with DynamoDB.
     */
    fun toDynamoDB(list: List<Map<String, Any?>>): List<Map<String, AttributeValue>> =
        list.map { toDynamoDB(it) }


    /**
     * Converts a generic `Map<String, Any?>` into a map of AWS DynamoDB [AttributeValue] format. It invokes
     * [toAttributeValue] for each element in the map, see it to know more about the handled types.
     *
     * @param map The input map to convert.
     * @return The input map converted in a format compatible with DynamoDB.
     */
    private fun toDynamoDB(map: Map<String, Any?>): Map<String, AttributeValue> =
        map.mapValues { (_, value) -> toAttributeValue(value) }


    /**
     * Internal recursive function to convert a single value into [AttributeValue].
     *
     * This function recursively processes nested data structures and converts them to DynamoDB-compatible
     * [AttributeValue] objects. It handles the following types: `String`, `Boolean`, `Number`, `ByteArray`, `null`,
     * `Set<String>`, `Set<Number>`, `Set<ByteArray>`, `List<Any?>` (where elements are of previous types included
     * nested list or map), and `Map<String, Any?>` (where values are of previous types included nested list or map).
     *
     * @param value The value to convert.
     * @return AttributeValue The converted DynamoDB attribute value.
     * @throws IllegalArgumentException if an unsupported type is encountered.
     */
    @Suppress("UNCHECKED_CAST")
    private fun toAttributeValue(value: Any?): AttributeValue {
        try {
            return when (value) {
                null -> AttributeValue.builder().nul(true).build()
                is String -> AttributeValue.builder().s(value).build()
                is Boolean -> AttributeValue.builder().bool(value).build()
                is Number -> AttributeValue.builder().n(value.toString()).build()
                is ByteArray -> AttributeValue.builder().b(SdkBytes.fromByteArray(value)).build()
                is Set<*> -> when {
                    value.all { it is String } -> AttributeValue.builder()
                        .ss(value as Set<String>)
                        .build()

                    value.all { it is Number } -> AttributeValue.builder()
                        .ns((value as Set<Number>).map { it.toString() })
                        .build()

                    value.all { it is ByteArray } -> AttributeValue.builder()
                        .bs((value as Set<ByteArray>).map { SdkBytes.fromByteArray(it) })
                        .build()

                    else -> {
                        logError("Set must contain only String, Number, or ByteArray elements")
                        return getErrorAttribute()
                    }
                }
                is List<*> -> AttributeValue.builder().l(value.map { toAttributeValue(it) }).build()

                is Map<*, *> -> {
                    // Verify all keys are strings
                    if (value.keys.any { it !is String }) {
                        logError("Map keys must be all strings. Cannot covert '{}'.", value)
                        return getErrorAttribute()
                    }
                    val v = value as Map<String, Any?>
                    AttributeValue.builder().m(v.mapValues { toAttributeValue(it.value) }).build()
                }

                else -> {
                    logError("Cannot convert type {} into a DynamoDB equivalent.",
                        value::class.java.simpleName)
                    return getErrorAttribute()
                }
            }
        } catch (e: Exception) {
            logError("Error converting value to DynamoDB attribute: {}", e.message)
            return getErrorAttribute()
        }
    }


    /**
     * Returns `true` if the input represents a `null` attribute value.
     * @param attribute The attribute to check.
     * @return `true` if the input represents a `null` attribute value, `false` otherwise.
     */
    fun isNullAttribute(attribute: AttributeValue): Boolean {
        return attribute.type() == AttributeValue.Type.NUL
    }


    /**
     * The attribute returned in case of error, it represents a `null` value as
     * `AttributeValue.builder().nul(true).build()`
     * @return A null attribute value for DynamoDB.
     */
    private fun getErrorAttribute(): AttributeValue {
        return AttributeValue.builder().nul(true).build()
    }
}

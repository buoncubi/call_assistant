package digital.boline.callAssistant.llm.message

import digital.boline.callAssistant.Loggable
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole
import software.amazon.awssdk.services.bedrockruntime.model.Message
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import java.lang.Thread.sleep
import kotlin.test.assertEquals


/**
 * The Unit test for the implementations in the
 * `llm.message` package.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class AwsMessagesManagerTest {


    @Test
    fun simpleExample() {

        // Create the message manager.
        val manager: MessagesManager<Message> = buildAwsMessagesManager()

        // Add some messages
        manager.addAssistant("Hello!") // It creates a fake message since the first message should come form the user
        manager.addUser("Hi, there")
        manager.addUser("how are you") // It will be merged with previous message since the role should alternate.
        manager.addAssistant(listOf("I am fine,", "and you?"))
        manager.addMessage(MetaRole.USER, listOf("Good, ", "thank you!"))

        // Get the messages to be give to the LLM model
        //val llmMessages: List<Message> = manager.messages

        // Perform summarization
        val summaryInfo: Summarizing = manager.getSummaryInfo()  // It will not include the last user message.
        //val toSummarize: String = summaryInfo.format()
        val summary = "..." // It should use LLM to process `toSummarize` and provide a `summary` string.
        manager.addSummary(summary, summaryInfo)

        // Add some other messages
        manager.addAssistant("How can I help you?")

        // Get the message after summarization, now previous messages are ignored.
        //val llmMessagesAfterSummarization: List<Message> = manager.messages

        // Convert the message into a serializable map.
        //val serializedMessages1: List<Map<String, Any>> = manager.toMessagesMap(incremental = true, excludeLast = true)

        // Add some other messages and manipulate metadata
        manager.addAssistant("Are you still there?")  // It will be merged with previous message. Be mindful of `excludeLast`.
        val userMessage: MessageWrapper<Message>? = manager.addUser("Yes, but I do not need help!")

        // Add some dummy metadata.
        userMessage?.metadata?.addData("stopped", true)
        userMessage?.metadata?.addTiming(MetaTiming.PLAY_START, System.currentTimeMillis())

        // Convert the messages into a serializable map. And take the last message
        //val serializedMessages2: List<Map<String, Any>> = manager.toMessagesMap(incremental = true, excludeLast = false)

        // Convert the messages into the DynamoDB format.
        //val dynamoMessages: List<Map<String, AttributeValue>> = DynamoDBMessage.toDynamoDB(serializedMessages1 + serializedMessages2)

        // Get the last summary message to prompt the LLM model with previous messages
        //val lastSummary: List<String>? = manager.getLastSummary()?.contents

        println(manager)
    }

    object DummyLogger: Loggable(){
        val publicLogger = logger
    }

    // Test the behaviour of MessagesManager while adding new messages.
    @Test
    fun testAddingMessages() {

        // A helping class for generating messages.
        data class TestData(val role: MetaRole, val contents: List<String>)

        // The messages used in this testing function.
        val testMessages = listOf(
            TestData(MetaRole.ASSISTANT, listOf("Hello", "how can I help you", "today?")),
            TestData(MetaRole.USER, listOf("Hi")),
            TestData(MetaRole.USER, listOf("there!", "How are you?")),
            TestData(MetaRole.ASSISTANT, listOf("I am fine, and you?")),
            TestData(MetaRole.USER, listOf("Good, ", "thank you!")),
            TestData(MetaRole.ASSISTANT, listOf("Nice to known.")),
            TestData(MetaRole.USER, listOf("OK, ", "Bye!")),
            TestData(MetaRole.USER, listOf("Bye!")),
            TestData(MetaRole.ASSISTANT, listOf("Bye!"))
        )

        // Generate the expected message to be used for assertions based on `testMessages`.
        fun getExpectedMassages(): List<MessageWrapper<Message>> {

            // Build the expected list of messages based on test data.
            val out = mutableListOf<MessageWrapper<Message>>()
            var previousRole: MetaRole? = null
            for ((idx, msg) in testMessages.withIndex()){

                // Eventually add a fake message.
                if (idx == 0 && msg.role == MetaRole.ASSISTANT) {
                    out.add(
                        buildAwsMetaMessage(
                            MetaRole.USER,
                            listOf(MessagesManager.FAKE_MESSAGE_CONTENTS),
                            logger = DummyLogger.publicLogger
                        )
                    )
                }

                // Eventually merge messages if the role is not alternating
                if (previousRole != null){
                    if (previousRole == msg.role) {
                        // Append to previous.
                        val previousMsg = out.last()
                        out.removeLast()
                        out.add(buildAwsMetaMessage(previousRole,
                            previousMsg.contents.map { it } + msg.contents,
                            previousMsg.id,
                            DummyLogger.publicLogger))
                        continue
                    }
                }
                // Add new.
                out.add(buildAwsMetaMessage(msg.role, msg.contents, logger = DummyLogger.publicLogger))
                // Update for next loop.
                previousRole = msg.role
            }
            return out
        }

        fun simpleAssert(manager: MessagesManager<Message>): Boolean {
            val metaMessage = manager.metaMessages
            val expected = getExpectedMassages()
            for ((idx, msg) in metaMessage.withIndex()) {
                if (msg.role != expected[idx].role || msg.contents != expected[idx].contents)
                    // Always fail
                    assertTrue(false, "Error while retrieving the messages for Bedrock")
            }
            return true
        }

        println("Test adding messages (it would generate warnings and errors on purpose)...")

        // Create the message manager.
        val messagesManager = buildAwsMessagesManager()

        // Test messages
        for (msg in testMessages) {
            messagesManager.addMessage(msg.role, msg.contents)
        }

        // Assert `role`, `contents`, and transformation on AWS message.
        simpleAssert(messagesManager)

        println("Adding test done!")
    }



    // Count the number of test just for simplify visual inspection. It is used in `printTestSection()`.
    private var testCounter = 1

    // Number of messages `contents`, used to make unique message text, e.g., 'U.x.1', 'A.x.2', 'A.x.3', etc.
    // It is used and incremented in `addMessage()`
    private var contentsCounter = 0

    // Number of messages added to the `MessagesManager`, e.g, 'U.1.x', 'U.2.x', 'A.3.x', etc.
    // It is incremented in `addMessage()` and used `assertSummarization()` and `testSummarization()`.
    private var messagesCounter = 0

    // Count how many summarization has been done so far. Summarization that do  nothing should not be counted.
    private var summarizationCount = 0

    // The simulation of the summarized message. It is computed by `summarize()` (based on `nestedList2string()`), and
    // used for test assertions in `summarize()` and `assertSummarization()`.
    private var summarizationMessageCopyString = ""


    // Used to manage the `summarizationCount` if two consecutive equal roles are added.
    private var lastRoleAdded: MetaRole? = null


    // Create a message with dummy text given a `role` and the number of required `contents`
    // (i.e., 'Role.messageCounter.contentCounter', e.g., 'U.2.3').
    private fun addMessage(role: MetaRole, contentSize: Int, messagesManager: MessagesManager<Message>){
        // Note that `role` cannot be `SUMMARY`
        if (role == MetaRole.SUMMARY)
            throw IllegalArgumentException("Role cannot be SUMMARY")

        // Get role identifier prefix, i.e., 'U' (user) or 'A' (assistant).
        val prefix = if (role == MetaRole.USER) "U" else "A"

        // Compute the message's contents.
        val contents = mutableListOf<String>()
        for (i in 0..<contentSize) {
            contents.add("$prefix.$messagesCounter.$contentsCounter")
            contentsCounter += 1
        }

        // Keep track of number of message. It is used for assertion.
        if(lastRoleAdded == null)
            messagesCounter += 1
        else if (role != lastRoleAdded)
            summarizationCount += 1
        lastRoleAdded = role

        // Add a dummy message.
        messagesManager.addMessage(role, contents)
    }


    // Convert a List<List<*>> in a string where all the items of the inner lists are appended with a `-`.
    // For instance [[a,b,c],[d,e,f],[g,h,i]] will become "a-b-c-d-e-f-g-h-i".
    // This function is used for printing and for tests assertions in `assertSummarization()`.
    private fun nestedList2string(nestedList: List<List<*>>): String {
        val sb = StringBuilder()
        var prefix = ""
        nestedList.forEach { innerList ->
            innerList.forEach { item ->
                sb.append(prefix)
                sb.append(item)
                prefix = "-"
            }
        }
        return sb.toString()
    }


    // Perform summarization and uses strings given by `nestedList2string` as simulations of message summary.
    // `summaryOffset` is the number of messages added from when the `summarizing` info has been retrieved, it is used for assertion.
    private fun summarize(messagesManager: MessagesManager<Message>, shouldDoNothing: Boolean = false,
                          summarizing: Summarizing? = null, summaryOffset: Int = 0) {

        // Get summary info.
        val info = summarizing ?: messagesManager.getSummaryInfo()

        // Check if `computeSummaryInfo()` was successful.
        if(info.isEmpty()) {
            if (shouldDoNothing) {
                // Everything fine.
                return
            } else {
                // Always fail
                assertTrue(false, "Summarization should do something, but did nothing.")
            }
        }

        // Print summary info for visual inspection.
        println("------------")
        println("\t__Text to be summarised:__\n$info")
        println("------------")

        // Make the `messages` as a `List<List<String>>` to be used by `nestedList2string()`, and drop last element if
        // it has the USER `role` (as it would be done by `MessagesManager`). Also, it discards fake messages, which has
        // "..." has content.
        val messageList = messagesManager.messages.dropLast(summaryOffset)
            .let { list ->
                if (list.lastOrNull()?.role() == ConversationRole.USER) {
                    list.dropLast(1)
                } else {
                    list
                }
            }
            .map { it2 -> it2.content().mapNotNull { if(it.text() != "...") it.text() else null} }

        // Make a copy before summarization, it will be used in `assertSummarization()`.
        val messageCopyString = nestedList2string(messageList)
        if (summarizationMessageCopyString.isEmpty()) {
            summarizationMessageCopyString = messageCopyString
        } else {
            // Keep track of all the messages previously summarized to be sure that no messages are missing.
            summarizationMessageCopyString += "-$messageCopyString"
        }

        // Simulate a summarizing test.

        val listInfo = mutableListOf<List<String>>()
        if (info.previousSummary != null)
            listInfo.add(info.previousSummary!!.contents)
        listInfo.addAll(info.messages.map { it.contents })

        val summary = nestedList2string(listInfo)
        // Perform summarization
        messagesManager.addSummary(summary, info)

        // Increase counter that will be used for  assertions.
        summarizationCount += 1
    }


    // This is used only to make easier reading the printed outcome.
    private fun printTestSection(title: String = "summarization") {
        // Wait to be sure to flush all asynchronous logs.
        sleep(100)
        val cntString = when (testCounter) {
            1 -> "1st"
            2 -> "2nd"
            3 -> "3rd"
            else -> "${testCounter}th"
        }
        println("-------------------------------------------------")
        println("\t\t\t\t $cntString ${title.uppercase()}")
        testCounter += 1
    }


    // Check that the summarization results are well done. It works if a summarization process has just occurred.
    // `summaryOffset` is the number of messages added from when the `summarizing` info has been retrieved, it is used for assertion.
    private fun assertSummarization(messagesManager: MessagesManager<Message>, shouldDoNothing: Boolean = false,
                                    summarizing: Summarizing? = null, summaryOffset: Int = 0){

        // Summarize messages
        summarize(messagesManager, shouldDoNothing, summarizing, summaryOffset)

        println(messagesManager)

        /*
        // Assert no `message` (except for the last user message that the assistant did not address) remaining after
        // summarization.
        assertTrue(
            messagesManager.messages.isEmpty() ||
                    (messagesManager.messages.size == 1 && messagesManager.messages[0].role() == ConversationRole.USER),
            "Error while summarizing user only messages."
        )
         */

        // Assert that the size of `metaMessage` is such to log all messages over time.
        assertEquals(
            messagesManager.metaMessages.size,
            messagesCounter + summarizationCount,
            "Error while summarizing user only meta-message."
        )

        // Stop asserting if the summarization process should not change `message` and `metaMessage`.
        if (shouldDoNothing)
            return

        // Retrieve the `message` related to a summary.
        val lastMetaMessage = messagesManager.getLastSummary()
        // Get the list of IDs related to all the `messages` that the was summarized during the last summarization.
        val summarizedIds = lastMetaMessage?.metadata?.summaryIds


        // Continue if there were some `messages` that have been summarized. Otherwise, fail the test.
        if (summarizedIds != null) {

            // Get all items that produced the last summarization `message` by IDs.
            val messageByIds = messagesManager.metaMessages.filter {
                summarizedIds.contains(it.id)
            }

            // Rebuild the summarization text from the items retrieved by id (i.e., as done in `summarize()`).
            val summarizedMessageById = nestedList2string(messageByIds.map { it.contents })

            // Check that the summarization `message` just built is equal to the one in the `metaMessage`.
            assertEquals(summarizedMessageById, lastMetaMessage.contents[0],
                "Error while summarization assertion within meta-message.")

            // Check that the summarization `message` just built is equal to the one generated in `summarize()`.
            assertEquals(summarizedMessageById, summarizationMessageCopyString,
                "Error while summarization assertion between message and meta-message.")

        } else // Always fails.
            assertTrue(false, "Error while retrieving the summary ids for summarization assertion.")
    }


    @Test
    fun testSummarization() {

        testCounter = 1

        // Instance the message manager.
        val messagesManager = MessagesManager { logger ->
            MetaMessage.build(AwsMessage.build(logger), logger)
        }

        // 1st ---------------------------------------------------------------------------
        printTestSection("SUMMARIZING EMPTY MESSAGES LIST")
        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)


        // 2nd ---------------------------------------------------------------------------
        printTestSection("SUMMARIZING (SHOULD DO NOTHING)")
        // Add messages.
        addMessage(MetaRole.USER, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)


        // 3rd ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)


        // 4th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.USER, 2, messagesManager)
        addMessage(MetaRole.ASSISTANT, 2, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)


        // 5th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.ASSISTANT, 2, messagesManager)
        addMessage(MetaRole.USER, 2, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        addMessage(MetaRole.USER, 3, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)

        // 6th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)


        // 7th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)


        // 8th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages.
        addMessage(MetaRole.USER, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)


        // 9th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager)


        // 10th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 3, messagesManager)
        addMessage(MetaRole.USER, 5, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        /// Summarize, assert and print.
        assertSummarization(messagesManager)


        // 11th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)


        // 11th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)



        // 12th ---------------------------------------------------------------------------
        printTestSection()
        // Add messages
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)
        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)

        val summarizing = messagesManager.getSummaryInfo()

        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)

        println(messagesManager)
        assertSummarization(messagesManager, summarizing = summarizing, summaryOffset = 2)

        addMessage(MetaRole.USER, 1, messagesManager)
        addMessage(MetaRole.ASSISTANT, 1, messagesManager)

        // Summarize, assert and print.
        assertSummarization(messagesManager, shouldDoNothing = true)

        // ---------------------------------------------------------------------------
        println("Summarization test done!")
    }


    // Get all the keys of a list of maps with nested list and maps. This is done to assert data structures even if
    // their actual value associated with each key is not checked. It assumes that keys are all unique.
    private fun getListKeys(objList: List<Any?>): List<Set<String>> {

        fun getAllKeys(obj: Any?): Set<String> { // Helper function
            return when (obj) {
                is Map<*, *> -> {
                    obj.entries.flatMap { (key, value) ->
                        buildSet {
                            if (key is String) add(key)
                            addAll(getAllKeys(value))
                        }
                    }.toSet()
                }

                is List<*> -> {
                    obj.flatMap { getAllKeys(it) }.toSet()
                }

                is AttributeValue -> {
                    when {
                        obj.hasM() -> getAllKeys(obj.m())
                        obj.hasL() -> getAllKeys(obj.l())
                        else -> emptySet()
                    }
                }

                else -> emptySet()
            }
        }

        val out = mutableListOf<Set<String>>()
        objList.forEach{ out.add(getAllKeys(it)) }
        return out
    }


    private fun assertMessageMap(messagesManager: MessagesManager<Message>, incremental: Boolean, excludeLast: Boolean) {
        // Convert messages to Map and DynamoDB
        val map = messagesManager.toMessagesMap(incremental, excludeLast)
        val dynamoDb = DynamoDBMessage.toDynamoDB(map)


        println("\tMap message to be stored on NoSQL DB:")
        println(map.joinToString(prefix = "\t\t- ", separator = ",\n  ") { it.toString() })

        println("\tDynamoDB message to be stored on NoSQL DB:")
        println(dynamoDb.joinToString(prefix = "\t\t- ", separator = ",\n  ") { it.toString() })

        // Assert that keys sets are equal.
        assertEquals(getListKeys(map), getListKeys(dynamoDb),
            "Error while checking keys of `toMessagesMap()` and `toDynamoDB()`")
    }


    @Test
    fun testMetadata() {

        testCounter = 1

        // Create the message manager.
        val messagesManager = buildAwsMessagesManager()

        // Setup for `messageToMap` function.
        Summarizing.Formatter.userTag = "Utente"
        Summarizing.Formatter.assistantTag = "Assistente"
        Summarizing.Formatter.summaryTag = "Dialogo Precedente"

        // 1st ---------------------------------------------------------------------------
        printTestSection("TESTING MAP AND DYNAMO DB CONVERSION WITH NO MESSAGES")

        val map1 = messagesManager.toMessagesMap(incremental = true, excludeLast = false)
        assertTrue(map1.isEmpty(), "Error while testing `toMessagesMap()` with no messages.")

        val dynamoDb1 = DynamoDBMessage.toDynamoDB(map1)
        assertTrue(dynamoDb1.isEmpty(), "Error while testing `toDynamoDB()` with no messages.")


        // 2nd ---------------------------------------------------------------------------
        printTestSection("TESTING MAP AND DYNAMO DB CONVERSION WITH ONE MESSAGE")
        // Add messages.
        messagesManager.addUser("U1")
        // Compute Map and DynamoDB, and assert that they have the same keys.
        assertMessageMap(messagesManager, incremental = false, excludeLast = false)
        println("----------------------------")
        assertMessageMap(messagesManager, incremental = false, excludeLast = true)
        println("----------------------------")
        assertMessageMap(messagesManager, incremental = true, excludeLast = false)


        // 3rd ---------------------------------------------------------------------------
        printTestSection("TESTING MAP AND DYNAMO DB CONVERSION WITH MERGED MESSAGE")
        // Add messages.
        messagesManager.addAssistant("A2")
        messagesManager.addUser("U2.1")
        // Compute Map and DynamoDB, and assert that they have the same keys.
        assertMessageMap(messagesManager, incremental = true, excludeLast = true)

        // Test `excludeLast`.
        println("----------------------------")
        messagesManager.addUser("U2.2")
        assertMessageMap(messagesManager, incremental = true, excludeLast = true) // Should do nothing.
        println("----------------------------")
        assertMessageMap(messagesManager, incremental = true, excludeLast = false)

        // 4th ---------------------------------------------------------------------------
        printTestSection("TESTING MAP AND DYNAMO DB CONVERSION WITH SOME MESSAGE AND EXTRA DATA")
        // Add messages
        val metaMessageA3 = messagesManager.addAssistant("A3")
        messagesManager.addUser(listOf("U3.1", "U3.2"))
        messagesManager.addAssistant(listOf("A4.1", "A4.2"))
        messagesManager.addUser("U4")
        val metaMessageA5 = messagesManager.addAssistant("A5")
        val metaMessageU5 = messagesManager.addUser("U5")

        // Set some data.
        metaMessageA3?.metadata?.addData("Some data", mapOf("Some key" to "Some value",
            "a list" to listOf(1, 2, 3, listOf(4, 5, mapOf("a" to 1, "b" to 2)))))
        metaMessageA5?.metadata?.addTiming(MetaTiming.PLAY_END, System.currentTimeMillis())

        metaMessageU5?.metadata?.addTiming(MetaTiming.PLAY_START, System.currentTimeMillis())
        metaMessageU5?.metadata?.addAttributes(MetaAttribute.MERGED)
        metaMessageU5?.metadata?.addData("my data", true)

        // Compute Map and DynamoDB, and assert that they have the same keys.
        assertMessageMap(messagesManager, incremental = true, excludeLast = true)
        println("----------------------------")
        assertMessageMap(messagesManager, incremental = true, excludeLast = false)
    }
}
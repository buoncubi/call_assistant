package digital.boline.callAssistant.llm.prompt

import digital.boline.callAssistant.llm.prompt.PromptsManager.Companion.MESSAGE_SUMMARY_TITLE_KEY
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.config.Configurator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.lang.Thread.sleep
import kotlin.system.measureNanoTime

/**
 * The Unit test for the implementations in the
 * `llm.prompt` package.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class ParsedPromptsTest {

    companion object {
        // eventually print parsed data for visual inspection.
        private const val SHOULD_PRINT_PARSED_RESULTS = false

        // insert tabs on strings, which otherwise are not escaped.
        private const val TAB = "\t"

        // The path where test prompt files are stored.
        private const val TEST_BASE_FILE_PATH = "src/test/resources/prompts/"
        // The prompt file to load.
        private const val TEST_PROMPT_FILE_PATH = "${TEST_BASE_FILE_PATH}test_example.prompt"
        // The prompts file where data is serialized in Json.
        private const val TEST_SAVE_PROMPT_JSON_FILE_PATH = "${TEST_BASE_FILE_PATH}test_example_parsed.json"
        // The prompts file where data is serialized in Bytes.
        private const val TEST_SAVE_PROMPT_BYTE_FILE_PATH = "${TEST_BASE_FILE_PATH}test_example_parsed.bytes"

        @JvmStatic
        @BeforeAll
        fun setupLogging() {
            Configurator.setLevel(PromptsParser::class.java.packageName, Level.WARN)
        }

        // Assert the correctness of parsed data from the test file.
        private fun assertPromptFile(parsed: PromptsManager?) {
            assertEquals(
                mapOf("version" to "1.0", "environment" to "test", "application name" to "Call Assistant",
                    MESSAGE_SUMMARY_TITLE_KEY to "Previous Dialogue"),
                parsed?.metadata,
                "Error while testing `metadata` on fields retrieval !!!"
            )

            assertEquals(
                mapOf(
                    "Context" to listOf(
                        Occurrence("getDate", 65, 74),
                        Occurrence("getTime", 78, 85)
                    ),
                    "Role" to listOf(
                        Occurrence("getDate", 101, 110)
                    )
                ),
                parsed?.variables,
                "Error while getting `variablesOccurrences` on fields retrieval !!!"
            )

            assertEquals(
                mapOf(
                    "Context" to "Nowadays, spam caller are getting better and better.\n\nNow is the {{today}} at {{now}}.",
                    "Role" to "You are the assistant of Mrs. Mario Rossi, and\nyou need to answer the phone when he is busy, and the\n{{today}} it is.",
                    "Action" to "  1. Ask for the reason of the call.\n  2. Ask for a phone number to eventually call back who\n     called Mrs. Mario."
                ),
                parsed?.rawPrompts,
                "Error while getting `prompts` in fields retrieval !!!"
            )


            val date = VariablesFunction.getDate()
            val time = VariablesFunction.getTime()
            assertEquals(
                mapOf(
                    "Context" to "Nowadays, spam caller are getting better and better.\n\nNow is the $date at $time.",
                    "Role" to "You are the assistant of Mrs. Mario Rossi, and\nyou need to answer the phone when he is busy, and the\n$date it is.",
                    "Action" to "  1. Ask for the reason of the call.\n  2. Ask for a phone number to eventually call back who\n     called Mrs. Mario."
                ),
                parsed?.prompts,
                "Error while getting `prompts` in fields retrieval !!!"
            )
        }

        // Print parsed data for visual inspection.
        private fun printResults(parsed: TypedPrompts?) {
            // Print final results
            if (SHOULD_PRINT_PARSED_RESULTS) {
                println("-------------------")
                println(parsed)
                println("-------------------")
            }
        }
    }

    @Test
    fun `test empty file`() {
        println("\tTest parsing an empty file (or empty string).")

        // Parse empty prompts.
        val prompts: StringBuilder = StringBuilder("")
        val parsed = PromptsParser.parse(prompts)

        // Assert correctness.
        assertTrue(parsed.metadata.isEmpty(), "Error while getting `metadata` from empty file !!!")

        assertTrue(parsed.constants.isEmpty(), "Error while getting `constants` from empty file !!!")

        assertTrue(
            parsed.variablesDefinition.isEmpty(),
            "Error while getting `variablesDefinition` from empty file !!!"
        )

        assertTrue(
            parsed.variables.isEmpty(),
            "Error while getting `variablesOccurrences` from empty file !!!"
        )

        assertTrue(parsed.prompts.isEmpty(), "Error while getting `prompts` from empty file !!!")
    }


    @Test
    fun `test comments`() {
        println("\tTest comments (it should arise WARNING and/or ERRORS on purpose).")

        // Define the prompts using the designed syntax.
        val promptSyntax = StringBuilder(
            """
        | // This is a comment
        |/* This is a
        |block comment*/
        |
        |__ Title 1 __
        |This is a section. // This is another comment
        |Note that /* since it requires a parser not
        |based on RegEx */ nested block
        |comments are not supported. 
        |
        |As a(n) /*stupid*/ example,
        |the following text should arise an error! 
        |/* an /* invalid */ comment*/
        |
        |Nonetheless, the test is designed to pass. // since the error is made on purpose.
        | 
        """.trimMargin("|")
        )

        // Parse the prompts.
        val prompts: StringBuilder = StringBuilder(promptSyntax)
        val parsed = PromptsParser.parse(prompts)

        // Assert correctness.
        printResults(parsed)

        assertTrue(
            parsed.metadata.isEmpty(),
            "Error on `metadata` while testing comments !!!"
        )

        assertTrue(
            parsed.constants.isEmpty(),
            "Error on `constants` while testing comments !!!"
        )

        assertTrue(
            parsed.variablesDefinition.isEmpty(),
            "Error on `variablesDefinition` while testing comments !!!"
        )

        assertTrue(
            parsed.variables.isEmpty(),
            "Error on `variablesOccurrences` while testing comments !!!"
        )

        assertEquals(
            mapOf(
                "Title 1" to "This is a section.\nNote that nested block\ncomments are not supported.\n\nAs a(n) example,\nthe following text should arise an error!\n comment*/\n\nNonetheless, the test is designed to pass.",
            ),
            parsed.prompts,
            "Error on `prompts` while testing comments !!!"
        )
    }


    @Test
    fun `test space normalization`() {
        println("\tTest space, lines and indentation management.")

        // Define the prompts using the designed syntax.
        val promptSyntax = StringBuilder(
            """
            |
            | 
            |    __ Title 1 __ 
            |    This  is a     not well      
            |formed$TAB$TAB$TAB text $TAB   
            |  
            | 
            |
            |
            | - only one black line
            |    - preserves space when line start   
            |$TAB-   preserves indentation when line start.   
            |
            |$TAB$TAB${TAB}After parsing   it will be well formatted.  $TAB   
            |
            | $TAB$TAB  
            |    
            |        
        """.trimMargin("|")
        )

        // Parse the prompts.
        val prompts: StringBuilder = StringBuilder(promptSyntax)
        val parsed = PromptsParser.parse(prompts)

        // Assert correctness.
        printResults(parsed)

        assertTrue(
            parsed.metadata.isEmpty(),
            "Error on `metadata` while testing space normalization !!!"
        )

        assertTrue(
            parsed.constants.isEmpty(),
            "Error on `constants` while testing space normalization !!!"
        )

        assertTrue(
            parsed.variablesDefinition.isEmpty(),
            "Error on `variablesDefinition` while testing space normalization !!!"
        )

        assertTrue(
            parsed.variables.isEmpty(),
            "Error on `variablesOccurrences` while testing space normalization !!!"
        )

        assertEquals(
            mapOf(
                "Title 1" to "    This is a not well\nformed text\n\n - only one black line\n    - preserves space when line start\n\t- preserves indentation when line start.\n\n\t\t\tAfter parsing it will be well formatted.",
            ),
            parsed.prompts,
            "Error on `prompts` while testing comments !!!"
        )
    }


    @Test
    fun `test fields and constant replacement`() {
        println("\tTest constants and variables (it should arise WARNING and/or ERRORS on purpose).")

        // Define the prompts using the designed syntax.
        val promptSyntax = StringBuilder("""
        |
        | __*mETA*__
        | m1= data 1
        | m2 = data2
        |__  *   Const *     __
        |  -  name=Sig.   Mario
        |  -  last name=     Rossi
        |  -  ill-formatted= my=val // This generates a warning.
        |  -  ill-formatted2=  // This generates an error.
        |    
        |    __ * META * __ // This generates a warning.
        |    
        |__* VAR *__
        |  -   today   =   getDate
        |  -   get now$TAB$TAB=$TAB${TAB}getTime
        |  - myVar = var1 // It generates an error
        |    
        |__ Title 1 __
        |This section relates to {{  name  }} {{last name}} the {{today}} at {{get now}}.
        |
        |__ Title 2 __
        |Here {{last name}} knows that today is {{today}} at {{get now}}. {{name}} wants to be sure that is {{get now}} ({{today}}).
        |{{unknownVar}} // This generates an error.
        |
        |    __ * var * __
        | my unfeasible var 1 = functino name // It generates an error.
        | my unfeasible var 2 = 0functinoName // It generates an error.
        | my unfeasible var 3 = functino!name // It generates an error.
        |
        | __* meta * __
        |  m2 = data3 // This generates an warning.
        |  m4 = data 4
        | 
        """.trimMargin("|"))

        // Parse the prompts.
        val prompts: StringBuilder = StringBuilder(promptSyntax)
        val parsed = PromptsParser.parse(prompts)

        // Assert correctness.
        printResults(parsed)

        assertEquals(
            mapOf("m1" to "data 1", "m2" to "data3", "m4" to "data 4"),
            parsed.metadata,
            "Error while testing `metadata` on fields retrieval !!!"
        )

        assertEquals(
            mapOf("name" to "Sig. Mario", "last name" to "Rossi"),
            parsed.constants,
            "Error while getting `variablesDefinition` on fields retrieval !!!"
        )

        assertEquals(
            mapOf("today" to "getDate", "get now" to "getTime"),
            parsed.variablesDefinition,
            "Error while testing `metadata` on fields retrieval !!!"
        )

        assertEquals(
            mapOf(
                "Title 1" to listOf(
                    Occurrence("getDate", 45, 54),
                    Occurrence("getTime", 58, 69)
                ),
                "Title 2" to listOf(
                    Occurrence("getDate", 31, 40),
                    Occurrence("getTime", 44, 55),
                    Occurrence("getTime", 93, 104),
                    Occurrence("getDate", 106, 115)
                )
            ),
            parsed.variables,
            "Error while getting `variablesOccurrences` on fields retrieval !!!"
        )

        assertEquals(
            mapOf(
                "Title 1" to "This section relates to Sig. Mario Rossi the {{today}} at {{get now}}.",
                "Title 2" to "Here Rossi knows that today is {{today}} at {{get now}}. Sig. Mario wants to be sure that is {{get now}} ({{today}}).\n{{unknownVar}}"
            ),
            parsed.prompts,
            "Error while getting `prompts` in fields retrieval !!!"
        )
    }


    @Test
    fun `test titles and sections`() {
        println("\tTest titles and divisions in sections (it should arise WARNING and/or ERRORS on purpose).")

        // Define the prompts using the designed syntax.
        val promptSyntax = StringBuilder(
            """
            |   Silently disregarded section since it does not have a title.
            |
            |__Title1__
            |1st section's content.
            |  __ Title   2 __
            |2nd section's content.
            |
            |
            |$TAB${TAB}__   Title3__
            |3rd section's content.
            |
            | ! __Title4   __  //'!' generates an error.
            |4th section's content.
            |
            |  __Title 5 __     
            |5th section's content.
            |
            | __* ??? *__
            | A wrong special sections.
            |
            |    __   Title 5   __
            |    
            |Another content appended to the 5th section.
            |
            | ____
            | A section without a title // It generates a warning.
            |
            |// The following sections are not feasible and generate errors.
            |__ Title 6__6th section's content.           
            |__ Title 7 __ 7th section's content.
            |__Title 8__8th section's content.__Title9__ 9th section's content.__Title10__10th section's content.
            |
            |  __Title 11 __ ! //'!' generates an error.
            |11th section's content.
            |
            |""".trimMargin("|")
        )

        // Parse the prompts.
        val prompts: StringBuilder = StringBuilder(promptSyntax)
        val parsed = PromptsParser.parse(prompts)

        // Assert correctness.
        printResults(parsed)

        assertTrue(
            parsed.metadata.isEmpty(),
            "Error on `metadata` while testing titles (and section division) !!!"
        )

        assertTrue(
            parsed.constants.isEmpty(),
            "Error on `constants` while testing titles (and section division) !!!"
        )

        assertTrue(
            parsed.variablesDefinition.isEmpty(),
            "Error on `variablesDefinition` while testing titles (and section division) !!!"
        )

        assertTrue(
            parsed.variables.isEmpty(),
            "Error on `variablesOccurrences` while testing titles (and section division) !!!"
        )

        assertEquals(
            mapOf(
                "Title1" to "1st section's content.",
                "Title 2" to "2nd section's content.",
                "Title3" to "3rd section's content.\n\n !",
                "Title4" to "4th section's content.",
                "Title 5" to "5th section's content.\n\nAnother content appended to the 5th section.",

                //"Title 6" to  "6th section's content.",
                //"Title 7" to  "7th section's content.",
                //"Title 8" to  "8th section's content.",
                //"Title9"  to  "9th section's content.",
                //"Title10" to "10th section's content."
            ),
            parsed.prompts,
            "Error on `prompts` while testing titles (and section division) !!!"
        )
    }


    @Test
    fun `test parsing from file and update variables`() {
        println("\tTest parsing from file (it should arise a stacktrace ERRORS on purpose).")

        // Try to use a not existing file (It will generate an error with stacktrace)
        val parsedNull = PromptsParser.parse("not-existing-file")
        assertNull(parsedNull, "Error while parsing from not existing file !!!")
        println("-------------------")

        // Parse the prompts from file
        val parsed = PromptsParser.parse(TEST_PROMPT_FILE_PATH)

        // Print final results
        printResults(parsed)

        //  Get the prompt manager.
        val promptsManager = parsed?.getPromptManager()

        // Assert correctness
        if (parsed != null) {
            assertPromptFile(promptsManager)
        } else {
            fail("Error while parsing from file !!!")
        }

        println("-------------------")
        println("\tTest variables update and reevaluation.")

        // Change variable time.
        sleep(1000) // Wait to be sure that time changed enough.
        VariablesFunction.updateTime()

        // Apply the variable again.
        promptsManager?.applyVariables()

        // Assert reevaluation variables.
        printResults(promptsManager)
        assertPromptFile(promptsManager)
    }


    @Test
    fun `test serialize and deserialize JSON`() {
        println("\tTest serialize JSON.")

        // Parse the prompts from file
        val parsed = PromptsParser.parse(TEST_PROMPT_FILE_PATH)

        // Serialize prompts
        val success = parsed?.serializeJson(TEST_SAVE_PROMPT_JSON_FILE_PATH) ?: false

        // Assert serialization correctness
        assertTrue(success, "Error while serializing to JSON !!!")

        println("-------------------")
        println("\tTest deserialize JSON.")

        // Deserialize prompts and log processing time.
        val promptsManager: PromptsManager?
        val timeNanos = measureNanoTime {
            promptsManager = PromptsDeserializer.fromJson(TEST_SAVE_PROMPT_JSON_FILE_PATH)
        }
        println("Time to deserialize json: ${timeNanos / 1000000.0} ms")

        // Assert deserialization correctness.
        printResults(promptsManager)
        assertPromptFile(promptsManager)
    }


    @Test
    fun `test serialize and deserialize Bytes`() {
        println("\tTest serialize Bytes.")

        // Parse the prompts from file.
        val parsed = PromptsParser.parse(TEST_PROMPT_FILE_PATH)

        // Serialize prompts.
        val success = parsed?.serializeBinary(TEST_SAVE_PROMPT_BYTE_FILE_PATH) ?: false

        // Assert serialization correctness.
        assertTrue(success, "Error while serializing to ByteArray !!!")

        println("\tTest deserialize Bytes.")

        // Deserialize prompts and log time required.
        val promptsManager: PromptsManager?
        val timeNanos = measureNanoTime {
            promptsManager = PromptsDeserializer.fromBytes(TEST_SAVE_PROMPT_BYTE_FILE_PATH)
        }
        println("Time to deserialize Bytes: ${timeNanos / 1000000.0} ms")

        // Assert deserialization correctness
        printResults(promptsManager)
        assertPromptFile(promptsManager)
    }


    @Test
    fun `test prompt formatting`() {
        println("\tTest prompts formatting.")

        // Parse the prompts from file and get the prompt manager.
        val manager = PromptsParser.parse(TEST_PROMPT_FILE_PATH)!!.getPromptManager()

        // Set previous summarized messages (see `MessagesInterface` from more)
        manager.messageSummary = "Previously the user asked for help."

        val minimalisticFormat = manager.formatPrompts(listOf("Action","Context"),
            includeTitle = false, includeSummary = false)
        println("----------------------")
        println(minimalisticFormat)
        println("----------------------")

        val extendedFormat = manager.formatPrompts(listOf("Action","Context"),
            includeTitle = true, includeSummary = true)
        println(extendedFormat)
        println("----------------------")


        // Assert
        val date = VariablesFunction.getDate()
        val time = VariablesFunction.getTime()
        val expectedMinimalisticFormat = "  1. Ask for the reason of the call.\n" +
                "  2. Ask for a phone number to eventually call back who\n" +
                "     called Mrs. Mario.\n\n" +
                "Nowadays, spam caller are getting better and better.\n\n" +
                "Now is the $date at $time."
        assertEquals(expectedMinimalisticFormat, minimalisticFormat, "Error while formatting minimalistic prompts !!!")

        val expectedExtendedFormat = "**Action:**\n" +
                "  1. Ask for the reason of the call.\n" +
                "  2. Ask for a phone number to eventually call back who\n" +
                "     called Mrs. Mario.\n\n" +
                "**Context:**\n" +
                "Nowadays, spam caller are getting better and better.\n\n" +
                "Now is the $date at $time.\n\n" +
                "**Previous Dialogue:**\n" +
                "Previously the user asked for help."
        assertEquals(expectedExtendedFormat, extendedFormat, "Error while formatting extended prompts !!!")
    }


    @AfterEach
    fun tearDown() {
        // Called after each test function defined above.
        println("---------------------------------------------------")
    }

}


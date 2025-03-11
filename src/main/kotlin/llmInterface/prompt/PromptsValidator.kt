/**
 * A script that can be used to validate a prompt syntax and,
 * eventually, serialize it in a file for being efficiently
 * used at runtime. This process is based on user interaction
 * through terminal. For more info see `validateAndStore()`.
 *
 * see `PromptsParser`, `ParsedPrompts`, `PromptsDeserializer`,
 * and `PromptsManager`.
 *
 * @author Luca Buoncompagni © 2025
 */

package llmInterface.prompt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.Level
import kotlinx.coroutines.Dispatchers
import sun.misc.Signal
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import kotlin.system.exitProcess
import kotlin.system.measureNanoTime


// Constants
private const val DEFAULT_JSON_FILE_EXTENSION = ".json"
private const val DEFAULT_BINARY_FILE_EXTENSION = ".bytes"
private const val DEFAULT_BASE_PATH = "src/main/resources/prompts"
private const val DEFAULT_LOGGING_LEVEL = "info"

// Input Stream
private val scanner = Scanner(System.`in`)

// Set logging level using Logback
private fun setLogger(loggingLevel: String = DEFAULT_LOGGING_LEVEL) {
    // TODO set logger properly and use input parameter from arguments
    val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
    root.level = Level.valueOf(loggingLevel.uppercase())
}


/**
 * Check if a file path exists and is a directory.
 *
 * @return a `File` reference if the path exists and is a directory, `null` otherwise.
 */
private fun directoryExists(path: String?): File? =
    try {
        val file = File(path!!)
        if(file.exists() && file.isDirectory)
            file
        else
            null
    } catch (e: Exception) {
        println("ERROR: FILE PATH DOES NOT EXISTS '$path'!")
        null
    }


/**
 * Set the base path for the prompt file and the serialization files.
 * The path must exist and be a directory (see [directoryExists]).
 *
 * @return The `File` to the base path or `null`.
 */
private fun askForWorkingDirectory(): File?{
    var output: File? = null
    println("Do you want to set a working directory?")
    println("\t Type: [Any Key] for No, [Yes] for Yes, [D] for default ('$DEFAULT_BASE_PATH').")

    print("  >  ")
    val command = scanner.nextLine().lowercase()
    when (command) {

        "y", "yes" -> {
            while (true) {
                println("\t Type the path to the working directory:")
                print("  >  ")
                val attemptPath = scanner.nextLine()
                val file = directoryExists(attemptPath)
                if (file != null) {
                    output = file
                    break
                } else {
                    println("ERROR: WORKING DIRECTORY '${attemptPath}' DOES NOT EXISTS! TRY AGAIN...")
                }
            }
        }

        "d", "default" -> {
            val file = directoryExists(DEFAULT_BASE_PATH)
            if (file != null) {
                output = file
            } else {
                println("ERROR: DEFAULT WORKING DIRECTORY DOES NOT EXISTS! EXITING...")
                exitProcess(1)
            }
        }
    }

    if (output == null)
        println("Working directory not set.")
    else
        println("Setting working directory: '${output.absolutePath}'.")
    return output
}


/**
 * Ask the user for the prompt file to parse.
 *
 * @return The `File` to the prompt file.
 */
private fun askForPromptFile(workingDirectory: File?): File {
    while (true) {
        if (workingDirectory == null) {
            println("Specify the path to the prompt file to parse:")
        } else {
            println("Specify the name of the prompt file to parse:")
        }

        print("  >  ")
        val promptFilePath = scanner.nextLine()
        val file = File(workingDirectory, promptFilePath)
        if (file.exists() && file.isFile)
            return file
        else
            println("ERROR: FILE PATH DOES NOT EXISTS '${file.absolutePath}'! TRY AGAIN...")
    }
}


/**
 * Ask the user to check the prompt results.
 * If the user does not confirm that everything is fine,
 * the program exits with a non-zero code.
 */
private fun askForCheckingPromptsOutcomes(){
    println("Check the log above, is eventing fine?")
    print("\t Type: [Any Key] for No, [Y] for Yes:")
    while (true) {
        print("  >  ")
        val command = scanner.nextLine().lowercase()

        when (command) {

            "y", "yes" -> {
                break
            }

            else -> {
                println("EXITING: Try again with a new prompt file!")
                exitProcess(1)
            }
        }
    }
}


/**
 * Data class to collect the file for Json and
 * Binary files.
 *
 * @param jsonFile The `File` to Json serialization.
 * @param  binaryFile The `File` to Binary serialization.
 *
 */
data class SerializationFiles(val jsonFile: File, val binaryFile: File)


/**
 * Ask the user if it wants to serialize the prompt file
 * using the same prompt file by with a different extension
 * (it uses [setFileExtension]). This is performed twice
 * for [DEFAULT_JSON_FILE_EXTENSION] and [DEFAULT_BINARY_FILE_EXTENSION].
 *
 * @param promptFile The `File` to the prompt file.
 *
 * @return The `File` to the serialization file, or `null` if it
 * should not be serialized or in cases of errors.
 */
private fun askForSerializeWithSameName(promptFile: File): SerializationFiles? {
    println("Do you want to serialize the prompt by using the same directory and name, but different extension?")
    print("\t Type [Y] for Yes, or any other key for No:")
    print("  >  ")
    val command1 = scanner.nextLine().lowercase()
    if (command1 == "y" || command1 == "yes") {
        val jsonFile = setFileExtension(promptFile, DEFAULT_JSON_FILE_EXTENSION)
        val binaryFile = setFileExtension(promptFile, DEFAULT_BINARY_FILE_EXTENSION)
        return if (jsonFile == null || binaryFile == null) {
            null
        } else {
            return SerializationFiles(jsonFile, binaryFile)
        }
    }
    return null
}


/**
 * Ask the user for the serialization file path.
 *
 * @return The `File` to the serialization file.
 */
private fun askForSerializationPath(workingDirectory: File?): File {
    while (true) {
        if (workingDirectory == null) {
            println("\tSpecify the path to the serialization file (file extension is not required):")
        } else {
            println("\tSpecify the name of the serialization file (file extension is not required) (Base path $workingDirectory):")
        }
        print("  >  ")
        val serializationFilePath = scanner.nextLine()
        val file = if(workingDirectory == null) {
            File(serializationFilePath)
        } else {
            File(workingDirectory, serializationFilePath)
        }
        return file
    }
}


/**
 * Set the file extension for the serialization file.
 * If the file exists ask the user if it wants to override it.
 *
 * @return The `File` to the serialization file, or `null` if it
 * should not be overridden.
 */
private fun setFileExtension(serializationFile: File, fileExtension: String): File?{
    val newFile = File(serializationFile.parent, serializationFile.nameWithoutExtension + fileExtension)
    if (newFile.exists()) {
        println("WARNING: FILE ALREADY EXISTS '${newFile.absolutePath}'.")
        println("         DO YOU WANT TO OVERRIDE IT?")
        print("\t Type [Any Key] for No, or [Y] for Yes:")
        print("  >  ")
        val command = scanner.nextLine().lowercase()
        return if (command == "y" || command == "yes") {
            newFile
        } else {
            null
        }
    }
    return newFile
}


/**
 * Ask to test deserialization of the prompt file and
 * perform simple assertions.
 *
 * @param serializationFiles The json and binary `File` that has been serialized.
 */
private fun testDeserialization(serializationFiles: SerializationFiles) {
    // Ask to test deserialization
    println("Do you want to test deserialization?")
    print("\t Type [Y] for Yes, or any other key for No:")
    print("  >  ")
    val command = scanner.nextLine().lowercase()
    if (command == "y" || command == "yes") {
        // Deserialize json.
        print("Deserializing Json prompts...")
        var deserializedJson: PromptsManager?
        val timeNanos1 = measureNanoTime {
            deserializedJson = PromptsDeserializer.fromJson(serializationFiles.jsonFile)
        }
        println(" Done in ${timeNanos1 / 1000000.0} ms.")

        // Deserialize bytes.
        println("----------------------------------------------------")
        println("Deserializing Bytes prompts...")
        var deserializedByte: PromptsManager?
        val timeNanos2 = measureNanoTime {
            deserializedByte = PromptsDeserializer.fromBytes(serializationFiles.binaryFile)
        }
        println(" Done in ${timeNanos2 / 1000000.0} ms.")

        // Assert results
        if (deserializedJson == null || deserializedByte == null) {
            println("ERROR: DESERIALIZATION FAILED!")
        } else {
            if (deserializedByte != deserializedJson) {
                println("ERROR: SOMETHING WENT WRONG ON DESERIALIZATION!")
            }
        }

        // Print results
        println("----------------------------------------------------")
        println("Deserialized data:")
        println(deserializedByte.toString())

    }
}


/**
 * The method that implements terminal-base interaction for
 * prompt syntax validation and serialization.
 *
 * This function is called by the [main] and performs the
 * following steps:
 * 1. Set the working directory
 *   ([askForWorkingDirectory], [directoryExists]).
 * 2. Parse the prompt file and check outcomes
 *   ([askForPromptFile], [PromptsParser.parse], [askForCheckingPromptsOutcomes]).
 *
 * 3. Ask for serialization path
 *   ([askForSerializeWithSameName], [askForSerializationPath], [setFileExtension]).
 * 4. Serialize prompts
 *   ([ParsedPrompts.serializeJson], [ParsedPrompts.serializeBinary]).
 * 5. Eventually test deserialization
 *   ([PromptsDeserializer.fromJson], [PromptsDeserializer.fromBytes]).
 */
fun validateAndStore() {

    // Eventually set the working directory.
    val workingDirectory = askForWorkingDirectory()

    // Parse the prompt file and check outcomes.
    val promptFile = askForPromptFile(workingDirectory)
    println("----------------------------------------------------")
    val parsedPrompt = PromptsParser.parse(promptFile)
    if (parsedPrompt == null) {
        println("ERROR: PROMPT FILE IS NOT VALID! EXITING.")
        exitProcess(1)
    }
    println("----------------------------------------------------")

    // Exit if the user does not confirm that everything is fine or continue.
    println("----------------------------------------------------")
    askForCheckingPromptsOutcomes()
    println("Good! Then I will serialize the file in Json and Byte format.")

    // Ask for serialization path.
    val serializationFiles: SerializationFiles
    while (true) {
        // Do you want to only change the file extension and serialize?
        val files = askForSerializeWithSameName(promptFile)
        if (files != null) {
            serializationFiles = files
            break
        }

        // Specify both the serialization file.
        var jsonFilSet = false
        while (true) {
            val serializationPath = askForSerializationPath(workingDirectory)

            // Json-based serialization.
            var jsonFile: File? = null
            if (!jsonFilSet) {
                jsonFile = setFileExtension(serializationPath, DEFAULT_JSON_FILE_EXTENSION)
                if (jsonFile == null) {
                    println("INTERNAL ERROR: UNKNOWN JSON SERIALIZATION FILE!")
                    continue
                }
                jsonFilSet = true
            }

            // Bytes-based serialization.
            val byteFile = setFileExtension(serializationPath, DEFAULT_BINARY_FILE_EXTENSION)
            if (byteFile == null) {
                println("INTERNAL ERROR: UNKNOWN BINARY SERIALIZATION FILE!")
                continue
            }

            // Store files for serialization.
            serializationFiles = SerializationFiles(jsonFile!!, byteFile)
            break
        }
        break
    }

    // Ask for confirmation before serializing.
    println("----------------------------------------------------")
    println("Serializing in: ")
    println("\t\t${serializationFiles.jsonFile}")
    println("\t\t${serializationFiles.binaryFile}")
    println("\tDo you confirm? Type [Any Key] for No, or [Y] for Yes:")
    print("  >  ")
    val command = scanner.nextLine().lowercase()
    if (! (command == "y" || command == "yes")) {
        println("EXITING, try again later.")
        exitProcess(0)
    }

    // Serialize prompts.
    print("Serializing Json prompts...")
    parsedPrompt.serializeJson(serializationFiles.jsonFile)
    print("Serializing Bytes prompts...")
    parsedPrompt.serializeBinary(serializationFiles.binaryFile)

    // Eventually test deserialization.
    println("----------------------------------------------------")
    testDeserialization(serializationFiles)

    println("All Done")
}


/**
 * The main method that runs when this file gets executed.
 * For more info see [validateAndStore].
 */
fun main() = runBlocking{
    // Register shutdown hook to gracefully exit on Ctrl+C (not working on IntelliJ).
    Signal.handle(Signal("INT")) {
        println("\nPrompts validator closed unexpectedly.")
        // Perform any necessary cleanup here
        exitProcess(0)
    }

    // Launch a coroutine to read user input
    val job = launch(Dispatchers.IO) {
        try {
            setLogger()
            validateAndStore()
            exitProcess(0)
        } catch (ex: Exception) {
            System.err.println("EXCEPTION ON PROMPTS VALIDATOR: " + ex.message)
            ex.printStackTrace()
            exitProcess(1)
        }
    }
    job.join()
}


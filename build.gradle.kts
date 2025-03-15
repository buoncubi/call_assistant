import java.util.*

// TODO check for the last versions, uniform KotlinVersion, and review all.

plugins {
    kotlin("jvm") version "2.1.10"
    java
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"
    application // required to make this software runnable
}


group = "org.example"
version = "0.1.0"


// The global entry point of the software.
application {
    mainClass.set("digital.boline.callAssistant.ApplicationRunnerKt")
}



repositories {
    mavenCentral()
}


dependencies {
    testImplementation(kotlin("test"))

    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.apache.logging.log4j:log4j-core:2.24.2")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")  // Bridge between SLF4J and Log4j2

    // Required for asynchronous computation in Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // TODO why it is not as the kotlin version (2.1.10)?

    // For MP3 player (used by text-to-speech)
    implementation("javazoom:jlayer:1.0.1")

    // For JSON-based serialization and deserialization of LLM prompts
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0") // TODO why it is not as the kotlin version (2.1.10)?
    // For Byte-based serialization and deserialization of LLM prompts
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.0") // TODO why it is not as the kotlin version (2.1.10)?

    // Dependencies for AWS (based on java)
    implementation(platform("software.amazon.awssdk:bom:2.30.31"))
    // For clients login and authentication
    implementation("software.amazon.awssdk:auth")
    implementation("software.amazon.awssdk:regions")
    // Fore speech-to-text (i.e., AWS Transcribe streaming service)
    implementation("software.amazon.awssdk:transcribestreaming")
    // Fore text-to-speech (i.e., AWS Polly service)
    implementation("software.amazon.awssdk:polly")
    // For LLM models usage (i.e., AWS Bedrock Converse streaming service)
    implementation("software.amazon.awssdk:bedrockruntime")

    // For Lambda-based logging
    //implementation("com.amazonaws:aws-lambda-java-log4j2:1.6.0")
}


tasks.test {
    useJUnitPlatform()
}


kotlin {
    jvmToolchain(23)
}

/**
 * The base path of all environmental files.
 */
val baseEnvironmentalFilePath = "src/main/resources/"


/**
 * List of environment files to load (in order of precedence - later files override earlier variables).
 * This list is used in both Test and JavaExec (included Run) software execution. Note that the file name is relative
 * to [baseEnvironmentalFilePath].
 */
val environmentFiles = listOf(
    baseEnvironmentalFilePath + "log_config.env",
    baseEnvironmentalFilePath + "aws_config.env"
)

/**
 * The list of all possible environmental variables that can be provided through [environmentFiles] files.
 * It encompasses
 *  - logging variables, which name is defined here, in the `log_config.env` file, and in the `log4j2.xml` file
 *    (both files are in the `src/main/resources`).
 *  - AWS variables, which are required and stored in the `aws_config.env` file (still on `src/main/resources`).
 */
val possibleEnvironmentVariables = listOf(
    // The base package path of this software and its dependence.
    EnvVar("BASE_PKG", EnvVarType.OPTIONAL),
    EnvVar("BASE_PKG_AWS", EnvVarType.OPTIONAL),

    // Base logging level
    EnvVar("LOG_LEVEL_ROOT", EnvVarType.OPTIONAL),

    // AWS Services logging levels
    EnvVar("LOG_LEVEL_AWS", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_AWS_TRANSCRIBE", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_AWS_POLLY",EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_AWS_BEDROCK", EnvVarType.OPTIONAL),
    // Project packages logging levels
    EnvVar("LOG_LEVEL_PKG_LLM", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG_LLM_PROMPT", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG_LLM_MESSAGE", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG_SPEECH2TEXT", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG_TEXT2SPEECH", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG_DIALOGUE", EnvVarType.OPTIONAL),
    EnvVar("LOG_LEVEL_PKG", EnvVarType.OPTIONAL), // This includes the `main` function.

    // Aws general config
    EnvVar("AWS_REGION", EnvVarType.REQUIRED),
    EnvVar("AWS_ACCESS_KEY_ID", EnvVarType.REQUIRED),
    EnvVar("AWS_SECRET_ACCESS_KEY", EnvVarType.REQUIRED),
    EnvVar("AWS_SESSION_TOKEN", EnvVarType.REQUIRED),
    // AWS Transcribe config (speech-to-text)
    EnvVar("AWS_TRANSCRIBE_LANGUAGE", EnvVarType.REQUIRED),
    EnvVar("AWS_TRANSCRIBE_AUDIO_STREAM_CHUNK_SIZE", EnvVarType.REQUIRED),
    // AWS Polly config (text-to-speech)
    EnvVar("AWS_POLLY_VOICE_NAME", EnvVarType.REQUIRED),
    EnvVar("AWS_POLLY_VOICE_TYPE", EnvVarType.REQUIRED),
    // AWS Bedrock (LLM inferences)
    EnvVar("AWS_BEDROCK_MODEL_NAME", EnvVarType.REQUIRED),
    EnvVar("AWS_BEDROCK_MAX_TOKENS", EnvVarType.REQUIRED),
    EnvVar("AWS_BEDROCK_TEMPERATURE", EnvVarType.REQUIRED),
    EnvVar("AWS_BEDROCK_TOP_P", EnvVarType.REQUIRED)
)



/*
 * Configure all Test tasks to use environmental variables from the files specified in `environmentFiles`.
 * It invokes [loadEnvironmentVariables] and [checkVenv] to check that the required environmental variables are
 * provided.

tasks.withType<Test> {
//    val envVars = loadEnvironmentVariables(this)
//    environment(envVars)
}
 */


/**
 * Configure all JavaExec tasks (including 'run') to use environmental variables from the files in `environmentFiles`.
 * It invokes [loadEnvironmentVariables] and [checkVenv] to check that the required environmental variables are
 * provided. Note that, for performances reasons due to cold start, the [checkVenv] is performed only in the development
 * environment. To run in production and avoid checks use `./gradlew run -Pprod`
 */
tasks.withType<JavaExec> {
    val envVars = loadEnvironmentVariables(this)
    if(project.hasProperty("prod")) { // It should be either 'dev' or 'prod'.
        this.logger.info("Running in production environment.")
        this.logger.warn("Environmental variables not checked!")
    }else {
        this.logger.info("Running in development environment. Use `./gradlew run -Pprod` to run in production mode.")
        checkVenv(this, envVars)
    }
    environment(envVars)
}



/**
 * An enumerate to discriminate between `REQUIRED` and `OPTIONAL` environmental variables.
 * If not provided through [environmentFiles], the former will through an exception, the latter will log a warning.
 */
enum class EnvVarType {REQUIRED, OPTIONAL}


/**
 * Data class to represent an environmental variable and if they are required or optional.
 */
data class EnvVar(val name: String, val type: EnvVarType)


/**
 * Loads environment variables from all the file listed in [environmentFiles].
 * @param task The task being configured (for logging purposes).
 * @return Map<String, String> of environment variables.
 */
fun loadEnvironmentVariables(task: Task): Map<String, String> {

    // Start with system environment variables
    val environmentMap = System.getenv().toMutableMap()


    // Process each env file
    environmentFiles.forEach { envFileName ->
        val envFile = file(envFileName)

        if (!envFile.exists()) {
            task.logger.warn("Environment file '$envFileName' not found for task '${task.name}'")
            return@forEach
        }

        try {
            val envProps = Properties()
            envFile.inputStream().use { inputStream ->
                envProps.load(inputStream)
                task.logger.info("Successfully loaded environment from: $envFileName")

                // Add or override variables from this file
                envProps.forEach { (key, value) ->
                    environmentMap[key.toString()] = value.toString()
                }
            }
        } catch (e: Exception) {
            task.logger.error("Failed to load environment file '$envFileName': ${e.message}")
            // Optionally, you might want to throw the exception if this is critical
            throw GradleException("Failed to load environment file: $envFileName", e)
        }
    }

    return environmentMap
}


/**
 * It goes through all the [possibleEnvironmentVariables] and checks if they are provided in the `envVars` map
 * (which is given by [loadEnvironmentVariables]). It throws an exception if a `REQUIRED` environmental variable is not
 * provided, or it logs a warning message if `OPTIONAL` environmental variables are not provided.
 * @param task The task being configured (for logging purposes).
 * @param loadedEnvVar Map<String, String> of environment variables.
 */
fun checkVenv(task: Task, loadedEnvVar: Map<String, String>) {
    var allSet = true
    possibleEnvironmentVariables.forEach { envVar ->
        val varName = envVar.name
        when (envVar.type) {
            EnvVarType.OPTIONAL -> {
                if (!loadedEnvVar.containsKey(varName)) {
                    task.logger.warn("Optional environment variable '${varName}' is not set.")
                    allSet = false
                }
            }
            EnvVarType.REQUIRED -> {
                if (!loadedEnvVar.containsKey(varName)) {
                    throw GradleException("Required environment variable '${varName}' is not set.")
                }
            }
        }
    }
    if (allSet) task.logger.info("All required environmental variables are set.")
}

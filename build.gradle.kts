// TODO check for the last versions and uniform KotlinVersion

plugins {
    kotlin("jvm") version "2.1.10"
    java
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10"

}

group = "org.example"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    //implementation("org.slf4j:slf4j-simple:2.0.17") // TODO check also for other logging mechanisms
    implementation("ch.qos.logback:logback-classic:1.4.12") // for changing log level programmatically

    // Required for asynchronous computation in Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") // TODO why it is not as the kotlin version (2.1.10)?

    // For MP3 player (used by text-to-speech)
    implementation("javazoom:jlayer:1.0.1")

    // For JSON-based serialization and deserialization of LLM prompts
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0") // TODO why it is not as the kotlin version (2.1.10)?
    // For Byte-based serialization and deserialization of LLM prompts
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.8.0") // TODO why it is not as the kotlin version (2.1.10)?

    // For Prompts variable placeholder fill based on function results
    //implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.10") // TODO The same version as the kotlin version ?!

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
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(23)
}

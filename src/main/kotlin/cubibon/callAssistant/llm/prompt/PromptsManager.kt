package cubibon.callAssistant.llm.prompt

import cubibon.callAssistant.Loggable
import cubibon.callAssistant.LoggableInterface
import cubibon.callAssistant.Utils
import cubibon.callAssistant.llm.message.MessagesManager // Only used in the documentation.
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.jvm.internal.CallableReference



/**
 * The most general interface to use prompts for LLM models.
 *
 * @property prompts The prompts map to guide an LLM model. Keys are prompts identifier (i.e., titles), while values are
 * prompts contents (i.e., sections).
 * @param K The types of keys to map different prompt sections.
 * @param T The types of values to represent prompts sections contents.
 *
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
interface Prompts<K, T>: LoggableInterface {
    val prompts: Map<K,T>
}



/**
 * The implementation of a Simple prompt which includes maps to represent `metadata≠ and `variables`
 *
 * This class is extended by [ParsedPrompts] and [PromptsManager] (which in turn is extended by [PromptsManagerImpl]).
 *
 * @param K See [Prompts]. In this implementation `K` is also use to identify the keys of [metadata] and [variables].
 * @param T See [Prompts].In this implementation `K` is also use to identify the keys of [metadata] and [variables].
 * @param O The types of [variables] to represent their values and occurrences.
 *
 * @property prompts See [Prompts.prompts]. In this implementation, prompts might have placeholders related to
 * [variables].
 * @property metadata  Some `key:value`-based metadata associated with these prompts.
 * @property variables The variables occurrences that should be used to fill placeholders in [prompts].
 *
 * @see [Prompts]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class SimplePrompt<K, T, O> : Loggable(), Prompts<K, T> {

    abstract val metadata: Map<K, T>

    abstract val variables: Map<K, O>

    /**
     * A helper function used to print [PromptsManager] and [ParsedPrompts] in a readable format.
     *
     * @param map The key-value map to format into a string.
     * @param separator The separator string to use between key-value pairs. Default value is `", "`.
     * @param prefix The prefix string to prepend to the formatted string. Default value is `""`.
     * @return The `map` formatted as a string with readability purposes.
     */
    protected fun formatMap2String(map: Map<*,*>, separator: String = ", ", prefix: String = ""): String {
        if (map.isEmpty()) return "{}"
        return "{$prefix" + map.map {(k, v) ->
            "'${k}': '${Utils.escapeCharacters(v)}'"
        }.joinToString(separator) + "}"
    }
}



/**
 * An abstract extension of [SimplePrompt] that specifies its template types as:
 *  - `K: String` The types of key to map different prompt sections.
 *  - `T: String` The types of value to represent prompts sections contents.
 *  - `O: [Occurrences]` The types of value to represent variables contents.
 *
 * This class is only in charge to give a common implementation for [ParsedPrompts] and [PromptsManager]
 * (which is made concurred by [PromptsManagerImpl]).
 *
 * @property prompts See [SimplePrompt.prompts].
 * @property metadata See [SimplePrompt.metadata].
 * @property variables See [SimplePrompt.variables]. In this implementation variables are identified by their
 * [Occurrences]
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class TypedPrompts : SimplePrompt<String, String, Occurrences>() {

    companion object {
        // Used in `toString` to separate the data structures and some of its items.
        private val SEPARATOR = "$NEW_LINE\t\t"
    }


    /**
     * Returns a string representation of the MutableParsedResults object, including all its fields.
     *
     * The string representation is structured to display the contents of each field in a readable format.
     *
     * @return A string representation of the MutableParsedResults object on multiple lines.
     */
    override fun toString(): String {
        return "${this.javaClass.simpleName}($NEW_LINE" +
                "\tmetadata = ${formatMap2String(metadata)},$NEW_LINE" +
                "\tvariables = ${formatMap2String(variables, ",$SEPARATOR", SEPARATOR)},$NEW_LINE" +
                "\tprompts = ${formatMap2String(prompts,",$SEPARATOR", SEPARATOR)}$NEW_LINE" +
                ")"
    }


    /**
     * An object is equal to `this` if both have the same [metadata], [variables], and [prompts] maps.
     *
     * @param other Another object to check equality with `this`.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PromptsManagerImpl

        if (metadata != other.metadata) return false
        if (variables != other.variables) return false
        if (prompts != other.prompts) return false

        return true
    }


    /**
     * Has code of `this` is based on [metadata], [variables] and [prompts] maps.
     */
    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + variables.hashCode()
        result = 31 * result + prompts.hashCode()
        return result
    }
}



/**
 * A general implementation of a class that can be used to exploit prompt representation at runtime in an efficient
 * manner.
 *
 * This class defines the [applyVariables], which is charge to create the `prompts` based on the `rawPrompts` and the
 * `variables` maps. This function has the effect to replace variables' placeholders in the prompts whit values computed
 * by functions defined in [VariablesFunction].
 *
 * Instance of this class are given by the [PromptsDeserializer] and [PromptsParser].
 *
 * @property prompts See [TypedPrompts.prompts].
 * @property metadata See [TypedPrompts.metadata].
 * @property variables See [TypedPrompts.variables].
 * @property rawPrompts The raw [prompts] where [variables] placeholder will never be filled with relative values.
 * @property messageSummary The string representing a summary of previous message exchanged between the user and the
 * LLM-based assistant. It should be used to enhance the prompts for giving to the LLM model the context of previous
 * conversation, while minimizing the number of tokens. For more information see [MessagesManager].
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManagerImpl]
 * @see [PromptsDeserializer]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 * @see [MessagesManager]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
abstract class PromptsManager : TypedPrompts(){

    abstract val rawPrompts: Map<String, String>

    var messageSummary: String = ""

    /**
     * Format part of the [prompts] to be given to the LLM model.
     *
     * @param titles The titles of the prompt's sections (i.e., the keys in [prompts]) to be formatted.
     * @param includeTitle If `true`, each prompt's section will be preceded with the specified `title` formatted as
     * `**title:**\n`. If `false`, titles will not appear in the returned string. By default, it is `true`.
     * @param includeSummary If `true`, the [messageSummary] will be appended after the selected prompt's sections. If
     * [includeTitle] is also `true`, then the summary will be preceded by a title as specified on the prompt's
     * `metadata` with the field key equal to [MESSAGE_SUMMARY_TITLE_KEY]. If such a title is not specified, then no
     * title will appear for the message summary section. If [messageSummary] is empty, the related section will not
     * appear.
     *
     * @return The formated string with selected prompts and (eventually) the summary of previous messages.
     */
    fun formatPrompts(titles: List<String>, includeTitle:Boolean = true, includeSummary: Boolean = true): String {

        val lastIndex = titles.lastIndex
        val summary = if (includeSummary) messageSummary else ""
        val hasMessageSummary = summary.isNotEmpty()

        val messageSummaryTitle = if (includeTitle)
            metadata.getOrDefault(MESSAGE_SUMMARY_TITLE_KEY, "")
        else ""

        return StringBuilder().apply {
            titles.forEachIndexed { index, title ->
                val content = prompts.getOrDefault(title,"")
                if (content.isEmpty()) {
                    logWarn("No contents for prompt title '{}'", title)
                } else {
                    if (includeTitle)
                        append("**$title:**$NEW_LINE")
                    append(content)
                    if (index < lastIndex || hasMessageSummary)
                        append("$NEW_LINE$NEW_LINE")
                }
            }
            if (hasMessageSummary) {
                if (includeTitle && messageSummaryTitle.isNotEmpty())
                    append("**$messageSummaryTitle:**$NEW_LINE")
                append(messageSummary)
            }
        }.toString()
    }


    /**
     * Set the [prompts] contents based on [rawPrompts], where variables placeholders has been replaced with relative
     * function value given by [VariablesFunction].
     */
    abstract fun applyVariables()


    companion object {
        /**
         * The key of a field in the prompt's `metadata` that is used by [formatPrompts] to give a title to the
         * section involving [messageSummary].
         */
        const val MESSAGE_SUMMARY_TITLE_KEY = "*MessageSummaryTitle*"
    }
}



/**
 * The class that implements the usage of parsed prompts at runtime.
 *
 * This class is instantiated by [PromptsDeserializer], which exploits [ParsedPrompts.getPromptManager].
 *
 * This class contains immutable maps of parsed data based on [ParsedPrompts].
 *
 * It also allows replacing the variables placeholders in the prompts with the results of functions defined in
 * [VariablesFunction]. Such a mechanism is based on [applyVariables], which is called at construction time, but it can
 * be recalled later if the prompts should be updated with new variable values.
 *
 * @property prompts See [PromptsManager.prompts].
 * @property metadata See [PromptsManager.metadata].
 * @property variables See [PromptsManager.variables].
 * @property rawPrompts See [PromptsManager.rawPrompts].
 *
 * @constructor Initialise all the properties of this class based on parsed information given by [ParsedPrompts]. The
 * main effect of this constructor is to make mutable data map from [ParsedPrompts] into immutable data maps. The
 * constructor of this class is invoked by [ParsedPrompts.getPromptManager], which is exploited by
 * [PromptsDeserializer]. Indeed, the recommended way to get instances of this class is through the
 * [PromptsDeserializer.fromBytes].
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsDeserializer]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
class PromptsManagerImpl private constructor(
    override val metadata: Map<String, String>,
    override val variables: Map<String, Occurrences>,
    override val rawPrompts: Map<String, String>
) : PromptsManager() {

    override lateinit var prompts: Map<String, String>
        private set


    constructor(parsedPrompts: ParsedPrompts): this(
        // Make all the maps not mutable.
        parsedPrompts.metadata,
        parsedPrompts.variables,
        parsedPrompts.prompts
    )


    init {
        applyVariables()
    }


    // The documentation of this function is on `PromptsManager.applyVariables`
    override fun applyVariables() {
        // Check if there are data to work with.
        if (rawPrompts.isEmpty()) {
            logWarn("No prompts to work with.")
            return
        }
        logDebug("Applying variables to prompts...")

        // Initialize a mutable version of the output of this method to work with.
        val mutablePrompts = mutableMapOf<String, String>()//rawPrompts.size)

        // Initialize a map of variables value that will act as a cache.
        val functionsResultCache = mutableMapOf<String, String>()

        // Iterate for all prompts
        for ((title, section) in rawPrompts.entries) {

            // Initialise the section that will be without variables placeholders.
            var replacedSection = section

            // If the prompt contains variables to consider, iterate over all of their occurrences.
            variables[title]?.asReversed()?.forEach { occurrence ->

                // If the function result is not cached, compute it and cache it.
                val functionResult = functionsResultCache.getOrPut(occurrence.functionName) {
                    VariablesFunction.invoke(occurrence.functionName) ?: ""
                }

                // Replace the variable placeholder in this prompt section.
                replacedSection = replacedSection.replaceRange(
                    occurrence.indexStart..< occurrence.indexEnd,
                    functionResult)
            }

            // Add the prompt to the mutable prompts.
            mutablePrompts[title] = replacedSection
        }

        // Store a not mutable version of the prompts with `variable` placeholder replaced.
        prompts = mutablePrompts.toMap()

        logInfo("Applied variables to prompts.")
    }
}



/**
 * The class where functions related to prompts variables are defined.
 *
 * When [PromptsManager.applyVariables] is called, the variables placeholders in the [PromptsManager.rawPrompts] (e.g.,
 * `{{today}}`) will be replaced with the value returned by a function (e.g., `getDate()`), which is identified by the
 * value associated with the `today` key in the `Var` special section (e.g., `- today = getDate`).
 *
 * The functions defined in this class must:
 *  - be public,
 *  - have the same name as defined in the 'Var' special section,
 *  - do not take any input parameter, and
 *  - return a string.
 *
 * For adding a new function you have to: (1) define the new function inside this class, and (2)  add the function to
 * the [functionMap] map through [addFunction].
 *
 * Currently, this implementation encompass only two functions, which are: [getDate] and [getTime].
 *
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object VariablesFunction: Loggable() {

    /**
     * A map that associates function names with their corresponding function references. This map is used to [invoke]
     * the function by its name without using reflection, which would be time-consuming.
     */
    private val functionMap = mapOf(
        addFunction(VariablesFunction::getTime),
        addFunction(VariablesFunction::getDate)
    )

    init {
        // Run this after that functionMap has been initialized above (Remember that `init` is positional).
        logDebug("Load variables function: {}", this)
    }


    /**
     * Add a function to the `functionMap` map during construction.
     *
     * @param function The reference to function to be added, e.g., `VariablesFunction::myFunction` .
     */
    private fun addFunction(function: () -> String): Pair<String, () -> String> {
        val functionName = (function as CallableReference).name
        return Pair(functionName, function)
    }


    /**
     * Invoke a function by its name. If an error occurs, it logs an error and returns `null`
     *
     * @param functionName The name of the function to be invoked.
     * @return The result of the function invocation, or `null` if an error occurred.
     */
    fun invoke(functionName: String): String? {
        try {
            if (!functionMap.containsKey(functionName)) {
                logError("Error function '{}' not found.", functionName)
                return null
            }
            return functionMap[functionName]?.invoke()
        } catch (e: Exception) {
            logError("Error while invoking function '{}'", functionName, e)
            return null
        }

    }


    /**
     * Check if a function with the given name exists, and if it can be used through prompts `variables`.
     *
     * @param functionName The name of the function to check for existence.
     * @return `true` if the function exists, `false` otherwise.
     */
    fun containsFunction(functionName: String): Boolean {
        return functionMap.containsKey(functionName)
    }


    /**
     * Get the current date in the format "dd/MM/yyyy". This is a function that can be invoked through prompts
     * variables.
     *
     * @return The current date as a string.
     */
    fun getDate(): String {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        return LocalDateTime.now().format(formatter)
    }


    /**
     * Returns the time in the format "HH:mm:ss". This is a function that can be invoked through prompts variables.
     *
     * The returned time is not updated at each call to this function, but only at system bootstrap, or when the
     * [updateTime] method is called.
     *
     * @return The current time as a string.
     */
    fun getTime(): String {
        return formattedTime
    }


    /**
     * Updates the time returned by [getTime] method, which cna be related to a prompt variable. This method is
     * automatically called when the software starts.
     */
    fun updateTime() {
        formattedTime = newTime()
    }


    /**
     * @return Returns the time in the "HH:mm:ss" format.
     */
    private fun newTime(): String {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }


    /**
     * The time in the format "HH:mm:ss" returned by the function `getTime`, which cna be related to a prompt variable.
     * The time is updated at system bootstrap and during each call of the `updateTime` function.
     *
     * @return The current time as a string.
     */
    private var formattedTime: String = newTime()


    /**
     * Print the name of the functions that can be used through prompt `variables`.
     *
     * @return A string representation of the functions defined in this class.
     */
    override fun toString(): String{
        return "${functionMap.keys}"
    }
}
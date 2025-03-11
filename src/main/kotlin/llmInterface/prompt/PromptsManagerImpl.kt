package llmInterface.prompt

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.collections.HashMap
import kotlin.jvm.internal.CallableReference


// TODO adjust logs and print in the whole file

/**
 * The most general interface to use prompts for LLM models.
 *
 *
 * @property prompts The prompts map to guide an LLM model. Keys are prompts
 * identifier (i.e., titles), while values are prompts contents (i.e., sections).
 *
 *
 * @param K The types of keys to map different prompt sections.
 * @param T The types of values to represent prompts sections contents.
 *
 *
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 *
 *
 * @author Luca Buoncompagni © 2025
 */
interface Prompts<K, T> {
    val prompts: Map<K,T>
}



/**
 * The implementation of a Simple prompt which includes maps to represent
 * `metadata≠ and `variables`
 *
 * This class is extended by [ParsedPrompts] and [PromptsManager]
 * (which in turn is extended by [PromptsManagerImpl]).
 *
 *
 * @property prompts See [Prompts.prompts]. In this implementation,
 * prompts might have placeholders related to [variables].
 *
 * @property metadata  Some `key:value`-based metadata associated with
 * these prompts.
 *
 * @property variables The variables occurrences that should be used
 * to fill placeholders in [prompts].
 *
 *
 * @param K See [Prompts]. In this implementation `K` is also use to identify
 * the keys of [metadata] and [variables].
 *
 * @param T See [Prompts].In this implementation `K` is also use to identify
 * the keys of [metadata] and [variables].
 *
 * @param O The types of [variables] to represent their values and occurrences.
 *
 *
 * @see [Prompts]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 *
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class SimplePrompt<K, T, O> : Prompts<K,T> {

    abstract val metadata: Map<K, T>

    abstract val variables: Map<K, O>

    /**
     * A helper function used to print [PromptsManager] and [ParsedPrompts] in
     * a readable format.
     *
     * @param map       The key-value map to format into a string.
     * @param separator The separator string to use between key-value pairs.
     *                  Default value is `", "`.
     * @param prefix    The prefix string to prepend to the formatted string.
     *                  Default value is `""`.
     *
     * @return The `map` formatted as a string with readability purposes.
     */
    protected fun formatMap2String(map: Map<*,*>, separator: String = ", ", prefix: String = ""): String {
        /**
         * Escapes the special characters in the given data.
         * It escapes only the following symbols: `\n`, `\t`, `\r`, `\b`  and `\$`.
         *
         * @param data The data to escape.
         * @return The escaped string.
         */
        fun escapeCharacters(data: Any?): String {
            val escapeMap = mapOf(
                '\n' to "\\n",
                '\t' to "\\t",
                '\b' to "\\b",
                '\r' to "\\r",
                //'\'' to "\\'",
                //'\"' to "\\\"",
                //'\\' to "\\\\",
                '\$' to "\\$"
            )
            val result = StringBuilder()
            for (char in data.toString()) {
                result.append(escapeMap[char] ?: char)
            }
            return result.toString()
        }

        if (map.isEmpty()) return "{}"
        return "{$prefix" + map.map {(k, v) ->
            "'${escapeCharacters(k)}': '${escapeCharacters(v)}'"
        }.joinToString(separator) + "}"
    }
}



/**
 * An abstract extension of [SimplePrompt] that specifies its template types as:
 *  - `K: String` The types of key to map different prompt sections.
 *  - `T: String` The types of value to represent prompts sections contents.
 *  - `O: [Occurrences]` The types of value to represent variables contents.
 *
 * This class is only in charge to give a common implementation for [ParsedPrompts]
 * and [PromptsManager] (which is made concurred by [PromptsManagerImpl]).
 *
 *
 * @property prompts See [SimplePrompt.prompts].
 * @property metadata See [SimplePrompt.metadata].
 * @property variables See [SimplePrompt.variables]. In this implementation
 * variables are identified by their [Occurrences]
 *
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 *
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class TypedPrompts : SimplePrompt<String, String, Occurrences>() {


    /**
     * Returns a string representation of the MutableParsedResults object, including all its fields.
     *
     * The string representation is structured to display the contents of each field in a readable format.
     *
     * @return A string representation of the MutableParsedResults object on multiple lines.
     */
    override fun toString(): String {
        val sep = "\n\t\t"
        return "${this.javaClass.simpleName}(\n" +
                "\tmetadata = ${formatMap2String(metadata)},\n" +
                "\tvariables = ${formatMap2String(variables, ",$sep", sep)},\n" +
                "\tprompts = ${formatMap2String(prompts,",$sep", sep)}\n" +
                ")"
    }


    /**
     * An object is equal to `this` if both have the same
     * [metadata], [variables], and [prompts] maps.
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
     * Has code of `this` is based on [metadata],
     * [variables] and [prompts] maps.
     */
    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + variables.hashCode()
        result = 31 * result + prompts.hashCode()
        return result
    }
}



/**
 * A general implementation of a class that can be used to exploit prompt representation
 * at runtime in an efficient manner.
 *
 * This class defines the [applyVariables], which is charge to create the `prompts`
 * based on the `rawPrompts` and the `variables` maps. This function has the effect to
 * replace variables' placeholders in the prompts whit values computed by functions
 * defined in [VariablesFunction].
 *
 * Instance of this class are given by the [PromptsDeserializer] and [PromptsParser].
 *
 * @property prompts See [TypedPrompts.prompts].
 * @property metadata See [TypedPrompts.metadata].
 * @property variables See [TypedPrompts.variables].
 * @property rawPrompts The raw [prompts] where [variables] placeholder will
 * never be filled with relative values.
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManagerImpl]
 * @see [PromptsDeserializer]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 *
 * @author Luca Buoncompagni © 2025
 */
abstract class PromptsManager : TypedPrompts(){

    abstract val rawPrompts: Map<String, String>


    /**
     * Set the [prompts] contents based on [rawPrompts], where variables placeholders has
     * been replaced with relative function value given by [VariablesFunction].
     */
    abstract fun applyVariables()

}



/**
 * The class that implements the usage of parsed prompts at runtime.
 *
 * This class is instantiated by [PromptsDeserializer], which exploits [ParsedPrompts.getPromptManager].
 *
 * This class contains immutable maps of parsed data based on [ParsedPrompts].
 *
 * It also allows replacing the variables placeholders in the prompts with the results of functions
 * defined in [VariablesFunction]. Such a mechanism is based on [applyVariables], which is called
 * at construction time, but it can be recalled later if the prompts should be updated with new
 * variable values.
 *
 *
 * @property prompts See [PromptsManager.prompts].
 * @property metadata See [PromptsManager.metadata].
 * @property variables See [PromptsManager.variables].
 * @property rawPrompts See [PromptsManager.rawPrompts].
 *
 *
 * @see [Prompts]
 * @see [SimplePrompt]
 * @see [PromptsManager]
 * @see [PromptsDeserializer]
 * @see [ParsedPrompts]
 * @see [Occurrences]
 *
 * @author Luca Buoncompagni © 2025
 */
class PromptsManagerImpl(
    override val metadata: Map<String, String>,
    override val variables: Map<String, Occurrences>,
    override val rawPrompts: Map<String, String>
) : PromptsManager() {

    override lateinit var prompts: Map<String, String>
        private set


    /**
     * Initialise all the properties of this class based on parsed information.
     * The main effect of this constructor is to make mutable data map from
     * [ParsedPrompts] into immutable data map.
     *
     * This constructor is used by [ParsedPrompts.getPromptManager], which is
     * exploited by [PromptsDeserializer].
     */
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
        if (variables.isEmpty()) {
            println("WARNING! No variables to apply to the prompts!")
            return
        }
        if (rawPrompts.isEmpty()) {
            println("WARNING No prompts found where variables can be applied!")
            return
        }
        println("INFO Evaluating and applying variables to prompts...")

        // Initialize a mutable version of the output of this method to work with.
        val mutablePrompts = HashMap<String, String>(rawPrompts.size)

        // Initialize a map of variables value that will act as a cache.
        val functionsResultCache = HashMap<String, String>()

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

        println("Applied variables functions") // this as info. On top of the function as debug ("Applying variables functions...")
    }




}



/**
 * The class where functions related to prompts variables are defined.
 *
 * When [PromptsManager.applyVariables] is called, the variables placeholders in the
 * [PromptsManager.rawPrompts] (e.g., `{{today}}`) will be replaced with the value
 * returned by a function (e.g., `getDate()`), which is identified by the value associated with the `today`
 * key in the `Var` special section (e.g., `- today = getDate`).
 *
 * The functions defined in this class must:
 *  - be public,
 *  - have the same name as defined in the 'Var' special section,
 *  - do not take any input parameter, and
 *  - return a string.
 *
 * For adding a new function you have to: (1) define the new function inside this class, and (2)  add the
 * function to the [functionMap] map through [addFunction].
 *
 * Currently, this implementation encompass only two functions, which are: [getDate] and [getTime].
 *
 * @see [PromptsManager]
 * @see [PromptsManagerImpl]
 *
 * @author Luca Buoncompagni © 2025
 */
object VariablesFunction {

    /**
     * A map that associates function names with their corresponding function references.
     * This map is used to [invoke] the function by its name without using reflection, which
     * would be time-consuming.
     */
    private val functionMap = mapOf(
        addFunction(::getTime),
        addFunction(::getDate)
    )

    init {
        // Run this after that functionMap has been initialized.
        println("Load variables function: $this")
    }


    /**
     * Add a function to the `functionMap` map during construction.
     *
     * @param function The reference to function to be added, e.g., `::myFunction` .
     */
    private fun addFunction(function: () -> String): Pair<String, () -> String> {
        val functionName = (function as CallableReference).name
        return Pair(functionName, function)
    }


    /**
     * Invoke a function by its name. If an error occurs, it logs an error and returns `null`
     *
     * @param functionName The name of the function to be invoked.
     *
     * @return The result of the function invocation, or `null` if an error occurred.
     */
    fun invoke(functionName: String): String? {
        try {
            if (!functionMap.containsKey(functionName)) {
                println("Error while invoking function $functionName: function not found !!!")
                return null
            }
            return functionMap[functionName]?.invoke()
        } catch (e: Exception) {
            println("Error while invoking function $functionName: ${e.message}")
            e.printStackTrace()
            return null
        }

    }


    /**
     * Check if a function with the given name exists, and if it can
     * be used through prompts `variables`.
     *
     * @param functionName The name of the function to check for existence.
     *
     * @return `true` if the function exists, `false` otherwise.
     */
    fun containsFunction(functionName: String): Boolean {
        return functionMap.containsKey(functionName)
    }


    /**
     * Get the current date in the format "dd/MM/yyyy".
     * This is a function that can be invoked through prompts variables.
     *
     * @return The current date as a string.
     */
    fun getDate(): String {
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        return LocalDateTime.now().format(formatter)
    }


    /**
     * Returns the time in the format "HH:mm:ss".
     * This is a function that can be invoked through prompts variables.
     *
     * The returned time is not updated at each call to this function,
     * but only at system bootstrap, or when the [updateTime] method is called.
     *
     * @return The current time as a string.
     */
    fun getTime(): String {
        return formattedTime
    }


    /**
     * Updates the time returned by [getTime] method, which cna be related to a
     * prompt variable. This method is automatically called when the software starts.
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
     * The time in the format "HH:mm:ss" returned by the function `getTime`, which cna be related to a
     * prompt variable. The time is updated at system bootstrap and during each call of the
     * `updateTime` function.
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
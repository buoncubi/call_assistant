package llmInterface.prompt

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.regex.Matcher
import java.util.regex.Pattern

// TODO allow recursive field definition, e.g., `my field key = {{another field key}}`.


/**
 * Represents a map of key-value pairs where both the key and value are String types.
 * This type alias is used to define metadata, constants or variables fields.
 *
 * @see PromptsParser.parse
 * @see PromptsParser.normalizeWhitespace
 * @see PromptsParser.extractSpecialSection
 *
 * @author Luca Buoncompagni © 2025
 */
typealias FieldsMap = HashMap<String, String>


/**
 * Represents a data structure that contains string matched in a text and the relative
 * starting and ending indexes where such a match occurred in the prompts special sections.
 *
 * It is used in [PromptsParser.parseSpecialSections] with `T: FieldsMap` and
 * in [PromptsParser.extractSpecialSection] with `T: List<String>`.
 *
 * @param P The type of the matched elements.
 *
 * @property fields Elements that are matched.
 * @property ranges A list of index ranges corresponding to the indexes in the text where
 * the `elements` are matched.
 *
 * @see PromptsParser.parse
 * @see PromptsParser.parseSpecialSections
 * @see PromptsParser.extractSpecialSection
 *
 * @author Luca Buoncompagni © 2025
 */
data class FieldsMatcher<P>(val fields: P, val ranges: List<IntRange>)


/**
 * Represents special sections identified by a string ID.
 * Possible special sections are
 *  - `METADATA`: `id = "Meta"`,
 *  - `CONSTANTS`: `id = "Const"`, and
 *  - `VARIABLES`: `id = "Var"`.
 *
 * @property id A string identifier for the special section.
 *
 * @see PromptsParser.parseSpecialSections
 *
 * @author Luca Buoncompagni © 2025
 */
enum class SpecialSectionTitle(private val id: String) {
    METADATA("Meta"), CONSTANTS("Const"), VARIABLES("Var");

    /**
     * Retrieves the identifier string associated with a specific special section.
     *
     * @return The ID string of the special section.
     */
    fun getName(): String = this.id
}



/**
 * Parse a specific syntax to define prompts to guide LLM model; such a syntax is documented in a
 * [README.md file](README.md) placed in the same directory of this class.
 *
 * This class provides the [parse] method, which returns an instance of [ParsedPrompts]. The latter
 * can be serialized to file and deserialized through [PromptsDeserializer]. It is recommender to
 * use this class only offline since it might be time-consuming. At runtime, the prompt data can be
 * deserialized to get an implementation of the [PromptsManager], which can be efficiently used.
 *
 * @see ParsedPrompts
 * @see PromptsManager
 * @see PromptsDeserializer
 *
 * @author Luca Buoncompagni © 2025
 */
object PromptsParser{

    // TODO adjust logs
    private val logger: Logger = LoggerFactory.getLogger(PromptsParser::class.java)


    /**
     * The constant character of a new line agnostic to the operative
     * system (i.e., '\n' or '\r\n')
     */
    private val NEW_LINE = System.lineSeparator()  // TODO be sure to replace all \n (also in println?)


    /**
     * A constant regex pattern used to match and capture the `{{...}}` notation.
     * It is used to identify variable placeholders in the prompts, and it is
     * used in [storeVariablesOccurrences].
     */
    private const val REGREX_VAR = "\\{\\{(.*?)}}"


    /**
     * A constant regex pattern used to match and capture the `{{...}}` notation.
     * It is used to identify constants placeholders in the prompts, and it is
     * used in [replaceConstantsPlaceholders].
     */
    private const val REGREX_CONST = REGREX_VAR


    /**
     * Regular expression pattern used to match single-line C-style comments.
     * It is used in [removeComments].
     */
    private const val REGEX_LINE_COMMENT = "//.*"


    /**
     * Regular expression pattern used to match C-style block comments, i.e., `/*...*/`.
     * Note that this implementation does not support nested block comments.
     * It is used in [removeComments].
     */
    private const val REGEX_BLOCK_COMMENT = "/\\*(.*?)\\*/"


    /**
     * String used to identify nested block comments and arise an error.
     * It is used in [removeComments].
     */
    private const val INNER_BLOCK_COMMENT = "/*"


    /**
     * Regular expression that matches one or more spaces or tab characters
     * occurring after a non-whitespace character within a line. This pattern is
     * used in [normalizeWhitespace], to remove additional spaces within the middle of a line.
     */
    private const val REGEX_SPACES = "(?<=\\S)[ \\t]+"


    /**
     * A constant string used as a separator between a field name and its value
     * in a special section. It is used in [parseSpecialSections] and [REGEX_FIELDS].
     */
    private const val FIELD_SEPARATOR = "="


    /**
     * A regular expression pattern used to match prompt fields in a special section, which are
     * defined with the syntax `\n - name = value \n`. It is used in [parseSpecialSections]
     *
     * This pattern captures:
     * - Group 1: The field/key name.
     * - Group 2: The value associated with that field/key.
     */
    private val REGEX_FIELDS = "\\s*-?\\s*([^${Regex.escape(NEW_LINE)}:]+)\\s*${FIELD_SEPARATOR}\\s*(.+)"


    /**
     * Returns a regular expression to match a special section, i.e., `\n__* title *__\n`.
     * It is used in [extractSpecialSection].
     *
     * The pattern dynamically the provided `title` and is designed to extract
     * the contents following such a title.
     *
     * @param title The title of the section to match in the regular expression.
     *              It will be quoted to safely handle any special characters.
     */
    private fun regrexSpecialSectionTitle(title: String)
            = "__\\s*\\*\\s*${Pattern.quote(title)}\\s*\\*\\s*__\\s*(.*?)(?=__|$)"


    /**
     * A regular expression pattern designed to identify and capture  prompt sections denoted by the
     * `\n__ title __\n` notation.  Each match captures:
     *  - the section `title`, and
     *  - the section contents until the next section or end of text.
     *
     * It is used in [parsePrompts].
     */
    private val REGEX_SECTION_TITLE = "__\\s*(.*?)\\s*__\\s*${Regex.escape(NEW_LINE)}([\\s\\S]*?)(?=__|$)"


    /**
     * A regular expression designed to find lines where a title is defined. It is used
     * in [checkTitleFormat] to validate if a title definition follows the notation
     * `\n__ ... ___\n`.
     */
    private const val REGREX_SECTION_TITLE_CHECKER = "__.*?__"


    /**
     * A regular expression designed to validate that the function name associated with a `variables`
     * are feasible, i.e., the name should
     *  - not start with a number,
     *  - not contains empty space, and
     *  - not contains any alphanumeric characters.
     *
     * It is used in [checkVariableDefinition].
     */
    private const val REGEX_FEASIBLE_FUNCTION_NAME = "^[A-Za-z][A-Za-z0-9]*$"



    /**
     * Parses a prompt file at the given path and returns the resulting parsed data.
     * For more information see [parseAll]..
     *
     * @param filePath The path to the file to be parsed.
     *
     * @return An instance of [ParsedPrompts] containing the parsed data, or
     * `null` if there was an error in reading data form the file.
     */
    fun parse(filePath: String): ParsedPrompts?{
        val file = File(filePath)
        return parse(file)
    }


    /**
     * Reads the contents of a file and parses the retrieved text.
     * For more information see [parseAll].
     *
     * @param file The file whose content needs to be parsed.
     */
    fun parse(file: File): ParsedPrompts? {
        val rawText = loadFileAsText(file)
        return rawText?.let { parse(it) }
    }


    /**
     * Parse the prompt syntax given as a `StringBuilder`.
     * For more information see [parseAll].
     *
     * @param rawText The string to be parsed.
     */
    fun parse(rawText: StringBuilder): ParsedPrompts {
        return parseAll(rawText) // Defined such to discriminate links in the documentation.
    }

    
    /**
     * Parse the syntax in order to generate the [ParsedPrompts.metadata], [ParsedPrompts.constants]
     * [ParsedPrompts.variablesDefinition], [ParsedPrompts.variables], and [ParsedPrompts.prompts]
     * data based on the input text.
     *
     * This function returns [ParsedPrompts], but is not ment to be used at runtime.
     * Instead, it should be used to serialize data in to a file and then deserialize it
     * with [PromptsDeserializer] for being efficient usage at runtime.
     * 
     * This is the main function of [PromptsParser] and it is called by all
     * implementations of [parse].
     *
     * Indeed, this method is in charge to invoke the following procedures (in order):
     *   1. [removeComments],
     *   2. [normalizeWhitespace],
     *   3. [checkTitleFormat],
     *   4. [parseSpecialSections] for `metadata`, which uses [extractSpecialSection],
     *   5. [parseSpecialSections] for `constants`, which uses [extractSpecialSection],
     *   6. [parseSpecialSections] for `variablesDefinition`, which uses [extractSpecialSection],
     *.  7. [checkVariableDefinition],
     *   8. [removeSpecialSections]
     *   9. [parsePrompts], which uses [replaceConstantsPlaceholders], and
     *  10. [storeVariablesOccurrences].
     *
     * @param rawText The text to parse based on the designed prompts' syntax.
     *
     * @return The [ParsedPrompts] instance containing the parsed data, which can be
     * serialized and deserialized on file for efficient usage at runtime.
     */
    private fun parseAll(rawText: StringBuilder): ParsedPrompts {

        logger.debug("Parsing prompts syntax...")
        logger.debug("{}{}{}--------------------", NEW_LINE, rawText, NEW_LINE)

        // Initialise the class that collects the outcome of this function with a copy of the
        // raw text with testing purposes. Note that this class is also used to pass data
        // parsed so far to the function in charge to keep parsing the data.
        val parsedResults = ParsedPrompts(rawText.toString())

        // Clean the data: remove comments and empty spaces or multiple blank lines,
        // but preserve paragraph division and indentation.
        removeComments(rawText)
        normalizeWhitespace(rawText)

        // Check if there are titles that do not follow the `\n __ ... __ \n` or
        // `\n __* ... *__ \n` syntax.  If yes, it will log an error.
        checkTitleFormat(rawText)

        // Parse the contents of special sections, i.e., `Meta`, `Cost`, and `Var`.  Generate the relative maps,
        // store them in the `parsingResults`, and get indexes of such sections in the text to remove them later.
        val parsedMetadata = parseSpecialSections(rawText, SpecialSectionTitle.METADATA)
        parsedResults.metadata.putAll(parsedMetadata.fields)
        val parsedConstants = parseSpecialSections(rawText, SpecialSectionTitle.CONSTANTS)
        parsedResults.constants.putAll(parsedConstants.fields)
        val parsedVariablesDefinition = parseSpecialSections(rawText, SpecialSectionTitle.VARIABLES)
        parsedResults.variablesDefinition.putAll(parsedVariablesDefinition.fields)

        // Check if variables are related to feasible and known functions implemented in `VariablesFunction`.
        checkVariableDefinition(parsedResults.variablesDefinition)

        // Remove special sections `Meta`, `Const` and `Var` from the text before parsing prompts.
        removeSpecialSections(rawText, parsedMetadata, parsedConstants, parsedVariablesDefinition)

        // Parse the `prompts` sections and generate the related dictionary.
        val prompts = parsePrompts(rawText, parsedResults)
        parsedResults.prompts.putAll(prompts)

        // Store the occurrences of variables in the prompts.
        // ATTENTION: Do not modify the prompt text after this command or occurrences indexes will be inconsistent.
        val parsedVariableOccurrence = storeVariablesOccurrences(parsedResults)
        parsedResults.variables.putAll(parsedVariableOccurrence)

        logger.info("Prompts syntax parsed.")
        return parsedResults
    }


    /**
     * It reads the contents of a text file.
     *
     * @param file The file to open and read.
     *
     * @return The raw text content of the file. If `IOException` occurs it will return `null`.
     */
    private fun loadFileAsText(file: File): StringBuilder? {
        try {
            logger.debug("Loading file '${file.path}'...")
            val fileContent = StringBuilder()
            BufferedReader(FileReader(file)).use { reader ->
                var line: String?
                while ((reader.readLine().also { line = it }) != null) {
                    fileContent.append(line).append(NEW_LINE)
                }
            }
            logger.info("File '${file.path}' loaded successfully.")
            return fileContent
        } catch (ex: IOException) {
            logger.error("Error cannot open file at '${file.path}' !!!!!", ex)
            return null
        }
    }


    /**
     * Removes all substrings from the given text that match the specified regular expression pattern.
     *
     * An optional inspector callback is invoked for each match, providing a `Matcher` object for
     * inspection or additional processing during the removal process. This function is used by
     * [removeComments] to remove specific patterns from the text.
     *
     * @param text A `StringBuilder` containing the text to be processed. The matched
     * substrings will be removed in-place.
     *
     * @param regrexPattern A `Pattern` object representing the regular expression to match
     * substrings that should be removed.
     *
     * @param inspector A lambda function that takes a [Matcher] as its parameter and performs
     * any custom inspection or operations needed for each match. This parameter is optional and
     * defaults to an empty function.
     */
    private fun removeRegrex(text: StringBuilder, regrexPattern: Pattern, inspector: (Matcher)->Unit = {}) {
        // Compile the RegExp pattern based on `regrex`.
        val matcher = regrexPattern.matcher(text)
        // Find all the RegExp in the text.
        while (matcher.find()) {
            // Eventually, invoke the inspector callback.
            inspector(matcher)
            // Remove the matched segment from `text`
            text.delete(matcher.start(), matcher.end())
            // Reset the `matcher` as the StringBuilder has been modified
            matcher.reset()
        }
    }


    /**
     * Removes single-line and block comments from the provided text. Comments are defined
     * using the C-like style, i.e., `\\` and `/* ... */`. This function is used by
     * [parseAll].
     *
     * **ATTENTION:** Inner block comments (e.g., `/*.../*...*/...*/`) are not allowed and
     * will result in an error if they exist in the input `text`.
     *
     * @param text The input text that may contain comments to be removed.
     */
    private fun removeComments(text: StringBuilder) {
        logger.debug("Removing comments...")

        // Define the pattern to find inline comments (i.e., '//')
        val inlineCommentPattern = Pattern.compile(REGEX_LINE_COMMENT)
        // Remove inline comments.
        removeRegrex(text, inlineCommentPattern)
        logger.info("Removed line comments.")

        // Define block comment regex pattern (i.e, '/* ... */').
        // It does not support nested block comments.
        val pattern: Pattern = Pattern.compile(REGEX_BLOCK_COMMENT, Pattern.DOTALL)
        // Remove block comments.
        var error = false
        removeRegrex(text, pattern) { matcher ->
            // Raise an error if at least one inner block comment exists.
            val blockComment: String = matcher.group(1).trim()
            if (blockComment.contains(INNER_BLOCK_COMMENT)) {
                logger.error("Nested block comments are not supported but found in: '$blockComment' !!!!!")
                error = true
                return@removeRegrex
            }
        }

        if (!error) {
            logger.info("Removed block comments.")
        }

        logger.debug("--------------------------\n" +
                "Text after that comments are removed:\n" +
                "{}\n--------------------------", text)
        logger.info("Removed comments.")
    }


    /**
     * Cleans up unnecessary whitespaces and tabs while preserving indentation
     * of not blank lines. It also reduces multiple blank lines into one to
     * preserve paragraph division. This function performs in place string
     * manipulation. This function is used by [parseAll].
     *
     * @param text A StringBuilder containing the text that will be cleaned up.
     */
    private fun normalizeWhitespace(text: StringBuilder) {
        logger.debug("Normalizing whitespaces...")

        // Match 2 or more spaces/tabs in the middle of the line.
        val middleSpaceRegex = Regex(REGEX_SPACES)
        // Pointer for in-place manipulation.
        var index = 0
        // Track if the previous line was blank.
        var previousLineWasBlank = false

        // Iterate over the entire text.
        while (index < text.length) {
            // Index where the current line starts.
            val lineStart = index
            // Index where the current line ends.
            var lineEnd = text.indexOf(NEW_LINE, index)

            // Handle the last line where '\n' is not present.
            if (lineEnd == -1) lineEnd = text.length
            // Extract current line (here the performances can be improved without creating a new string `line`).
            val line = text.substring(lineStart, lineEnd)

            // Check if the current line is blank.
            if (line.trim().isEmpty()) {
                if (previousLineWasBlank) {
                    // Remove this blank line completely.
                    text.delete(lineStart, lineEnd + 1)
                } else {
                    // Ensure the line is entirely blank with no spaces or tabs.
                    text.replace(lineStart, lineEnd, "")
                    // Set the flag to reduce consecutive blank lines.
                    previousLineWasBlank = true
                    // Move pointer past the blank line.
                    index = lineStart + 1
                }
            } else {
                // Capture leading spaces to preserve leading spaces for non-blank lines (i.e., indentation).
                val leadingSpaces = line.takeWhile { it.isWhitespace() }

                // Process the rest of the line (exclude leading spaces)
                val content = line.substring(leadingSpaces.length).trimEnd()
                // Remove multiple consecutive spaces or tabs in the middle of the line.
                val normalizedContent = content.replace(middleSpaceRegex, " ")

                // Recombine leading spaces with the normalized content
                val normalizedLine = leadingSpaces + normalizedContent

                // Replace the current line in the StringBuilder
                text.replace(lineStart, lineEnd, normalizedLine)
                // Reset the flag to reduce consecutive blank lines.
                previousLineWasBlank = false
                // Move pointer to the next line.
                index = lineStart + normalizedLine.length + 1
            }
        }

        // Remove any trailing newlines or blank lines at the end of the text.
        while (text.isNotEmpty() && text.last().isWhitespace()) {
            // Trim the StringBuilder in place.
            text.setLength(text.length - 1)
        }

        logger.debug("--------------------------\n" +
                "Text after that spaces have been normalized:\n" +
                "{}\n--------------------------", text)
        logger.info("Normalized whitespaces.")
    }


    /**
     * Checks if there are error in the title definitions.
     *
     * Processes the input text, line by line, to identify lines containing the syntax
     * `\n__ ... __\n`. If such a syntax is found, the function checks if there are
     * non-whitespace or non-tab characters before or after this syntax within the same line.
     * If such characters are found, an error is logged. This function is used by [parseAll].
     *
     * @param text The input text to check
     */
    private fun checkTitleFormat(text: StringBuilder) {
        logger.debug("Checking title format...")

        /**
         * Checks if the given string contains any character that is not a whitespace or tab.
         *
         * @param s The input string to check.
         * @return `true` if the string contains any non-whitespace and non-tab characters;
         *         `false` otherwise or if it is empty.
         */
        fun containsNonWhitespaceOrTab(s: String): Boolean {
            //if (s == "") true else s.any { (!it.isWhitespace() || it != '\t') }
            if (s.isEmpty()) return false
            return s.any { it != ' ' && it != '\t' }
        }

        // Convert the StringBuilder to a list of lines
        val lines = text.lines()

        // Regular expression to match the '__ XXX __' pattern
        val pattern = Regex(REGREX_SECTION_TITLE_CHECKER)

        // Iterate over each line with its index
        for (line in lines) {
            // Find all occurrences of the pattern in the line
            val matches = pattern.findAll(line)

            // Iterate over each match
            for (match in matches) {

                // Get the start and end indices of the matched substring
                val startIndex = match.range.first
                val endIndex = match.range.last

                // Check characters before the matched substring
                val beforeMatch = line.substring(0, startIndex)
                val afterMatch = if (endIndex + 1 < line.length)
                    line.substring(endIndex + 1)
                else ""

                // Log an error if invalid characters are found before or after the match
                if (containsNonWhitespaceOrTab(beforeMatch) || containsNonWhitespaceOrTab(afterMatch)) {
                    logger.error("Error in '$line', be sure to use the notation `\\n __ ... __ \\n` !!!!!")
                }
            }
        }
        logger.info("Checked title format.")
    }


    /**
     * Parses all fields from the special sections in the `text` for the given `title`.
     * This method is invoked by [parseAll].
     * 
     * The fields are extracted into a `key-value` map, while also returning the
     * ranges of `[start, end]` indexes when they occur in the text. Indexes are 
     * required for removing special sections from the text 
     * ([removeSpecialSections]) and continue the parsing procedure.
     *
     * Special sections are identified by the notation '\n__* ... *__\n', and its
     * fields by the syntax `- key = value\n`. This method searches for section with 
     * the given `title` (i.e., `Meta`, `Const`, or `Var`), and returns their 
     * associated fields. 
     * 
     * The returned `fields` map will be used to create the [ParsedPrompts.metadata],
     * [ParsedPrompts.constants] and [ParsedPrompts.variablesDefinition] maps based
     * on the given `title`. Such a maps are part of the [ParsedPrompts] object
     * returned by [parseAll].
     *
     * @param text The text containing special sections to parse.
     * @param title The title of the special section to parse. Possible values are
     * enumerated in [SpecialSectionTitle]; case-insensitive.
     *
     * @return An [FieldsMatcher] containing:
     *   - `fields: [FieldsMap]`: A map of key-value pairs parsed from the fields of
     *      special sections. For example:
     *              ```
     *              __* Const *__
     *              - field1 = value1
     *              - field2 0 value2
     *              ```
     *      would produce:
     *              ```
     *              {"field1": "value1", "field2": "value2"}
     *              ```
     *   - `ranges: List<IntRange>`: A list of index ranges `[start, end]` representing
     *      where the special sections appear in the text; included the section title.
     *
     * **Note** The `fields` and `ranges` are empty if no special sections with the specified
     * `title` appear in the input `text`.
     */
    private fun parseSpecialSections(text: StringBuilder, title: SpecialSectionTitle): FieldsMatcher<FieldsMap> {
        logger.debug("Parsing special section '{}'...", title)

        // Extract sections and their indices from the input text using the title.
        val sectionData = extractSpecialSection(text, title.getName())
        val fieldsSections = sectionData.fields
        val indices = sectionData.ranges

        // Initialize the output matches.
        val fieldsMap: FieldsMap = HashMap()
        // Compile the RegExp to match keys and values for every field.
        val fieldPattern = Pattern.compile(REGEX_FIELDS)

        // Process each special section with the same name (title).
        for (section in fieldsSections) {
            // Match fields keys and values.
            val matcher = fieldPattern.matcher(section)

            // Extract key-value pairs from the section.
            while (matcher.find()) {

                // Parse data from the match.
                val fieldName = matcher.group(1).trim()
                val fieldValue = matcher.group(2).trim()

                if (fieldName.contains(FIELD_SEPARATOR)) {
                    logger.error("Field '$fieldName', with value '$fieldValue', ill-formed. Only one occurrence" +
                            "of '$FIELD_SEPARATOR' can occur per line !!!!!")
                    continue
                }

                // Check if a pair with the same key already exists.
                if (fieldsMap.containsKey(fieldName)) {
                    // Print a warning and replace the existing value.
                    logger.warn("Field '$fieldName' already exists with value '${fieldsMap[fieldName]}'. Replacing with '$fieldValue'.")
                    // Remove the old value to be overwritten.
                    fieldsMap.remove(fieldName)
                }

                // Add the (fieldName, fieldValue) pair to the fieldsMap.
                fieldsMap[fieldName] = fieldValue
            }
        }

        logger.debug("--------------------------\n" +
                "Parsed Special Section '{}':\n" +
                "{}\n--------------------------", title, fieldsMap)
        logger.info("Parsed special section '$title'.")

        // Return the map of fields and the list of index ranges.
        return FieldsMatcher(fieldsMap, indices)
    }


    /**
     * Extracts all fields in the `text` for a special section, which is identified by its `title`.
     * This method is utilized by [parseSpecialSections].
     *
     * Note that multiple special sections with the same `title` can exist in the `text`.
     * Therefore, the result includes a list of extracted sections.
     *
     * This method matches the specific notation `\n__* ... *__\n` and retrieves the corresponding
     * sections as raw strings.
     *
     * @param text  The input `StringBuilder` containing special sections to extract.
     * @param title The title of the section to extract (e.g., `Meta`, `Const`, or `Var`).
     *
     * @return An [FieldsMatcher] containing:
     * - `fields: List<String>`: A list that represents all the fields within the special
     *    sections as raw strings. For example, for the following syntax:
     *            ```
     *             __* Const *__
     *              - field1 = value1
     *              - field2 = value2
     *             __* Const *__
     *              - field3 = value3
     *              - field4 = value4
     *            ```
     *    The `fields` would be:
     *            ```
     *            listOf(
     *                "- field1: value1\n- field2: value2",
     *                "- field1: value1\n- field3: value3"
     *            )
     *            ```
     * - `ranges: List<IntRange>`: A list of index ranges representing the `[start, end]`
     *   positions of each special section with the given title in the `text`. These positions
     *   include the matched title (e.g., `__* Const *__`) within the range, even if such a 
     *   title is not included in the `fields`. This is required for removing the special 
     *   sections from the text (see [removeSpecialSections]) and continuing the parse procedure.
     *
     * **Note**: The size of the `fields` and `ranges` lists will always be equal. The `fields` 
     * and `ranges` are empty if no special sections with the specified `title` appear in the 
     * input `text`.
     */
    private fun extractSpecialSection(text: StringBuilder, title: String): FieldsMatcher<List<String>> {
        logger.debug("Extracting special section $title...")

        // Match the special sections contents (i.e., search for '__* ... *___\n').
        val pattern = Pattern.compile(regrexSpecialSectionTitle(title),
            Pattern.DOTALL or Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(text)

        // Initialize the structure to store the part of the text related to the special section.
        val fieldSections: MutableList<String> = ArrayList()

        // Initialize the `[start, end]` range of index in the text related to the special section.
        val indices: MutableList<IntRange> = ArrayList()

        // Iterate over all found special sections.
        while (matcher.find()) {
            // Get the matched string
            val fieldSection = matcher.group(1).trim { it <= ' ' }

            if (fieldSection.isEmpty()) {
                logger.warn("No fields found in one special section of type '$title' !!!")
            }

            // Append all the fields of a special section as a raw string
            fieldSections.add(fieldSection)
            // Store start and end positions, which are used to remove the special section during parsing
            indices.add(matcher.start()..matcher.end())
        }

        logger.info("Extracted special section $title.")

        return FieldsMatcher(fieldSections, indices)
    }


    /**
     * Check if variables refer to feasible Kotlin functions defined in the [VariablesFunction] object.
     *
     * This function removes items form the [variablesDefinition] map given as input if they refer
     * to unfeasible or unknown functions. Note that [variablesDefinition] must already be initialized
     * though the [parseSpecialSections] method with an input `title` of `Var`.
     * 
     * Function are feasible if they have a number that d
     *  - not contain empty spaces,
     *  - not start with a number, and
     *  - contain only alphanumeric characters.
     * 
     * This method is used by [parseAll].
     * 
     * @param variablesDefinition The parsed map of [variablesDefinition], which contains variable 
     * names as keys and the related function name as value.
     */
    private fun checkVariableDefinition(variablesDefinition: HashMap<String, String>) {
        logger.debug("Checking variable definition...")

        // Prepare a list of item to remove afterwords.
        val varsToRemove = ArrayList<String>()

        // Define the regrex to check if the function name is feasible within the Kotlin syntax
        val regex = Regex(REGEX_FEASIBLE_FUNCTION_NAME)

        // Iterate for all parsed `variables` definition.
        for ((varName, functionName) in variablesDefinition.entries) {

            // Function name should (1) not contain empty spaces, (2) not start with a number,
            // and (3) contain only alphanumeric characters.
            if( !regex.containsMatchIn(functionName)) {
                logger.error("Invalid function name `$functionName` for variable `{{$varName}}` !!!!!")
                varsToRemove.add(varName)
                continue
            }

            // Variable should refer to a function implemented in `VariablesFunction`.
            if (!VariablesFunction.containsFunction(functionName)) {
                logger.error("Variable '$functionName' is not defined in `VariablesFunction` !!!!!")
                varsToRemove.add(varName)
            }
        }

        // Remove the invalid variables
        for (varName in varsToRemove) {
            variablesDefinition.remove(varName)
        }

        logger.info("Checked variable definition.")
    }


    /**
     * Removes the part of the given `text` based on the specified `ranges`.
     * This operation is made in-place such to modify the input `text`.
     *
     * It is used to remove special sections (i.e., `"Meta"`, `"Const"`, and 
     * `"Var"`) from the text to be parsed. This method is utilized by 
     * [parseAll] and required before to invoke [parsePrompts].
     *
     * Note that The `ranges` of indexes to be removed are obtained from the
     * [extractSpecialSection], which returns instances of [FieldsMatcher]. 
     *
     * @param text   The input string from which specified ranges of
     * indexes will be removed.
     * @param ranges Lists of index ranges (i.e., `[start, end]`) that
     * identifies the substrings to be removed.
     */
    private fun removeSpecialSections(text: StringBuilder, vararg ranges: FieldsMatcher<*>) {
        logger.debug("Removing special sections...")

        // Sort ranges to ensure they are in ascending order of their start index.
        // Note that ranges cannot overlap by design
        val sortedRanges = ranges.flatMap{ it.ranges }.sortedBy { it.first }

        // Loop over the ranges and delete the specified sections.
        for (range in sortedRanges.reversed()) {
            // Remove the matched segment from `text`
            text.delete(range.first, range.last)
        }

        logger.debug("--------------------------\n" +
                "Removed Special Sections:\n" +
                "{}\n--------------------------", text)
        logger.info("Removed special sections.")
    }


    /**
     * Parses `text` to extract prompts sections with a title and a content. The 
     * title will be a key on the [ParsedPrompts.prompts], while the content will
     * be the relative values. This function is called by [parseAll].
     * 
     * This function assumes that the given ´text´ does not contain special sections 
     * (i.e., `"Meta"`, `"Const"`, and `"Var"`) anymore. Thus, it requires that 
     * [removeSpecialSections] has already been executed.
     *
     * This function searches for sections in the format:
     * ```
     * __ Title __
     * Section content with some {{constants}} or {{variables}}.
     * ```
     *
     * This function uses [replaceConstantsPlaceholders] to replace constants
     * placeholders (e.g., `{{constants}}`) with the corresponding value available
     * in the [ParsedPrompts.constants] map given as input. Thus, it requires that
     * [parseSpecialSections] with the `Const` title has already been executed.
     * In addition, in order to log warning in case of error, this function
     * requires that the [parseSpecialSections] with the `Var` title has already
     * been executed, i.e., that the `parsed` object given as input has an
     * [ParsedPrompts.variablesDefinition] map already initialised.
     *
     * If more than one section has the same title, their content will be merged
     * with the same key in the [ParsedPrompts.prompts] map. In particular, the
     * two sections will be appended based on the order in which they occur in the
     * `text` with a blank line between them.
     *
     * Sections with a title but no content will generate a warning, and no related
     * data will be stored in the [ParsedPrompts.prompts] map.
     *
     * @param text The input text containing prompts sections to be parsed.
     * @param parsed The parsed results containing the [ParsedPrompts.constants]
     * and [ParsedPrompts.variablesDefinition] maps.
     *
     * @return A map where keys are section titles and values are their contents
     * with constant placeholders replaced. The [parseAll] method will store
     * such a map in the input `parsed` object as [ParsedPrompts.prompts].
     */
    private fun parsePrompts(text: StringBuilder, parsed: ParsedPrompts): Map<String, String> {
        logger.debug("Parsing prompts section...")

        // Initialize the output object of this function.
        val prompts: MutableMap<String, String> = HashMap()

        // Match titles and sections using regex.
        val pattern = Pattern.compile(REGEX_SECTION_TITLE, Pattern.DOTALL)
        val matcher = pattern.matcher(text)

        // Iterate over matched sections.
        while (matcher.find()) {
            val title = matcher.group(1).trim { it <= ' ' }
            val sectionWithConstants = matcher.group(2).trimEnd { it <= ' ' }
            val section = replaceConstantsPlaceholders(sectionWithConstants, parsed)
            logger.debug("Replacing constants placeholders on '${title}'...")

            // Check if the section content is empty.
            if (section.isEmpty()) {
                logger.warn("Cannot parse a section without contents. " +
                        "The '$title' title will be disregarded !!!")
                continue
            }

            // Check if the section has a title.
            if (title.isEmpty()) {
                logger.warn("Cannot parse a section without a title. " +
                        "This text will be disregarded: '$section' !!!")
                continue
            }

            if (title.contains('*')) {
                logger.warn("Special section '$title' unknown !!!")
                continue
            }

            // Check if a section with the same title exists, and append content.
            if (prompts.containsKey(title)) {
                logger.warn("Section '$title' appears multiple times, appending text within a " +
                        "single section with a blank line !!!")
                prompts[title] += "$NEW_LINE$NEW_LINE$section"
                continue
            }

            // Everything is fine; add to the map.
            prompts[title] = section.toString().trimEnd()
        }

        logger.debug("--------------------------\n" +
                "Parsed prompts:\n{}" +
                "\n--------------------------", prompts)
        logger.info("Parsed prompts section.")

        return prompts
    }


    /**
     * Replaces constant placeholders in the given `text` (i.e., `{{const name}}`)
     * with corresponding values stored in the [ParsedPrompts.constants] map
     * encoded in the input argument `parsed` (which should have already been
     * initialised generated through [parseSpecialSections] with an input `title`
     * equal to `Const`). This function is used by [parsePrompts]
     *
     * If a placeholder is not found in the [ParsedPrompts.constants] or
     * [ParsedPrompts.variablesDefinition] maps, then an error will be logged, and
     * the placeholder will remain unchanged in the text.
     * Therefore, this method also requires that the [ParsedPrompts.variablesDefinition]
     * map has been populated in the input argument `parsed` using
     * [parseSpecialSections] with an input `title` of `Var` as well.
     *
     * @param text The input text containing prompts with constant placeholders to be
     * replaced.
     * @param parsed The parsed results containing the `constants` and
     *               `variablesDefinition` maps.
     *
     * @return The text with constant placeholders replaced with their corresponding
     * values. This text will be added by [parsePrompts] and [parseAll] as a value of
     * the [ParsedPrompts.prompts] map belonging to the input argument `parsed`.
     */
    private fun replaceConstantsPlaceholders(text: String, parsed: ParsedPrompts): StringBuilder {
        logger.debug("Replacing constants placeholders...")

        // Define the constant placeholder pattern as `{{...}}` and search for it in the input `text`.
        val matcher = Pattern.compile(REGREX_CONST).matcher(text)

        // Create a string where constant placeholders are replaced with the relative values.
        val result = StringBuilder()
        // Iterate for all the matches in the `text`.
        while (matcher.find()) {
            // Get the constant name and remove possible white space at the beginning or end of it.
            val placeholder = matcher.group(1).trim { it <= ' ' }
            // Find the relative value in the `constants` map.
            var replacement = parsed.constants[placeholder]

            // If constant value is not found, also check the `variablesDefinition` map to eventually arise an error.
            if (replacement == null) {
                val variableDefinitions: Map<String, String> = parsed.variablesDefinition
                if (!variableDefinitions.containsKey(placeholder)) {
                    logger.error("Field `{{ $placeholder }}` is undefined !!!!!")
                }
                // Keep the original placeholder if replacement is not found.
                replacement = matcher.group(0)
            }

            // Perform the replacement.
            matcher.appendReplacement(result, replacement)
        }
        // Terminate the `matcher.appendReplacement()` operations.
        matcher.appendTail(result)

        logger.info("Replaced constants placeholders.")
        return result
    }


    /**
     * Stores [MutableOccurrences] of variables used in the [ParsedPrompts.prompts]
     * map of the input argument `parsed`.
     *
     * This function is called by [parseAll] and requires that [parsePrompts] and
     * [parseSpecialSections] with the `Var` title have already been used to
     * initialise the [ParsedPrompts.prompts] and [ParsedPrompts.variablesDefinition]
     * maps of the input argument `parsed`. The result of this function will
     * be used to populate the [ParsedPrompts.variables] map of the input argument
     * `parsed`.
     *
     * The [ParsedPrompts.variables] map is used to efficiently replace the
     * variables placeholders in the prompt section values computed by
     * [PromptsManager.applyVariables] at runtime, which are based on
     * [VariablesFunction].
     *
     * The [ParsedPrompts.variables] map will have the following structure:
     * ```
     *  {
     *      "Title 1": [
     *          { "functionName": "varFun1", "start_idx": 0,  "end_idx": 1 },
     *          { "functionName": "varFun2", "start_idx": 2,  "end_idx": 3 },
     *          ...
     *      ],
     *      "Title 2": [
     *          { "functionName": "varFun3", "start_idx": 4, "end_idx": 5 },
     *          ...
     *      ]
     *      ...
     * }
     * ```
     * Where keys are prompt sections titles where some variable placeholder
     * occurs. While the value is a list of [Occurrence] indicating, the name
     * of the function to be called with [VariablesFunction.invoke] and the
     * range of index where the placeholder should be replaced with the
     * function's output. Note that, by design, [Occurrences] are sorted in
     * ascending order, and that the id ranges are never overlapping.
     *
     * @param parsed The parsed results containing the [ParsedPrompts.prompts]
     * and [ParsedPrompts.variablesDefinition] maps to be populated. The
     * [parseAll] method will store such a map in the input `parsed` object as
     * [ParsedPrompts.variables].
     */
    private fun storeVariablesOccurrences(parsed: ParsedPrompts): HashMap<String, MutableOccurrences> {
        logger.debug("Storing variables occurrences...")

        // Initialize the output object of this function.
        val variablesOccurrences = HashMap<String, MutableOccurrences>()

        // Iterate over all prompts to find variable placeholders.
        for ((title, section) in parsed.prompts.entries) {

            // Match all placeholders in the format {{...}}
            val placeholderPattern = Pattern.compile(REGREX_VAR)
            val matcher = placeholderPattern.matcher(section)
            while (matcher.find()) {

                // Extract the variable name from the placeholder.
                val varName = matcher.group(1).trim { it <= ' ' }

                // Check if the variable exists in the `definitions` map.
                val variableDefinitions = parsed.variablesDefinition

                if (variableDefinitions.contains(varName)) {
                    // Get the function name associated with the variable.
                    val functionName = variableDefinitions[varName]

                    // If this is the first occurrence for the title, initialize the occurrence list.
                    if (!variablesOccurrences.containsKey(title))
                        variablesOccurrences[title] = MutableOccurrences()

                    // Create a map to store the occurrence information.
                    val occurrence = Occurrence(functionName!!, matcher.start(), matcher.end())

                    // Add the occurrence to the list for this title.
                    variablesOccurrences[title]!!.add(occurrence)
                } else {
                    logger.warn("Variable placeholder `{{$varName}}` not found in definitions but used in '$title'.")
                }
            }
        }

        logger.debug("--------------------------\n" +
                "Parsed variable occurrences:\n{}" +
                "\n--------------------------", variablesOccurrences)
        logger.info("Stored variables occurrences.")

        return variablesOccurrences
    }

}
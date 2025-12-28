package cubibon.callAssistant

/**
 * An object that contains utility functions available to the entire project.
 *
 * @author Luca Buoncompagni, © 2025, v1.0.
 */
object Utils {

    /**
     * It trasforms the given data into a String (with `toString()`) end escapes the special characters within such a
     * string. It escapes only the following symbols: `\n`, `\t`, `\r`, `\b`  and `\$` by adding an extra `\` at the
     * beginning.
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
}
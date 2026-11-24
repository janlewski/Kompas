package app.feelings.tracker.export

object BackupJson {
    fun encode(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Int -> value.toString()
        is Long -> value.toString()
        is Double -> value.toString()
        is String -> buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            encode(key.toString()) + ":" + encode(item)
        }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { encode(it) }
        else -> error("unsupported")
    }

    fun parse(text: String): Any? = Parser(text).parseValue()

    private class Parser(private val text: String) {
        private var index = 0

        fun parseValue(): Any? {
            skip()
            if (index >= text.length) error("json")
            return when (text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val map = linkedMapOf<String, Any?>()
            skip()
            if (peek('}')) {
                index++
                return map
            }
            while (index < text.length) {
                val key = parseString()
                skip()
                expect(':')
                map[key] = parseValue()
                skip()
                if (peek('}')) {
                    index++
                    return map
                }
                expect(',')
                skip()
            }
            error("json")
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skip()
            if (peek(']')) {
                index++
                return list
            }
            while (index < text.length) {
                list += parseValue()
                skip()
                if (peek(']')) {
                    index++
                    return list
                }
                expect(',')
                skip()
            }
            error("json")
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val char = text[index++]
                if (char == '"') return out.toString()
                if (char != '\\') {
                    out.append(char)
                    continue
                }
                val escaped = text[index++]
                out.append(
                    when (escaped) {
                        '\\', '"' -> escaped
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        else -> escaped
                    },
                )
            }
            error("json")
        }

        private fun parseNumber(): Any {
            val start = index
            if (peek('-')) index++
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            val raw = text.substring(start, index)
            return if ('.' in raw) raw.toDouble() else raw.toLong()
        }

        private fun literal(token: String, value: Any?): Any? {
            require(text.startsWith(token, index))
            index += token.length
            return value
        }

        private fun expect(char: Char) {
            skip()
            require(index < text.length && text[index] == char)
            index++
        }

        private fun peek(char: Char): Boolean = index < text.length && text[index] == char

        private fun skip() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}

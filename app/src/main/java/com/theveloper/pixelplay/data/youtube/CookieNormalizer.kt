package com.theveloper.pixelplay.data.youtube

/**
 * Normalizes pasted YouTube Music session cookies into a clean `Cookie`
 * header. Accepts a raw `A=1; B=2` header, a Netscape `cookies.txt` file,
 * multiline exports, or the JSON formats produced by common browser
 * extensions.
 *
 * At least one SAPISID token is required because it seeds the SAPISIDHASH
 * authorization header used by account-scoped InnerTube calls.
 */
object CookieNormalizer {

    private val SAPISID_KEYS = listOf("__Secure-3PAPISID", "SAPISID", "APISID")

    fun normalize(raw: String?): String {
        val input = raw?.trim().orEmpty()
        require(input.isNotEmpty()) { "Please enter cookie text." }

        val pairs = mutableMapOf<String, String>()

        fun addPair(name: String, rawValue: String) {
            val n = name.trim()
            var v = rawValue.trim()
            if (n.isEmpty() || v.isEmpty()) return
            if (v.length >= 2 && ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith('\'') && v.endsWith('\'')))) {
                v = v.substring(1, v.length - 1).trim()
            }
            if (v.isNotEmpty()) pairs[n] = v
        }

        if (input.startsWith("[") || input.startsWith("{")) {
            val objPattern = Regex("""\{[^{}]*}""")
            val namePattern = Regex(""""name"\s*:\s*"([^"]+)"""")
            val valuePattern = Regex(""""value"\s*:\s*"([^"]*)"""")
            for (match in objPattern.findAll(input)) {
                val block = match.value
                val n = namePattern.find(block)?.groupValues?.getOrNull(1)
                val v = valuePattern.find(block)?.groupValues?.getOrNull(1)
                if (n != null && v != null) addPair(n, v)
            }
            if (pairs.isEmpty()) {
                val kvPattern = Regex(""""([^"]+)"\s*:\s*"([^"]*)"""")
                val ignored = setOf(
                    "name", "value", "cookies", "domain", "path", "expirationDate",
                    "hostOnly", "httpOnly", "sameSite", "secure", "session", "storeId",
                )
                for (match in kvPattern.findAll(input)) {
                    val k = match.groupValues[1]
                    if (k !in ignored) addPair(k, match.groupValues[2])
                }
            }
        }

        if (pairs.isEmpty()) {
            for (rawLine in input.lines()) {
                var line = rawLine.trim()
                if (line.isEmpty()) continue
                if (line.startsWith("#HttpOnly_", ignoreCase = true)) {
                    line = line.substring(10).trim()
                } else if (line.startsWith("#HttpOnly", ignoreCase = true)) {
                    line = line.substring(9).trim()
                } else if (line.startsWith("#")) {
                    continue
                }
                if (line.startsWith("cookie:", ignoreCase = true)) {
                    line = line.substring(7).trim()
                }
                if ('\t' in line) {
                    val cols = line.split('\t')
                    if (cols.size >= 7) {
                        addPair(cols[5], cols[6])
                        continue
                    }
                    line = line.replace('\t', ';')
                }
                for (part in line.split(';')) {
                    val chunk = part.trim()
                    if (chunk.isEmpty() || chunk.startsWith("#")) continue
                    val index = chunk.indexOf('=')
                    if (index <= 0) continue
                    addPair(chunk.substring(0, index), chunk.substring(index + 1))
                }
            }
        }

        require(pairs.isNotEmpty()) { "No cookies found — paste a Cookie header, cookies.txt, or JSON" }
        require(SAPISID_KEYS.any { pairs.containsKey(it) }) {
            "Missing SAPISID — export cookies from music.youtube.com while signed in"
        }
        return pairs.keys.sorted().joinToString("; ") { "$it=${pairs[it]}" }
    }
}

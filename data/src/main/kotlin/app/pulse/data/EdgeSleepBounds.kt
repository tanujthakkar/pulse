package app.pulse.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Reads onset and offset epoch seconds out of a sleep candidate payload.
 *
 * The search accepts a number under `onset_ts` or, if that key is absent,
 * `onset`. Offset uses `offset_ts` then `offset`. Nested objects and arrays
 * are walked only after the current object. Text that is not JSON, and keys
 * whose values are not numbers, yield a null for that side. The payload text
 * itself is stored separately and is not rewritten.
 */
internal fun edgeSleepBounds(payload: String): Pair<Long?, Long?> {
    val root = try {
        JSONObject(payload)
    } catch (_: JSONException) {
        return null to null
    }
    return findNumber(root, ONSET_KEYS) to findNumber(root, OFFSET_KEYS)
}

private val ONSET_KEYS: List<String> = listOf("onset_ts", "onset")
private val OFFSET_KEYS: List<String> = listOf("offset_ts", "offset")

private fun findNumber(node: Any?, keys: List<String>): Long? {
    when (node) {
        is JSONObject -> {
            keys.forEach { key ->
                if (node.has(key) && !node.isNull(key)) {
                    val value = node.get(key)
                    if (value is Number) return value.toLong()
                }
            }
            val names = node.keys()
            while (names.hasNext()) {
                val found = findNumber(node.get(names.next()), keys)
                if (found != null) return found
            }
        }
        is JSONArray -> {
            for (index in 0 until node.length()) {
                val found = findNumber(node.opt(index), keys)
                if (found != null) return found
            }
        }
    }
    return null
}

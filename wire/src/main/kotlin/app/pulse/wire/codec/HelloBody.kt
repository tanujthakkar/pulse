package app.pulse.wire.codec

/**
 * Hello final body, command 145 revision 1.
 *
 * Offsets are into the command body, after origin sequence and result.
 * Spec §7. Identity blocks are personal device information. [summary] and
 * [redact] are the only views that should leave this parser.
 */
object HelloBody {
    const val FINAL_LENGTH: Int = 107

    /** Body offsets 14..24, 11 bytes. */
    val IDENTITY_A: IntRange = 14 until 25

    /** Body offsets 25..48, 24 bytes. */
    val IDENTITY_OPAQUE: IntRange = 25 until 49

    /** Body offsets 49..78, 30 bytes. */
    val IDENTITY_B: IntRange = 49 until 79

    const val VERSION_OFFSET: Int = 91
    const val SUFFIX_OFFSET: Int = 94

    val IDENTITY: List<IntRange> = listOf(IDENTITY_A, IDENTITY_OPAQUE, IDENTITY_B)

    fun parse(body: ByteArray): ParsedHello? {
        if (body.isEmpty()) return null
        val revision = body[0].toInt() and 0xFF
        val version = if (body.size >= SUFFIX_OFFSET + 4) versionText(body) else null
        return ParsedHello(revision = revision, versionText = version)
    }

    /**
     * Copy with identity ranges replaced by zeros. Bytes outside those ranges,
     * including the version components, stay as they were.
     */
    fun redact(body: ByteArray): ByteArray {
        val copy = body.copyOf()
        for (range in IDENTITY) {
            for (index in range) {
                if (index < copy.size) copy[index] = 0
            }
        }
        return copy
    }

    /**
     * One log line. It names the length, revision, and version, and it does
     * not include identity bytes.
     */
    fun summary(body: ByteArray): String {
        val parsed = parse(body)
        val revision = parsed?.revision?.toString() ?: "absent"
        val version = parsed?.versionText ?: "absent"
        return "hello bytes=${body.size} revision=$revision version=$version identity=redacted"
    }

    private fun versionText(body: ByteArray): String {
        val major = body[VERSION_OFFSET].toInt() and 0xFF
        val minor = body[VERSION_OFFSET + 1].toInt() and 0xFF
        val patch = body[VERSION_OFFSET + 2].toInt() and 0xFF
        val suffix = readU32Le(body, SUFFIX_OFFSET)
        return "$major.$minor.$patch.$suffix"
    }
}

class ParsedHello(
    val revision: Int,
    val versionText: String?,
)

package app.pulse.wire.codec

sealed class AccumulatorEvent {
    class Frame(val frame: WireFrame) : AccumulatorEvent()

    class Rejected(val reason: RejectReason) : AccumulatorEvent()
}

/**
 * Reassembles format-1 frames from notification chunks.
 *
 * A frame may be split at any byte, including every byte. One chunk may hold
 * several frames. Bytes are decoded only after they form a complete candidate.
 *
 * The stream policy follows spec §2 (fragmented data is reassembled before
 * format-1 decode), §4 (acceptance), and §8 (framed records only up to 2140
 * bytes):
 *
 * 1. Append bytes in order.
 * 2. Discard a run of bytes that is not the start pair `AA 01`. A trailing lone
 *    `AA` is kept, because the next chunk may supply `01`.
 * 3. Once four bytes are buffered, read declared length `L`. The complete size
 *    `L + 8` must be longer than 15, `(L − 4)` must be divisible by 4, and the
 *    complete size must be at most [maxFrameBytes]. A candidate that fails is a
 *    local rejection: drop the start byte and keep scanning. Do not wait for a
 *    declared size this buffer will never accept.
 * 4. When the size is acceptable, wait for eight bytes and check CRC-16/MODBUS
 *    over `[0, 6)`. A mismatch drops the start byte and scans again. The length
 *    is not trusted until that CRC matches.
 * 5. When the header CRC matches, wait for `L + 8` bytes and check the CRC-32
 *    trailer. Success emits the frame. Failure emits a local rejection and
 *    consumes the declared frame, because the header CRC authenticated `L`.
 * 6. [reset] drops a partial frame. A disconnect must call it. Later bytes are
 *    not a continuation of the dropped chunk.
 *
 * This type is not thread-safe. The caller serializes appends for one connection.
 */
class FrameAccumulator(
    val maxFrameBytes: Int = WireLimits.MAX_FRAME_BYTES,
) {
    init {
        require(maxFrameBytes >= WireLimits.MIN_FRAME_BYTES) {
            "reassembly buffer must hold a minimum frame"
        }
        require(maxFrameBytes <= WireLimits.MAX_FRAME_BYTES) {
            "reassembly buffer must not exceed the framed-record bound"
        }
    }

    private var data = ByteArray(0)
    private var start = 0
    private var end = 0

    val bufferedByteCount: Int
        get() = end - start

    fun reset() {
        start = 0
        end = 0
    }

    fun append(chunk: ByteArray): List<AccumulatorEvent> {
        if (chunk.isEmpty()) {
            return emptyList()
        }
        ensure(chunk.size)
        chunk.copyInto(data, destinationOffset = end)
        end += chunk.size
        val events = ArrayList<AccumulatorEvent>()
        while (true) {
            val available = end - start
            if (available < 2) {
                break
            }
            if (data[start] != FrameBytes.START || data[start + 1] != FrameBytes.FORMAT) {
                val skipped = skipUntilMarker()
                if (skipped > 0) {
                    events.add(AccumulatorEvent.Rejected(RejectReason.BAD_START))
                }
                if (end - start < 2) {
                    break
                }
                continue
            }
            if (available < 4) {
                break
            }
            val declared = readU16Le(data, start + 2)
            val total = declared + 8
            val sizeReject = rejectSize(declared, total)
            if (sizeReject != null) {
                start += 1
                events.add(AccumulatorEvent.Rejected(sizeReject))
                continue
            }
            if (available < 8) {
                break
            }
            val storedHeader = readU16Le(data, start + 6)
            val actualHeader = Crc16Modbus.compute(data, start, 6)
            if (storedHeader != actualHeader) {
                start += 1
                events.add(AccumulatorEvent.Rejected(RejectReason.BAD_HEADER_CRC))
                continue
            }
            if (available < total) {
                break
            }
            val slice = data.copyOfRange(start, start + total)
            when (val decoded = FrameCodec.decode(slice)) {
                is DecodeResult.Ok -> {
                    start += total
                    events.add(AccumulatorEvent.Frame(decoded.frame))
                }
                is DecodeResult.Rejected -> {
                    start += total
                    events.add(AccumulatorEvent.Rejected(decoded.reason))
                }
            }
        }
        compact()
        return events
    }

    private fun rejectSize(declared: Int, total: Int): RejectReason? {
        if (total > maxFrameBytes) {
            return RejectReason.FRAME_TOO_LARGE
        }
        if (total < WireLimits.MIN_FRAME_BYTES) {
            return RejectReason.TOO_SHORT
        }
        if (declared < 4 || (declared - 4) % 4 != 0) {
            return RejectReason.LENGTH_ALIGNMENT
        }
        return null
    }

    private fun skipUntilMarker(): Int {
        var skipped = 0
        while (end - start >= 1) {
            if (data[start] == FrameBytes.START) {
                if (end - start == 1 || data[start + 1] == FrameBytes.FORMAT) {
                    break
                }
            }
            start += 1
            skipped += 1
        }
        return skipped
    }

    private fun ensure(extra: Int) {
        if (end + extra <= data.size) {
            return
        }
        compact()
        if (end + extra <= data.size) {
            return
        }
        var grown = if (data.isEmpty()) 64 else data.size
        while (grown < end + extra) {
            grown *= 2
        }
        data = data.copyOf(grown)
    }

    private fun compact() {
        if (start == 0) {
            return
        }
        val remaining = end - start
        if (remaining > 0) {
            data.copyInto(data, destinationOffset = 0, startIndex = start, endIndex = end)
        }
        start = 0
        end = remaining
    }
}

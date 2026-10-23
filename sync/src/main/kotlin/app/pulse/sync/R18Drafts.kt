package app.pulse.sync

import app.pulse.wire.codec.HistoryRecord

/**
 * Maps a decoded layout-18 record onto the draft [SyncStore.commitChunk] stores.
 *
 * Layout 18 documents Unix seconds only, so [R18Draft.subsecondTicks] is null.
 * The decoder version stays on [HistoryRecord.Layout18]; the sample table has
 * no column for it.
 */
fun HistoryRecord.Layout18.toR18Draft(): R18Draft {
    val source = stored
    return R18Draft(
        recordIndex = source.recordIndex,
        unixSeconds = source.unixSeconds,
        subsecondTicks = null,
        heartRateBpm = reading.heartRateBpm,
        heartRateRaw = source.heartRateRaw,
        rrCount = source.rrCount,
        rrTicks = source.rrTicks.copyOf(),
        rrBlockRaw = source.rrBlockRaw.copyOf(),
        cardiacRaw = source.cardiacRaw,
        qualityRaw = source.qualityRaw,
        alternateHeartRateRaw = source.alternateHeartRateRaw,
        packedWordRaw = source.packedWordRaw,
        dynamicAccel = source.dynamicAccel,
        dynamicAccelBits = source.dynamicAccelBits,
        axisAccel = source.axisAccel.copyOf(),
        axisAccelBits = source.axisAccelBits.copyOf(),
        stepCounter = source.stepCounter,
        cadenceRaw = source.cadenceRaw,
        hardwareCounter = source.hardwareCounter,
        activityClass = source.activityClass,
        activityClassRaw = source.activityClassRaw,
        counterOverrideRaw = source.counterOverrideRaw,
        auxTempC = source.auxTempC.copyOf(),
        auxTempRaw = source.auxTempRaw.copyOf(),
        skinTempC = reading.skinTempC,
        skinTempRaw = source.skinTempRaw,
        statusRaw = source.statusRaw.copyOf(),
        motionRaw = source.motionRaw,
        restState = source.restState,
        sleepAdjacentRaw = source.sleepAdjacentRaw,
        crc32 = source.crc32,
        raw = source.raw.copyOf(),
    )
}

/**
 * One archived record for the same commit.
 *
 * The bytes are stored whole. Record index and Unix seconds are not inferred
 * for layouts this decoder does not interpret.
 */
fun HistoryRecord.Unknown.toRawFrameDraft(): RawFrameDraft {
    return RawFrameDraft(
        bytes = bytes.copyOf(),
        packetType = bytes.getOrNull(8)?.toInt()?.and(0xFF),
        layout = layout,
    )
}

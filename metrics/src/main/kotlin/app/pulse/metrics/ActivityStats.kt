package app.pulse.metrics

/**
 * A workout the person typed in. Sleep is not stored this way.
 *
 * [startUnix] is inclusive and [endUnix] is exclusive.
 */
data class LoggedActivity(
    val id: Long,
    val type: String,
    val startUnix: Long,
    val endUnix: Long,
)

enum class ActivityKind { SLEEP, MANUAL }

data class ActivityCard(
    val kind: ActivityKind,
    val title: String,
    val startUnix: Long,
    val endUnix: Long,
    val manualId: Long?,
    val edgeProvided: Boolean,
)

/**
 * Sleep episodes and typed workouts, earliest start first.
 *
 * Episodes shorter than [DayRules.MIN_SLEEP_SECONDS] are left out. Nothing
 * here detects a workout from motion.
 */
fun activityTimeline(
    episodes: List<SleepEpisode>,
    logged: List<LoggedActivity>,
): List<ActivityCard> {
    val sleep = episodes
        .filter { it.sleepSeconds >= DayRules.MIN_SLEEP_SECONDS }
        .map { episode ->
            ActivityCard(
                kind = ActivityKind.SLEEP,
                title = "Sleep",
                startUnix = episode.startUnix,
                endUnix = episode.endUnix,
                manualId = null,
                edgeProvided = episode.edgeProvided,
            )
        }
    val manual = logged.map { item ->
        ActivityCard(
            kind = ActivityKind.MANUAL,
            title = item.type,
            startUnix = item.startUnix,
            endUnix = item.endUnix,
            manualId = item.id,
            edgeProvided = false,
        )
    }
    return (sleep + manual).sortedWith(compareBy({ it.startUnix }, { it.endUnix }, { it.title }))
}

data class HeartChartPoint(
    val unixSeconds: Long,
    val bpm: Int,
)

data class ActivityWindowStats(
    val durationSeconds: Long,
    val heartSamples: Int,
    val averageHr: Int?,
    val maxHr: Int?,
    val chart: List<HeartChartPoint>,
    val zones: Strain,
    val approximateStrain: Double?,
)

const val ACTIVITY_CHART_POINTS: Int = 48

/**
 * Heart-rate stats for one activity window.
 *
 * [startUnix] is inclusive and [endUnix] is exclusive. Edge seconds within
 * [DayRules.STRAP_ALIGN_SECONDS] of a strap second are dropped first. Zone
 * seconds use the same bands as the day strain. The strain number is
 * [approximateStrain21] of Banister TRIMP in this window, and it is null
 * when [restingHr] is missing.
 */
fun activityWindow(
    records: List<SecondRecord>,
    startUnix: Long,
    endUnix: Long,
    restingHr: Int?,
): ActivityWindowStats {
    val inside = dropEdgeBesideStrap(records).filter { it.unixSeconds in startUnix until endUnix }
    val hearts = inside.mapNotNull { record -> record.heartRateBpm?.let { record.unixSeconds to it } }
    val zones = zoneStrain(hearts.map { it.second }, restingHr)
    return ActivityWindowStats(
        durationSeconds = (endUnix - startUnix).coerceAtLeast(0L),
        heartSamples = hearts.size,
        averageHr = if (hearts.isEmpty()) null else hearts.sumOf { it.second } / hearts.size,
        maxHr = hearts.maxOfOrNull { it.second },
        chart = downsample(hearts, ACTIVITY_CHART_POINTS),
        zones = zones,
        approximateStrain = zones.trimp?.let { approximateStrain21(it) },
    )
}

/**
 * Label Edge sleep windows, drop Edge seconds beside a strap second, then
 * keep [startUnix, endUnix).
 */
fun prepareWindow(
    records: List<SecondRecord>,
    windows: List<EdgeSleepWindow>,
    startUnix: Long,
    endUnix: Long,
): List<SecondRecord> {
    return dropEdgeBesideStrap(labelEdgeWindows(records, windows))
        .filter { it.unixSeconds in startUnix until endUnix }
}

private fun zoneStrain(heartRates: List<Int>, resting: Int?): Strain {
    var below = 0
    var from100 = 0
    var from120 = 0
    var from140 = 0
    var from160 = 0
    var seconds = 0
    var sum = 0.0
    for (hr in heartRates) {
        when {
            hr < 100 -> below += 1
            hr < 120 -> from100 += 1
            hr < 140 -> from120 += 1
            hr < 160 -> from140 += 1
            else -> from160 += 1
        }
        if (resting != null) {
            seconds += 1
            sum += banisterSecond(hr, resting, DayRules.ASSUMED_HR_MAX)
        }
    }
    return Strain(
        trimp = if (resting == null) null else sum,
        seconds = seconds,
        restingHr = resting,
        below100 = below,
        from100 = from100,
        from120 = from120,
        from140 = from140,
        from160 = from160,
    )
}

private fun downsample(points: List<Pair<Long, Int>>, maxPoints: Int): List<HeartChartPoint> {
    if (points.size <= maxPoints) return points.map { HeartChartPoint(it.first, it.second) }
    val step = points.size.toDouble() / maxPoints.toDouble()
    return (0 until maxPoints).map { index ->
        val sample = points[(index * step).toInt()]
        HeartChartPoint(sample.first, sample.second)
    }
}

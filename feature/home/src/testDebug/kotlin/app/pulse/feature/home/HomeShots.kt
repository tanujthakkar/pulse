package app.pulse.feature.home

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import app.pulse.metrics.ActivityWindowStats
import app.pulse.metrics.HeartChartPoint
import app.pulse.metrics.Rhythm
import app.pulse.metrics.Strain
import com.android.ide.common.rendering.api.SessionParams.RenderingMode
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test

class HomeShots {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(screenHeight = 4800),
        renderingMode = RenderingMode.NORMAL,
    )

    @Test
    fun calendarOpen() {
        snap {
            HomeScreen(
                content = shotDay(),
                onPickDate = {},
                onOpenActivity = {},
                onOpenHealth = {},
                onSaveActivity = { _, _, _, _ -> },
                onDeleteActivity = {},
                initiallyShowCalendar = true,
            )
        }
    }

    @Test
    fun addActivitySheet() {
        snap {
            HomeScreen(
                content = shotDay(),
                onPickDate = {},
                onOpenActivity = {},
                onOpenHealth = {},
                onSaveActivity = { _, _, _, _ -> },
                onDeleteActivity = {},
                initiallyEditing = true,
            )
        }
    }

    @Test
    fun workoutDetail() {
        snap {
            ActivityDetailScreen(
                workoutDetail(
                    "Run",
                    ActivityWindowStats(
                        durationSeconds = 2700,
                        heartSamples = 48,
                        averageHr = 142,
                        maxHr = 168,
                        chart = List(48) { index -> HeartChartPoint(index.toLong(), 120 + (index % 12) * 4) },
                        zones = Strain(
                            trimp = 80.0,
                            seconds = 2700,
                            restingHr = 49,
                            below100 = 60,
                            from100 = 400,
                            from120 = 900,
                            from140 = 1100,
                            from160 = 240,
                        ),
                        approximateStrain = 8.4,
                    ),
                ),
                onBack = {},
            )
        }
    }

    @Test
    fun sleepDetail() {
        val start = LocalDate.of(2026, 11, 7).atTime(23, 0).toEpochSecond(ZoneOffset.UTC)
        val end = LocalDate.of(2026, 11, 8).atTime(6, 0).toEpochSecond(ZoneOffset.UTC)
        snap {
            ActivityDetailScreen(
                sleepDetail(
                    start = start,
                    end = end,
                    zone = ZoneOffset.UTC,
                    rhythm = Rhythm(
                        intervals = 400,
                        accepted = 380,
                        pairs = 300,
                        rmssdMs = 42.0,
                        heartSamples = 2000,
                        restingHr = 49,
                        meanHr = 51.0,
                    ),
                    edgeProvided = false,
                ),
                onBack = {},
            )
        }
    }

    private fun snap(content: @Composable () -> Unit) {
        paparazzi.snapshot {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = PulsePalette.green,
                    onPrimary = Color(0xFF06210F),
                    background = PulsePalette.background,
                    onBackground = PulsePalette.text,
                    surface = PulsePalette.surface,
                    onSurface = PulsePalette.text,
                ),
            ) {
                CompositionLocalProvider(LocalContentColor provides PulsePalette.text, content = content)
            }
        }
    }

}

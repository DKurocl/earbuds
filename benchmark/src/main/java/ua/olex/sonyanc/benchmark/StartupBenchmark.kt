package ua.olex.sonyanc.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    private val withBaselineProfile = CompilationMode.Partial(BaselineProfileMode.Require)

    @Test fun coldLaunch() = startup(StartupMode.COLD, withBaselineProfile)

    @Test fun coldLaunchWithoutBaselineProfile() = startup(StartupMode.COLD, CompilationMode.None())

    @Test fun warmLaunch() = startup(StartupMode.WARM, withBaselineProfile)

    @Test fun hotResume() = startup(StartupMode.HOT, withBaselineProfile)

    private fun startup(mode: StartupMode, compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = "ua.olex.sonyanc",
        metrics = listOf(StartupTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        compilationMode = compilation,
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test fun settingsTransitions() = settingsTransitions(withBaselineProfile)

    @Test fun settingsTransitionsWithoutBaselineProfile() = settingsTransitions(CompilationMode.None())

    private fun settingsTransitions(compilation: CompilationMode) = benchmark.measureRepeated(
        packageName = "ua.olex.sonyanc",
        metrics = listOf(FrameTimingMetric(), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        compilationMode = compilation,
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.desc("Settings")), 5_000L))
        },
    ) {
        repeat(3) {
            checkNotNull(device.findObject(By.desc("Settings"))).click()
            check(device.wait(Until.hasObject(By.desc("Back")), 5_000L))
            device.waitForIdle()
            checkNotNull(device.findObject(By.desc("Back"))).click()
            check(device.wait(Until.hasObject(By.desc("Settings")), 5_000L))
            device.waitForIdle()
        }
    }
}

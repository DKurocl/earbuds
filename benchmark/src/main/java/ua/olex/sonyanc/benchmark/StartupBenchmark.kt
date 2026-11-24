package ua.olex.sonyanc.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
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
class StartupBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun coldLaunch() = startup(StartupMode.COLD)

    @Test fun warmLaunch() = startup(StartupMode.WARM)

    private fun startup(mode: StartupMode) = benchmark.measureRepeated(
        packageName = "ua.olex.sonyanc",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(),
        startupMode = mode,
        iterations = 10,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
    }

    @Test fun settingsTransitions() = benchmark.measureRepeated(
        packageName = "ua.olex.sonyanc",
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(),
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

---
description: Measure Redact's cold start with and without the baseline profile on the stripped-down benchmark emulator, and say what the profile saves
allowed-tools: Bash, PowerShell, Read, Grep, Glob
---

> These commands use the Android SDK's emulator and Gradle with Windows paths, so they only work on my own machine. If you are running in a cloud session without the Android SDK and the emulator, say so and stop instead of trying.

Measure how long a returning user's cold start to the Clean tab takes without any ahead-of-time compilation and with the baseline profile the app ships, and report the difference. Change nothing and commit nothing. Run it after `/generateprofiles` when you want to know whether a profile change helped, or to catch a startup slowdown before a release.

**Which emulator.** It runs on the **benchmark emulator**, `Pixel_10_Pro_Benchmark`, a Gradle-managed emulator in `baselineprofile/build.gradle` used for nothing else. It is an Automated Test Device image, stripped of most system apps and background services so fewer processes compete with Redact while it is timed. It is neither the `Pixel_10_Pro_Profiles` profile emulator nor the `Pixel_10_Pro` test emulator, and never a connected device. Gradle starts and stops it; the first run downloads its system image (`system-images;android-37.0;aosp_atd;x86_64`). It has no Play services, so the emoji font load real phones do at startup is not timed: read only the gap between the two modes. Do not run it while another Gradle build of this project is running: two builds fight over the same build locks.

## 1. Run

1. Run `./gradlew :baselineprofile:Pixel_10_Pro_BenchmarkBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.doubleangels.redact.baselineprofile.StartupBenchmark` (use `gradlew.bat` from PowerShell). It builds the app's benchmark variant (release code, profile installed), runs only `StartupBenchmark` (`startupWithoutProfile` and `startupWithBaselineProfile`, 10 cold starts each; the `class` argument keeps the profile generator's journeys out) and takes several minutes.
2. If it fails, show the relevant error from the test report under `baselineprofile/build/reports/androidTests/managedDevice/` and stop. Do not retry in a loop.

## 2. Read the results

The results are in `com.doubleangels.redact.baselineprofile-benchmarkData.json` under `baselineprofile/build/outputs/managed_device_android_test_additional_output/benchmarkrelease/`. The baseline profile plugin names that folder after the profile emulator (`Pixel_10_Pro_Profiles`) even though the run used `Pixel_10_Pro_Benchmark`; the file's `context.build.model` reads "Android ATD built for x86_64" when it came from the benchmark emulator. Check that, and that the file's time is from this run. For each of the two tests, read `timeToInitialDisplayMs` (and `timeToFullDisplayMs` if present): its `minimum`, `median` and `maximum`.

The first run, on 2026-11-05 with 16,260 baseline and 6,185 startup rules, gave a median time to initial display of 481.7 ms without the profile and 395.6 ms with it (86 ms, 18% faster). Compare with it when no newer result is in the conversation.

## 3. Report

- A table: test, median, minimum and maximum time to initial display.
- What the baseline profile saves: the difference between the two medians, in milliseconds and as a percentage of the no-profile median.
- A verdict. On an emulator the absolute numbers are not what a phone sees, and runs vary by several percent, so judge only the gap between the two modes. A gap of under about 5% means the profile is not helping startup; say so, and point to the startup journey in `Profiles`. If an earlier result is in this conversation, or I give you one, compare with it.

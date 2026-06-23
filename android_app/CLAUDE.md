# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The `android_app/` directory is a modern Kotlin / Jetpack Compose rewrite of **openScale**, an open-source, offline (no internet permission), privacy-respecting weight & body-metrics tracker with support for many Bluetooth (BLE/SPP) smart scales. The Gradle project root is `android_app/` — all commands below run from there. The wider repo also contains `arduino_mcu/` (DIY scale firmware) and `fastlane/` (store metadata); those are unrelated to the app build.

Package root: `com.health.openscale`. JDK 21, AGP 9, Kotlin 2.4, Compose, `minSdk 31` / `compileSdk 37`.

## Commands

Run from `android_app/`. Use `./gradlew` (git-bash) or `.\gradlew.bat` (PowerShell).

```bash
./gradlew testDebugUnitTest                 # JVM unit tests (what CI gates on)
./gradlew testDebugUnitTest --tests "com.health.openscale.core.bluetooth.libs.SoehnleLibTest"   # single test class
./gradlew testDebugUnitTest --tests "*.SoehnleLibTest.decodesWeight"                              # single test method
./gradlew assembleDebug                      # build debug APK -> app/build/outputs/apk/debug/openScale-debug.apk
./gradlew compileDebugAndroidTestKotlin      # type-check instrumented tests without an emulator
./gradlew lint                               # Android lint
```

Build variants: `debug` (`.debug` suffix, dev icon), `beta` (`.beta`), `oss` (Play Store, `.oss`), `release`. Release/oss signing reads keystore properties from `../../openScale.keystore` etc.; absent locally, so prefer debug builds. CI (`ci_master.yml`) runs `testDebugUnitTest`, compiles android tests, and assembles the debug APK.

## Architecture

Layered, with Hilt for DI throughout. Most classes are wired via `@Inject constructor` + `@Singleton`, so there are very few explicit `@Module`s — to find what provides a type, look for its `@Inject` constructor rather than a module.

**UI → Facade → UseCase → Repository/DataStore**

- **UI** (`ui/`): Compose screens under `ui/screen/<feature>/` (overview, table, graph, statistics, insights, settings). Each feature has a `@HiltViewModel`. `ui/shared/SharedViewModel` holds cross-screen state. Navigation is Compose Navigation: `ui/navigation/{Routes,AppNavHost,AppNavigation}.kt`. Single `MainActivity` (`@AndroidEntryPoint`), app class `OpenScaleApp` (`@HiltAndroidApp`). Home-screen widgets use Glance (`ui/widget/`).
- **Facade** (`core/facade/`): the API surface ViewModels call — `MeasurementFacade`, `UserFacade`, `SettingsFacade`, `BluetoothFacade`, `DataManagementFacade`. Each facade is a thin aggregator that delegates to use cases.
- **UseCase** (`core/usecase/`): the actual business logic, split by concern (CRUD, query, filter, smoothing, aggregation, evaluation, insights, import/export, backup/restore, reminders). This is where to add measurement logic.
- **Data** (`core/database/`, `core/data/`, `core/model/`): Room (`AppDatabase`, DAOs, `DatabaseRepository`) for persistence; `core/data/` holds the entities (Measurement, MeasurementType, MeasurementValue, User, UserGoals); `core/model/` holds derived/enriched read models. App preferences/settings use **DataStore** (via `SettingsFacade`). Room schemas are exported to `app/schemas/` and migrations are covered by `MigrationTest` — bump the schema and add a migration + test when changing entities.
- **Service/derivation** (`core/service/`): `DerivedValuesCalculator`, `MeasurementEnricher`, `MeasurementEvaluator`, `TrendCalculator`, and the BLE plumbing `BleScanner`/`BleConnector`.
- **Workers** (`core/worker/`): WorkManager (Hilt-integrated) jobs — `BackupWorker`, `ReminderWorker`, `BootReceiver`.

### Bluetooth scale support (the core domain)

This is the most intricate subsystem and where most contributions land.

- `core/bluetooth/ScaleFactory` (`@Singleton`) builds a `ScaleCommunicator` for a scanned device. It holds an **ordered list of `ScaleDeviceHandler`s** and returns the first whose `supportFor(ScannedDeviceInfo)` is non-null. **Order matters** — handlers that share a BLE service must be listed before more permissive ones (see the `TaylorBIAHandler` vs `MGBHandler` comment in `ScaleFactory`). Registering a new scale = add a handler class in `core/bluetooth/scales/` and insert it in this list at the right position.
- `ScaleDeviceHandler` (abstract, in `scales/`) is the per-device protocol. It declares a `DeviceSupport` (display name, `DeviceCapability` set, `LinkMode`, `TuningProfile`), optionally renders a Compose `DeviceConfigurationUi()`, and reacts to connection/notification/advertisement events, calling `publish()` to emit a `ScaleMeasurement`.
- `LinkMode` selects the adapter: `ModernScaleAdapter` (abstract base) is subclassed by `GattScaleAdapter` (CONNECT_GATT), `BroadcastScaleAdapter` (BROADCAST_ONLY advertisements), and `SppScaleAdapter` (CLASSIC_SPP). The adapter serializes and paces all BLE I/O — **handlers must not sleep or block**; just issue protocol steps in order.
- `core/bluetooth/libs/` holds **pure decoding/computation logic** (impedance→body-composition formulas, frame parsers, the Xiaomi S400 AES-CCM decryptor, etc.), deliberately separated from handlers so it can be unit-tested on the JVM without Bluetooth. Mirrored by `*LibTest` classes — prefer putting testable logic here.

### Testing conventions

Unit tests run on the **JVM via Robolectric** (no emulator). `testOptions.unitTests.isReturnDefaultValues = true` lets pure-logic tests touch `android.util.Log` without "not mocked" failures. Room DAO and migration tests run under Robolectric too. When adding a scale, add a `…LibTest` (decoding) and/or `…HandlerTest` (`supportFor` matching) under `app/src/test/`. `app/src/test/.../testutil/` has shared fixtures. Truth is the assertion library; coroutines/work testing helpers are available.

# Walking Pace Coach

A deliberately simple native Android app for Android 16 that monitors walking/running speed using only the phone's location sensors and repeatedly vibrates the phone when smoothed GPS speed remains below the selected minimum pace.

## Core behavior

- Kotlin + Jetpack Compose.
- `compileSdk = 36` and `targetSdk = 36` for Android 16.
- User starts tracking from the visible app; a `location` foreground service then keeps GPS active while the screen is locked or another app is open.
- Fused Location Provider requests high-accuracy updates only while a workout is active.
- GPS readings worse than 35 m accuracy, implausible walking/running speeds, and unrealistic jumps are rejected.
- Speed is smoothed with a rolling window and trimmed mean.
- Explicit alert states: `WAITING_FOR_GPS`, `ON_TARGET`, `BELOW_TARGET_PENDING`, `BELOW_TARGET_ALERTING`, `PAUSED`.
- A default 4-second grace period prevents vibration from one noisy reading.
- While below target, a phone vibration pattern repeats every 5 seconds by default until speed returns above the hysteresis threshold.
- If **Alert while stopped** is off, pace warnings are suppressed after the configured stationary delay and resume once movement returns.
- No missing GPS fix is treated as `0 km/h`; the UI displays **Waiting for accurate GPS…** instead.
- Completed workouts and speed samples are stored locally with Room.
- No accounts, advertisements, analytics, server, or workout uploads.

## Requirements

- Android Studio with Android 16 / API 36 SDK installed.
- JDK 17 or newer configured for Gradle (Android Studio's bundled JDK is suitable).
- Android phone with Android 8.0+; Android 16 is the primary target.
- Google Play services on the device for the Fused Location Provider.
- Internet is needed only by the development computer the first time Gradle/dependencies are downloaded. The installed app itself works offline.

## 1. Open the project in Android Studio

1. Extract `WalkingPaceCoach.zip` if you downloaded the ZIP.
2. Open Android Studio.
3. Choose **Open**.
4. Select the `WalkingPaceCoach` folder, the folder containing `settings.gradle.kts`.
5. If Android Studio asks for a Gradle JDK, choose JDK 17 or newer.
6. In **Tools > SDK Manager**, make sure **Android SDK Platform 36** is installed.

The project pins Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13, Compose BOM 2026.08.00, Room 2.8.5, and Play Services Location 21.4.0.

## 2. Sync Gradle

In Android Studio choose:

**File > Sync Project with Gradle Files**

You can also run from a terminal in the project folder:

### Windows

```bat
gradlew.bat tasks
```

### macOS / Linux

```bash
./gradlew tasks
```

The included bootstrap scripts download Gradle 8.13 on first use if it is not already cached.

## 3. Build the application

In Android Studio:

**Build > Make Project**

Or from the project directory:

```bash
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
```

## 4. Generate a debug APK

Run:

```bash
./gradlew assembleDebug
```

The APK will be generated at:

`app/build/outputs/apk/debug/app-debug.apk`

Android Studio can also create it through:

**Build > Build Bundle(s) / APK(s) > Build APK(s)**

## 5. Generate a signed release APK

In Android Studio:

1. Choose **Build > Generate Signed App Bundle or APK**.
2. Select **APK**.
3. Choose or create a keystore.
4. Select the `release` build variant.
5. Complete the wizard.

The signed output will normally be under:

`app/build/outputs/apk/release/`

Do not commit your private keystore or passwords to source control.

## 6. Install on an Android 16 phone

### Android Studio

1. Enable **Developer options** and **USB debugging** on the phone.
2. Connect the phone by USB.
3. Approve the computer's debugging prompt.
4. Choose the phone in Android Studio's device selector.
5. Press **Run**.

### ADB with a built APK

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Manual APK installation

Copy the APK to the phone, open it from Files, and allow installation from that source if Android asks.

## First run and permissions

Press **START WALK**. Android will request foreground location access. The app explains that location is used only during an active workout to calculate GPS speed and distance.

On Android 13+, it also requests notification permission so the foreground workout notification can be shown normally. The service itself uses the required Android foreground-service declarations:

- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_LOCATION`
- `foregroundServiceType="location"`
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`
- `POST_NOTIFICATIONS`
- `VIBRATE`

The app deliberately does **not** request `ACCESS_BACKGROUND_LOCATION`. The user starts the location foreground service while the activity is visible; after that the foreground service is designed to keep tracking while the app is backgrounded or the screen is locked.

## Alert logic

Default configuration:

- Target speed: 5.0 km/h.
- First-warning delay: 4 seconds.
- Repeated warning interval: 5 seconds.
- Hysteresis: 0.15 km/h.
- GPS acceptance threshold: 35 m accuracy.
- Stationary threshold: about 0.6 km/h.
- Automatic stopped-warning suppression after 10 seconds when **Alert while stopped** is OFF.

The flow is:

1. `WAITING_FOR_GPS`: no alert is possible until a valid location/speed fix exists.
2. `ON_TARGET`: smoothed speed is acceptable.
3. `BELOW_TARGET_PENDING`: speed is below the entry threshold; the grace timer runs.
4. `BELOW_TARGET_ALERTING`: after the grace period, vibration is triggered and repeats at the configured interval.
5. As soon as smoothed speed rises above the exit threshold, the machine returns to `ON_TARGET` and vibration is cancelled.
6. `PAUSED`: used for a manual pause and for stopped-alert suppression.

## Workout statistics

The completed workout screen contains:

- distance;
- duration;
- average speed;
- maximum smoothed speed;
- selected target;
- time at/above target;
- time below target;
- target achievement percentage;
- slowdown warning count;
- longest at-target streak;
- longest below-target period;
- speed-over-time graph with a horizontal target line.

Completed workouts appear in **History** and can be opened or deleted individually.

## Battery and privacy

Location updates are requested only after **START WALK** and are removed on **FINISH**. Vibration is cancelled and the foreground service stops when the workout finishes.

The Room database and preferences remain local on the phone. Android cloud backup/device transfer is explicitly excluded for the workout database and preferences in the included backup rules.

## Practical GPS notes

Phone GPS speed can fluctuate, especially indoors, between tall buildings, or when a phone has poor sky view. This app intentionally waits for acceptable accuracy and smooths multiple measurements before warning. For best results, start outdoors, allow several seconds for a stable fix, and carry the phone where it has reasonable GPS reception.

# Voice Recorder

A microphone-only Android recorder targeting Android 16 (API 36). Recording runs in a microphone foreground service, supports M4A/AAC and WAV/PCM, publishes files to `Music/Voice Recorder`, and exposes separate Start and Stop widgets.

## Build

Install Android Studio with Android SDK 36 and JDK 17. Open this directory, allow Gradle sync, then run the `app` configuration. From a terminal with an Android SDK configured, run `./gradlew assembleDebug`.

The repository also includes a GitHub Actions workflow that builds the debug APK in a clean GitHub-hosted Ubuntu environment. It installs JDK 17 and Android SDK Platform 36, invokes the checked-in Gradle Wrapper, and does not require signing keys or repository secrets. Android's standard debug signing configuration is used for this development APK.

## Build the APK with GitHub Actions

### 1. Push the project to GitHub

Create an empty repository on GitHub without adding a README, license, or `.gitignore`. From this project directory, commit every project file—including `.github`, `gradle/wrapper`, `gradlew`, and `gradlew.bat`—then connect and push it:

```bash
git add .
git commit -m "Add Android voice recorder and APK build workflow"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
git push -u origin main
```

Replace `YOUR_USERNAME` and `YOUR_REPOSITORY` with the values for your GitHub repository. If an `origin` remote already exists, inspect it with `git remote -v` and update it when necessary with:

```bash
git remote set-url origin https://github.com/YOUR_USERNAME/YOUR_REPOSITORY.git
```

### 2. Open GitHub Actions

Open the repository on GitHub and select the **Actions** tab. If GitHub asks whether to enable workflows for the repository, enable them. Select **Build Android APK** in the workflow list.

### 3. Run the workflow

The workflow runs automatically for pushes and pull requests targeting `main`. To start it manually, select **Run workflow**, choose the `main` branch, and select the green **Run workflow** button. Open the new workflow run and wait for the **Build debug APK** job to complete successfully.

### 4. Download the APK

Open the completed workflow run. At the bottom of its summary page, find the **Artifacts** section and select **voice-recorder-debug-apk**. GitHub downloads a ZIP archive containing `app-debug.apk`. Extract the ZIP before installing the APK on a device.

The artifact is retained for 14 days. This is a development/debug APK; a release build intended for distribution should use a separately configured private signing key stored through GitHub Secrets, never committed to the repository.

The app requests microphone permission and, on Android 13+, notification permission. It requests no phone, contacts, SMS, accessibility, or broad storage permission.

## Replacing Icons

Replace exactly these three PNG files while keeping their filenames unchanged:

- `app/src/main/res/drawable-nodpi/app_icon.png` → application launcher icon
- `app/src/main/res/drawable-nodpi/widget_start.png` → Start Widget icon
- `app/src/main/res/drawable-nodpi/widget_stop.png` → Stop Widget icon

No Kotlin, XML, widget, or Manifest changes are required after replacing them. Use square PNG images with transparency where appropriate; 512×512 pixels or larger is recommended. The launcher resource wrappers automatically use `app_icon.png` for legacy and adaptive launchers.

## Interruption and timing behavior

The service watches Android's active recording configuration for its own audio session. Ringing alone does nothing. If Android silences this app's capture while the audio system is in call/communication mode, the segment is finalized as `beforecall_...`, the logical session remains active, and retries use exponential backoff capped at one minute. A recovered segment is saved as `aftercall_...`. Non-call microphone loss uses the same recovery path but retains normal `recording_...` naming. WAV read failures also recover. No audio is synthesized during gaps and the app never selects a call-audio source.

Both limits use logical-session wall-clock time, including interruption gaps. The effective limit is `min(automatic minutes, safety hours)` and never resets between segments.

Widget taps dispatch directly to the service and never open an activity or display transient UI. Start is ignored if a persisted session exists (and cannot record until permission was granted in the app); Stop is ignored if none exists.

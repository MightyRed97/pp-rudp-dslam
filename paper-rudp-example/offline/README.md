# Offline kit

Everything needed to run and to build the example on a Windows x64 computer without internet access.

## What is in it

| Folder | What it holds | Version | Licence |
|---|---|---|---|
| `jdk/` | Eclipse Temurin JDK: runs Gradle and compiles the app | 17.0.21.1+2 | GPL v2 with the Classpath Exception |
| `gradle/` | Gradle | 8.9 | Apache 2.0 |
| `m2repository/` | Every library the build uses: the Android Gradle plugin 8.7.3, AndroidX, JUnit and their dependencies (259 module versions, Maven layout) | — | open source, mostly Apache 2.0 |
| `node/` | Node.js for the relay server, the command-line client and the server tests | 22.12.0 | MIT |
| `app/IrudpChat-debug.apk` | The app, ready to install | — | — |
| `android-sdk/` | Android SDK: platform 35, build-tools 34.0.0, platform-tools | — | Android SDK Licence |

It also contains these scripts:
- `build-app.bat`: builds and tests the app.
- `run-server.bat`: starts the relay.
- `run-client.bat`: starts the command-line chat client.
- `test-server.bat`: runs the server's tests.
- `install-app.bat`: installs the app with adb.
- `tools/make-kit.ps1`: makes the kit again on a computer with internet access.
- `tools/gradle-cache-to-maven.js`: turns a Gradle cache into `m2repository/`.

## Getting the kit

- **From GitHub.** The repository's release has `offline-kit-windows-x64.zip`. Unzip it at the root of the repository, and it fills `paper-rudp-example/offline/`. The zip has everything except `android-sdk/`, because Google's Android SDK licence does not allow passing the SDK on. See the next section.
- **Making it yourself.** On a Windows computer with internet access, run:

  ```
  powershell -ExecutionPolicy Bypass -File offline\tools\make-kit.ps1
  ```

  It downloads about 600 MB and checks each download against the publisher's checksum. It installs the Android SDK packages, which accepts Google's licence for you. Then it builds the app once to collect the libraries.

## The Android SDK on the offline computer

You need the SDK only to build the app; running the relay and installing the APK don't need it.

Copy these folders from an Android SDK into `offline\android-sdk\`, keeping their paths:
- `platforms\android-35`
- `build-tools\34.0.0`
- `platform-tools`
- `licenses`

Android Studio keeps its SDK in `%LOCALAPPDATA%\Android\Sdk`. Instead of copying, you can set `ANDROID_HOME` to an SDK that has these packages.

## Use

| To | Run |
|---|---|
| Start the relay | `offline\run-server.bat` |
| Chat from the command line | `offline\run-client.bat --user bob` |
| Test the server | `offline\test-server.bat` |
| Install the app on a phone (USB debugging on) or an emulator | `offline\install-app.bat`, or copy the APK to the phone and open it |
| Build and test the app | `offline\build-app.bat` (the APK goes to `offline\app\`) |

The project's `android/settings.gradle` uses `offline\m2repository` by itself whenever the folder exists.

To use **Android Studio** without internet access, change three settings:
1. Under File > Settings > Build, Execution, Deployment > Build Tools > Gradle, set the distribution to *Local installation* at `offline\gradle`, and the Gradle JDK to `offline\jdk`.
2. In the Gradle tool window, turn on offline mode.
3. In File > Project Structure, set the SDK location.

## Other systems

The Gradle distribution and `m2repository/` work on Linux and macOS as well; the repository has `aapt2` for all three systems. The JDK, Node.js and the Android SDK must be the ones for that system. With `JAVA_HOME` and `ANDROID_HOME` set:
- build the app with `offline/gradle/bin/gradle -p android --offline testDebugUnitTest assembleDebug`;
- start the relay with `node server/src/main.js`.

## Notes

- **Keep the Gradle home on a short path.** The default `%USERPROFILE%\.gradle` is fine. Gradle unpacks `aapt2.exe` deep inside its home, and Windows cannot start a program whose path is longer than 260 characters. The build then stops with "AAPT2 ... Daemon startup failed".
- **The APK is a debug build** signed with a development key. To install a build of your own over it, uninstall it first.
- **The kit was tested offline.** The app was built, tested and linted with `--offline` and an empty Gradle home, and nothing was downloaded. `test-server.bat` passed with the kit's Node.js.

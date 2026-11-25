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

- **From GitHub.** The repository's [release](https://github.com/MightyRed97/pp-rudp-dslam/releases/latest) has `offline-kit-windows-x64.zip`. Unzip it at the root of the repository, and it fills `paper-rudp-example/offline/`. The zip has everything except `android-sdk/`, because Google's Android SDK licence does not allow passing the SDK on. See the next section.
- **Making it yourself.** On a Windows computer with internet access, run:

  ```
  powershell -ExecutionPolicy Bypass -File offline\tools\make-kit.ps1
  ```

  It downloads about 600 MB and checks each download against the publisher's checksum. It installs the Android SDK packages, which accepts Google's licence for you. Then it builds the app once to collect the libraries.

## The Android SDK on the offline computer

You need the SDK only to build the app; running the relay and installing the APK don't need it.

The build needs two packages from the SDK:
- **an SDK Platform**: 35, 36 or 34 (the folder `platforms\android-35`, `platforms\android-36` or `platforms\android-34`);
- **Build-Tools** 34.0.0 or newer (a folder under `build-tools`, such as `build-tools\34.0.0`).

It uses Platform 35 and Build-Tools 34.0.0 when they are there. Otherwise it takes Platform 36 or 34, and the oldest Build-Tools newer than 34.0.0, and prints which ones it took. Other versions cannot be used:
- Platform 37 and later are too new for the Android Gradle plugin 8.7.3 of this build;
- Build-Tools older than 34.0.0 are too old for it;
- the AndroidX libraries need Platform 34 or later.

Having other versions installed as well does no harm.

To give the build an SDK, do one of these:
- set `ANDROID_HOME` to an SDK that has these packages. Android Studio keeps its SDK in `%LOCALAPPDATA%\Android\Sdk`;
- copy the two folders into `offline\android-sdk\`, keeping their paths, for example `offline\android-sdk\platforms\android-36` and `offline\android-sdk\build-tools\37.0.0`.

`build-app.bat` uses `offline\android-sdk` when it has a platform in it, and `ANDROID_HOME` otherwise. If Android Studio has written `android\local.properties`, the `sdk.dir` in it comes first.

The build needs neither `platform-tools` nor `licenses`. `install-app.bat` uses the adb in `offline\android-sdk\platform-tools` if it is there, and the adb on your `PATH` otherwise.

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
- **Other SDK versions were tested offline too.** With Platform 34, 35 or 36, and Build-Tools 34.0.0, 35.0.0 or 37.0.0, the app built and passed its 18 unit tests, with no change to the project.
- **Two warnings are harmless.** With Platform 36, the plugin warns that it was tested up to compileSdk 35; the build works all the same. A warning that "this version only understands SDK XML versions up to 3" means that newer Android tools installed the SDK packages.

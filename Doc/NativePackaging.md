# Native packaging

MiMiTrends uses the JDK 26 `jpackage` tool to produce installers containing the application and a private Java runtime. A package must be built on the operating system on which it will run; `jpackage` does not cross-build native formats.

## macOS

Requirements:

- macOS with Xcode command-line tools;
- JDK 26, resolved by the Gradle toolchain;
- a valid `Developer ID Application` certificate in Keychain for distribution outside the App Store;
- Apple notary credentials for the final public DMG.

An unsigned application image for local testing:

```zsh
./gradlew :app:packageMacApp
```

The project wrapper builds a signed DMG and automatically selects the first available `Developer ID Application` identity:

```zsh
./Scripts/build-macos-dmg.zsh
```

A Developer ID signed DMG:

```zsh
security find-identity -v -p codesigning

MAC_SIGNING_KEY_USER_NAME="Iakov Senatov (G2V9T9AD95)" \
  ./gradlew :app:packageMacDmg
```

Store the notarization credentials once in the login keychain. Use an app-specific password, not the Apple ID password:

```zsh
xcrun notarytool store-credentials "MiMiTrends-notary" \
  --apple-id "YOUR_APPLE_ID" \
  --team-id "YOUR_TEAM_ID" \
  --password "YOUR_APP_SPECIFIC_PASSWORD"
```

Then build, sign, submit to Apple, wait for acceptance, staple the ticket, and validate it:

```zsh
APPLE_NOTARY_PROFILE="MiMiTrends-notary" \
MAC_SIGNING_KEY_USER_NAME="Iakov Senatov (G2V9T9AD95)" \
  ./gradlew :app:packageNotarizedMacDmg
```

The equivalent shorter command is:

```zsh
./Scripts/build-macos-dmg.zsh --notarize --profile MiMiTrends-notary
```

CI may use App Store Connect API credentials instead of a keychain profile by setting all of `APPLE_NOTARY_KEY_FILE`, `APPLE_NOTARY_KEY_ID`, and `APPLE_NOTARY_ISSUER_ID`.

Output:

```text
app/build/distributions/native/macos/MiMiTrends-1.0.1.dmg
```

The package task deliberately fails when `MAC_SIGNING_KEY_USER_NAME` is absent. This prevents an ad-hoc signed application from being mistaken for a distributable Developer ID build.

## Windows

Requirements: Windows, JDK 26, and WiX Toolset 3.x available on `PATH`.

```bat
gradlew.bat :app:packageWindowsExe
```

The result is a self-contained per-user EXE installer with Start menu and desktop shortcuts under:

```text
app\build\distributions\native\windows\
```

The task creates the installer but does not Authenticode-sign it. A Windows code-signing certificate can be applied afterward with `signtool` in the release environment.

## Linux

Requirements: Linux and JDK 26. Building the Debian package also needs `fakeroot`.

```bash
./gradlew :app:packageLinuxPortable :app:packageLinuxDeb
```

This creates:

- a portable `MiMiTrends-1.0.1-linux-<arch>.tar.gz` containing an executable and private runtime;
- a Debian/Ubuntu `.deb` package with a desktop-menu entry.

Files are written below:

```text
app/build/distributions/native/linux/
```

## Versions and secrets

The user-facing project version begins at `0.0.0.1`. The native package version is normalized to `1.0.1`, because `jpackage` accepts at most three numeric components and macOS package versions require a positive first component. Build IDs remain independently generated and appear in the title bar and About dialog.

Certificates, Apple passwords, API private keys, and notarization profiles are never stored in the repository. Pass only identity names or environment variables to Gradle.

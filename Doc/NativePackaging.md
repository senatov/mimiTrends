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

A Developer ID signed DMG is built through the same wrapper with an explicit identity:

```zsh
security find-identity -v -p codesigning

./Scripts/build-macos-dmg.zsh --identity "Iakov Senatov (G2V9T9AD95)"
```

Before `jpackage` runs, the Gradle pipeline extracts dependency JARs containing macOS native
libraries, signs every Mach-O binary with the Developer ID identity, hardened runtime, and a secure
timestamp, then rebuilds the JAR. After creating the DMG, `verifySignedMacDmg` mounts it and verifies
the separately signed DMG container, the application signature tree, and every embedded `.dylib` or `.jnilib`. Apple submission
is blocked if this preflight fails.

Run the preflight explicitly when diagnosing signing:

```zsh
MAC_SIGNING_KEY_USER_NAME="Iakov Senatov (G2V9T9AD95)" \
  ./gradlew :app:verifySignedMacDmg
```

Store the notarization credentials once in the login keychain. Use an app-specific password, not the Apple ID password:

```zsh
xcrun notarytool store-credentials "MiMiNotary" \
  --apple-id "YOUR_APPLE_ID" \
  --team-id "YOUR_TEAM_ID" \
  --password "YOUR_APP_SPECIFIC_PASSWORD"
```

Then build, sign, submit to Apple, wait for acceptance, staple the ticket, and validate it:

```zsh
./Scripts/build-macos-dmg.zsh --notarize \
  --identity "Iakov Senatov (G2V9T9AD95)" \
  --profile "MiMiNotary"
```

The equivalent shorter command is:

```zsh
./Scripts/build-macos-dmg.zsh --notarize
```

CI may use App Store Connect API credentials instead of a keychain profile by setting all of `APPLE_NOTARY_KEY_FILE`, `APPLE_NOTARY_KEY_ID`, and `APPLE_NOTARY_ISSUER_ID`.

Output:

```text
app/build/distributions/native/macos/MiMiTrends-<version>.dmg
```

The wrapper increments `appVersion` in `gradle.properties` before every DMG attempt and passes that
exact version to Gradle. Version components carry at ten (`1.0.9` becomes `1.1.0`). The application
label, About dialog, generated build information, JAR manifest, `jpackage` metadata, and DMG filename
therefore use one value. Calling the DMG Gradle task without the wrapper is rejected, preventing a
package from being created without its required version increment.

The package task also deliberately fails when `MAC_SIGNING_KEY_USER_NAME` is absent. This prevents an ad-hoc signed application from being mistaken for a distributable Developer ID build.

## Windows

Requirements: Windows, JDK 26, and WiX Toolset 3.x available on `PATH`.

```bat
Scripts\build-windows-exe.bat
```

The result is a self-contained per-user EXE installer with Start menu and desktop shortcuts under:

```text
app\build\distributions\native\windows\
```

The task creates the installer but does not Authenticode-sign it. A Windows code-signing certificate can be applied afterward with `signtool` in the release environment.

## Linux

Requirements: Linux and JDK 26. Building the Debian package also needs `fakeroot`.

```bash
./Scripts/build-linux-packages.sh
```

Use `--portable-only` or `--deb-only` when only one Linux format is needed.

This creates:

- a portable `MiMiTrends-<version>-linux-<arch>.tar.gz` containing an executable and private runtime;
- a Debian/Ubuntu `.deb` package with a desktop-menu entry.

Files are written below:

```text
app/build/distributions/native/linux/
```

## Versions and secrets

The repository stores one three-component `appVersion` in `gradle.properties`. DMG builds advance
the patch digit automatically, carrying into minor and major digits as necessary. Build IDs remain
independently generated and appear alongside the application version in the title bar and About dialog.

Certificates, Apple passwords, API private keys, and notarization profiles are never stored in the repository. Pass only identity names or environment variables to Gradle.

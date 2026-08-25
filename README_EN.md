# ComicManager

[简体中文](README.md) | [English](README_EN.md)

An Android comic app centered first on **JMComic browsing, search, details, and downloads to a local library**, with local file management and reading tools alongside it. It also supports batch-importing ZIP comics, organizing the library, tracking reading progress, and reading in page-by-page or continuous scrolling mode.

Current version: **1.1** (`versionCode 3`)

System requirement: **Android 7.0 (API 24) or later**

## Key Features

- Browse and search JMComic comics, view comic details, and download comics into the local library
- Use the interface in Simplified Chinese or English, following the system language or a manual in-app selection
- Import one or more ZIP files through the system file picker, automatically extract images, and generate covers
- Search and sort your library, select items in batches, remove duplicates, export, and share
- Read using horizontal page navigation or vertical continuous scrolling, with automatic reading-position saving
- Reading history
- Detect associated IDs from ZIP filenames, check for duplicate bindings, and batch-organize titles
- Manually bind or edit associated IDs and open the corresponding details from the library

Comic images, covers, and the database are all stored locally on the device. The project does not automatically upload personal library data.

## Highlights in v1.1

- Improved batch importing, deduplication, and batch renaming for ZIP files containing associated IDs
- Preserved associated IDs in exported filenames to make future re-imports and relationship recovery easier
- Fixed an issue where opening details by long-pressing a library item could reuse stale page state
- Improved the ID binding dialog and duplicate-ID conflict checks
- Repaired legacy comic directory paths and improved stability during batch operations
- Added stronger exception handling during JMComic client initialization to reduce crashes at startup or during batch processing
- Added Simplified Chinese and English interfaces with an in-app language selector

## Development and Building

### Requirements

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Gradle Wrapper 8.5 (included in the project)

### Common Commands

Run these commands from the project root:

```powershell
# Unit tests
.\gradlew.bat testDebugUnitTest

# Build the Debug APK
.\gradlew.bat assembleDebug

# Run Android Lint
.\gradlew.bat lintDebug
```

The Debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

On macOS or Linux, replace `gradlew.bat` with `./gradlew`. To generate an official release package, first configure signing locally without committing the signing configuration, then run:

```powershell
.\gradlew.bat assembleRelease
```

Signing files, signing configuration, local SDK configuration, and build artifacts must not be committed to the repository.

## Installation and Upgrading

You can download the official APK from GitHub Releases. On first installation, Android may ask you to allow your current file manager or browser to install unknown apps.

To upgrade directly from an older version, the old and new APKs must use the same application signing key:

- A 1.0 release signed with the same official key can be upgraded directly to 1.1
- A Debug build or a build signed with a different key cannot replace the official release directly
- If Android reports a signature conflict, you must uninstall the old version first; uninstalling deletes the library, images, reading history, and progress stored in the app's private directory

## Data Backup Reminder

Before uninstalling the app, switching devices, or testing an APK signed with a different key, use the in-app **Export All** or **Export Selected** feature to save your comics as ZIP files and confirm that the exported files open correctly.

Exported ZIP files are primarily intended to back up comic images and associated IDs embedded in filenames. Internal application state such as reading progress and history is not guaranteed to be included. Keep an additional copy of important data.

## Disclaimer

This project is intended for local file management, learning, and technical exchange. It is not affiliated with JMComic, its operators, or any related application, and makes no guarantees regarding the availability, data accuracy, or API stability of JMComic services.

Only import, download, and read content that you are authorized to use. You are responsible for complying with applicable laws, copyright requirements, and relevant terms of service. All third-party content remains the property of its respective rights holders.

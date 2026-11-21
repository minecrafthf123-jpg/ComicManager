# ComicManager

[简体中文](README.md) | [English](README_EN.md)

An Android comic manager with **JMComic API search, details, and downloads to a local library**, ZIP imports, library organization, zoomable reading, automatic playback, and reading progress.

Current version: **2.3** (`versionCode 8`)

System requirement: **Android 7.0 (API 24) or later**

## Key Features

- Search JMComic using its API, view comic details, and download comics into the local library
- Use the interface in Simplified Chinese or English, following the system language or a manual in-app selection
- Import one or more ZIP files through the system file picker, automatically extract images, and generate covers
- Search and sort your library, select items with drag gestures, remove duplicates, export, and share
- Read with vertical continuous scrolling by default, pinch to zoom and pan, or switch to horizontal pages
- Automatically scroll or turn pages at an adjustable speed, with saved reading progress
- Reading history
- Detect associated IDs from ZIP filenames, check for duplicate bindings, and batch-organize titles
- Manually bind or edit associated IDs and open the corresponding details from the library

Comic images, covers, and the database are all stored locally on the device. The project does not automatically upload personal library data.

## Highlights in v2.3

- Blue-and-white Material 3 styling across the library, history, JM search, folders, and reader, with dark mode following the system
- Google Material icons for bottom navigation and common actions, with both tapping and swiping available for page navigation
- Press and release animations for common actions such as search, import, folder creation, download, and autoplay

## Highlights in v2.2.1

- Fixed stale folder confirmations appearing repeatedly after imports
- Tap the bottom navigation to jump to a page or swipe between pages
- Library cards have a consistent size, with ellipses for long titles

## Highlights in v2.2

- JM downloads appear above local imports. The current sort order applies within both groups
- Select comics in the library and add them to existing folders or create a named folder. Comics can belong to multiple folders
- Browse folders in a grouped directory on the fourth page. Removing a comic from a folder preserves it in the library
- Swipe left or right between the library, reading history, JM search, and folders

## Highlights in v2.1

- The library remembers its sort order and shows the current progress of batch ZIP imports
- Press Enter to search JM, then select individual comics or all loaded results for batch download
- Swipe left on the JM search page to return to reading history
- Share selected comics as ZIP files through Android's share sheet, with one bundle for multiple comics
- Pinch to zoom from 50% to 400% in continuous reading mode
- New chibi character launcher icon on a blue background

## Highlights in v2.0

- Removed the embedded website browser and web downloads; API search, details, and native downloads remain
- Kept a fixed back button available throughout loading, downloads, errors, and completed downloads
- Made continuous scrolling the default and added pinch zoom and panning
- Added automatic scrolling at 0.05–1.5 screens/second and page turns every 2–20 seconds, with pause and automatic stop at the end
- Fixed download cancellation from the details page and cleanup of temporary files
- Show actual total image counts in details instead of treating chapter counts as page counts
- Added range selection, deselection, and edge scrolling when dragging across library items

To use automatic playback, tap Play at the bottom of the reader and tap the speed or interval to adjust it. For drag selection, enter **Select comics** from the library menu, then swipe horizontally or hold an item and drag; starting from a selected item deselects the range.

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

- Older releases signed with the same official key can upgrade directly to 2.3
- A Debug build or a build signed with a different key cannot replace the official release directly
- If Android reports a signature conflict, you must uninstall the old version first; uninstalling deletes the library, images, reading history, and progress stored in the app's private directory

## Data Backup Reminder

Before uninstalling the app, switching devices, or testing an APK signed with a different key, use the in-app **Export All** or **Export Selected** feature to save your comics as ZIP files and confirm that the exported files open correctly.

Exported ZIP files are primarily intended to back up comic images and associated IDs embedded in filenames. Internal application state such as reading progress and history is not guaranteed to be included. Keep an additional copy of important data.

## Disclaimer

This project is intended for local file management, learning, and technical exchange. It is not affiliated with JMComic, its operators, or any related application, and makes no guarantees regarding the availability, data accuracy, or API stability of JMComic services.

Only import, download, and read content that you are authorized to use. You are responsible for complying with applicable laws, copyright requirements, and relevant terms of service. All third-party content remains the property of its respective rights holders.

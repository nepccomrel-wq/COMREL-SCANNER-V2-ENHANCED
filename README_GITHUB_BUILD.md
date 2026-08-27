# COMREL SCANNER — GitHub APK Builder

This version is prepared so GitHub Actions builds the APK for you.
You do NOT need Android Studio on your laptop.

## EASIEST METHOD

### 1. Create a GitHub account
Go to GitHub and sign in.

### 2. Create a new repository

Create a new repository named:

COMREL-SCANNER

For this first test, you may make it Private.

### 3. Upload the PROJECT contents

IMPORTANT:

Upload the CONTENTS of this folder into the repository root.

The repository should look like:

COMREL-SCANNER/
├── .github/
│   └── workflows/
│       └── build-apk.yml
├── app/
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md

Do NOT upload the outer COMREL_SCANNER_GITHUB folder itself as an extra level.

### 4. Build it

After uploading:

GitHub → Actions → Build COMREL SCANNER APK → Run workflow

The workflow also runs automatically when you push to main.

### 5. Download the APK

When the workflow becomes green:

Actions → Build COMREL SCANNER APK → click the successful run

Scroll to:

Artifacts

Download:

COMREL-SCANNER-APK

Extract the downloaded ZIP.

Inside it is:

app-debug.apk

Send that APK to your Android phone and install it.

## Why this works

GitHub's hosted runner supplies the Java/build environment. The workflow checks out the source, sets up Java 17 and Gradle, runs:

gradle :app:assembleDebug

and uploads the resulting APK as a downloadable workflow artifact.

## First build

This is a DEBUG APK for testing.

It is suitable for installing on your own Android phone.

A future production version should use a release signing key so the same app can be updated safely over time.

## Current app features

- COMREL SCANNER branding
- Camera scanning
- Single page
- Multiple pages
- Review pages
- Delete individual page
- Add another page
- Save batch as one PDF
- Offline operation after installation
- Saves to Downloads/COMREL SCANNER
- Small creator credit:
  Created by Community Relations Assistant: Jon Rose

## Current limitation

Version 1 does not yet automatically detect document corners/perspective-crop like CamScanner.

That can be added as Version 2 after the basic APK is successfully installed and tested.

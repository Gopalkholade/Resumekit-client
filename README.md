# ResumeKit clients

This repository contains the user-facing ResumeKit clients. The desktop client is in [`desktop`](./desktop), and the native Android client is in [`android`](./android). The API is maintained separately in the `resumekit-api` repository and deployed to Hugging Face Spaces.

## Android

The Android client uses Kotlin, Jetpack Compose, MVVM/StateFlow, Ktor with the OkHttp engine, and kotlinx.serialization. Open `android` in Android Studio with JDK 17, Gradle 8.13, and Android SDK 36 available, allow Gradle sync to finish, then build or run the `app` configuration. The project requires an HTTPS API base URL and the single-user `RESUMEKIT_API_KEY`; configure these on the **Connect** tab.

To build from a terminal, run `gradle :app:assembleDebug` from the `android` directory. A Gradle wrapper is not included yet.

GitHub Actions builds the debug APK on pushes and pull requests that change files under `android/`. Download `app-debug.apk` from the `resumekit-android-debug-apk` artifact on the workflow run.

The workflow supports LinkedIn PDF work-history import with the Storage Access Framework, GitHub project import, job-description URL import or paste, a separate consent step for each Gemini resume match, ATS gap reporting, server-side PDF compilation, native `PdfRenderer` preview, and PDF sharing. Requests allow up to 90 seconds for a sleeping Space to wake.

The API key, API URL, and cached MasterCV are encrypted with Google Tink AEAD. Tink protects its keyset using Android Keystore; whether that key is backed by dedicated hardware depends on the device and is not guaranteed by the app. Android backup is disabled so encrypted app data is not restored without its device-bound key. LinkedIn PDF selection uses the Storage Access Framework, and PDF sharing grants temporary access through `FileProvider`; broad storage permissions are not requested.

## Desktop development

Install Node.js 20+ and the Rust MSVC toolchain with Visual Studio C++ Build Tools:

```powershell
cd desktop
npm ci
Copy-Item .env.example .env
# Set RESUMEKIT_API_KEY in .env to the key configured in your Space.
npm run tauri:dev
```

The desktop development command loads the API URL and key from `desktop/.env`; this file is ignored by Git. Keep the API protected with the matching `RESUMEKIT_API_KEY` Space secret; the client does not authenticate with Hugging Face account credentials.

The desktop stores API credentials and the reusable profile encrypted with Windows DPAPI for the current Windows account. Credentials are not stored in browser local storage. Each resume match asks for consent before sending the profile and job description to the API and Google Gemini. The desktop waits up to 90 seconds for API requests to allow for a sleeping Space to start.

The API base URL may also be supplied at build time with `VITE_RESUMEKIT_API_URL`. The interactive connection settings can be used to configure or replace the saved URL and token.

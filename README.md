# Kompas

A private, on-device Android journal for sleep, stress, physical feeling, mental feeling, notes and medication reminders. The app has no network access, no accounts and no analytics.

## Decisions

- Manual dependency injection through `AppContainer`. Receivers reach it with `(application as App).container`.
- minSdk 26, targetSdk 36, compileSdk 37. `applicationId` is `app.kompas`.
- The database key is 32 random bytes, wrapped by an Android Keystore AES-256-GCM key and stored in `noBackupFilesDir`. SQLCipher receives it as a raw key (`x'<hex>'`) so it does not run a second passphrase KDF.
- Export uses lazysodium (Argon2id, opslimit 3, 64 MiB) and secretstream. A device benchmark of the KDF has not been run; keep 64 MiB until that measurement says otherwise.
- English and Polish strings ship together. The Polish copy still needs a native-speaker pass.

## Build

```
./gradlew assembleDebug test lint
```

Release permissions are checked with `scripts/check-permissions.sh` against `scripts/allowed-permissions.txt`.

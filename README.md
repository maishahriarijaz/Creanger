## Creanger messenger for Android

Creanger is a messaging app with a focus on speed and security. It’s superfast, simple and free.

## Documentation

The app is built on the Telegram API platform. Full API documentation is available at https://core.telegram.org/api and MTProto protocol manuals at https://core.telegram.org/mtproto.

### Compilation Guide

**Note**: This repo contains dummy release.keystore, google-services.json and filled variables inside BuildVars.java. Before publishing your own APKs please make sure to replace all these files with your own.

You will require Android Studio, Android NDK and Android SDK.

1. Copy your release.keystore into TMessagesProj/config
2. Fill out RELEASE_KEY_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_STORE_PASSWORD in gradle.properties to access your release.keystore
3. Go to https://console.firebase.google.com/, create android apps, turn on firebase messaging and download google-services.json, which should be copied to the TMessagesProj folder.
4. Open the project in the Studio (note that it should be opened, NOT imported).
5. Fill out values in TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java.
6. You are ready to compile Creanger.

### Localization

Translations can be updated via the language pack system in-app.
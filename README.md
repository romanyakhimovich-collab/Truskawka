# Truskawka

Truskawka is an experimental offline-first Android messenger for nearby communication without an internet connection. It uses Bluetooth Low Energy and Wi-Fi Direct to discover nearby devices, build a lightweight mesh network, and exchange messages or images between users.

The app has a soft Strawberry Cream interface while keeping mesh-network status and connection logs readable.

## Features

- Nearby peer discovery over Bluetooth Low Energy and Wi-Fi Direct
- Public broadcast chat for everyone nearby
- Private peer-to-peer chats from the Nearby panel
- Local Saved Messages chat
- Image sending with chunk splitting and reassembly
- Fullscreen image preview
- Nicknames with a required `@` prefix
- 12-character nickname limit
- Weekly nickname change limit
- Foreground mesh service for active local discovery
- Strawberry Cream Android UI

## Tech Stack

- Kotlin
- Android Views
- Bluetooth Low Energy
- Wi-Fi Direct
- Custom mesh routing layer
- Gradle Kotlin DSL

## Requirements

- Android Studio
- Android device with Bluetooth LE support
- Bluetooth enabled
- Required Android permissions granted: Bluetooth, Nearby devices, Wi-Fi Direct, and Location where needed

Two physical Android devices are recommended for testing. Emulators usually do not provide realistic BLE and Wi-Fi Direct behavior.

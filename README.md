# Office Sentry ⚔️

An Android kiosk app for an old phone mounted outside a home office door. A grizzled pixel-art veteran guard (**Old Garrick**), shown as a framed portrait, displays your status, spots visitors with on-device AI, and talks with them using Claude.

## What it does
- **Shows your status:** COME IN / KNOCK FIRST / IN A MEETING / DO NOT DISTURB / AWAY, plus an optional note ("Back at 2:30").
- **Detects visitors on-device:** ML Kit face detection (free, offline) triggers when someone stays in view for about 1 second. Image labeling also catches dogs and cats.
- **Holds a conversation:** Claude greets the visitor, enforces your status, takes messages, and lets real emergencies through. Speech uses the phone's mic (Android speech recognition) and plays through the phone speaker or any paired Bluetooth speaker.
- **Takes messages:** messages visitors leave appear on the phone and on the control page.
- **Wi-Fi control page:** open `http://<phone-ip>:8080` from any PC on your network to change status, make the guard speak, read messages, and change settings. The address appears in the bottom-right corner of the screen.
- **Voice:** pick any installed Google voice on the control page and tune pitch and speed; *Save & preview* plays a test line. The default is a stout veteran (low pitch, slightly slow).
- **Owner menu:** hold a finger on the screen for 3 seconds.
- **Idle behavior:** after 2 minutes with no one around, the guard falls asleep and the screen dims. He wakes when someone appears.

## Install
1. Download the latest APK from **Releases → latest** (every push to `main` builds a new one).
2. On the phone, allow "Install unknown apps" for your browser or file manager, then open the APK.
3. Grant Camera and Microphone permissions.

## Setup
1. Open the control page from your PC (the address is on screen), paste your **Claude API key** from console.anthropic.com, and save. Without a key, the guard still works but uses scripted lines.
2. The default model is `claude-haiku-5-5`, which is fast and cheap. You can change it on the settings page.
3. Optional: set a PIN so visitors (or kids) can't change your status from the phone or the page.
4. Kiosk tips: enable **Settings → Security → App pinning** and pin the app; keep the phone on a charger; turn off its lock screen.

## Privacy
- Detection runs fully on the phone. Snapshots go to Claude only at the start of a conversation, and only when "Send snapshot" is enabled.
- The control page is plain HTTP on your LAN only. Set a PIN if others share your network.

## Build locally
Open the folder in Android Studio (Ladybug or newer) and run. CI builds with Gradle 8.10.2, AGP 8.7.3, JDK 17.

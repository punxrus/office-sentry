# Office Sentry ⚔️

An Android kiosk app for an old phone mounted outside a home office door. A grizzled pixel-art veteran guard (**Old Garrick**), shown as a framed portrait, displays your status, spots visitors with on-device AI, and talks with them using Claude.

## What it does
- **Shows your status:** COME IN / KNOCK FIRST / IN A MEETING / DO NOT DISTURB / AWAY, plus an optional note ("Back at 2:30").
- **Detects visitors on-device:** ML Kit face detection (free, offline) triggers when someone stays in view for about 1 second. Image labeling also catches dogs and cats.
- **Holds a conversation:** Claude greets the visitor, enforces your status, takes messages, and lets real emergencies through. Speech uses the phone's mic (Android speech recognition) and plays through the phone speaker or any paired Bluetooth speaker.
- **Takes messages:** messages visitors leave appear on the phone and on the control page.
- **Wi-Fi control page:** open `http://<phone-ip>:8080` from any PC on your network to change status, make the guard speak, read messages, and change settings. The address appears in the bottom-right corner of the screen.
- **Voice:** pick any installed Google voice on the control page and tune pitch and speed; *Save & preview* plays a test line. The default is a stout veteran (low pitch, slightly slow).
- **Owner menu (secret):** tap the corners in order (top-left, top-right, bottom-right, bottom-left) within 5 seconds. It offers kiosk mode (locks the phone to the app) and **Exit to home screen**. Set a PIN!
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

## Desk herald (optional): ATOM Voice + ESP32-S3-Touch-LCD-7
Garrick can also speak *to you* at your desk: visitor arrivals ("Lord James, there is a visitor at the chamber door"), messages left at the door, and calendar reminders from the phone's signed-in Google calendars.

| Device | Role | Firmware |
|---|---|---|
| M5Stack ATOM Voice (ATOM Echo) | Desk speaker. Streams each line from the phone and starts at a shared time | `Garrick-ATOM-Voice.bin` |
| Waveshare ESP32-S3-Touch-LCD-7 | Voice lines that move with his speech, caption, status, touch status buttons | `Garrick-Desk-Panel.bin` |

**Flash:** open https://espressif.github.io/esptool-js/ in Chrome, connect the device over USB, add the `.bin` at address **0x0**, and click Program. (On the Touch-LCD-7, use the port labeled **UART1**.)

**Setup:** on first boot each device opens a Wi-Fi hotspot (`Garrick-Voice-Setup` / `Garrick-Panel-Setup`). Join it, choose your Wi-Fi, and enter the phone's IP and the Office Sentry PIN (if set). Give the phone a **DHCP reservation** so its IP never changes.

**Reset setup:** ATOM: hold its button while plugging in, for 5 s. Panel: hold a finger on the top-left corner for 8 s.

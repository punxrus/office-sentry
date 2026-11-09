// Old Garrick's desk panel — Waveshare ESP32-S3-Touch-LCD-7 (800x480)
//
//  +-------------------------------------------------------------+
//  | OLD GARRICK            [ STATUS ]                    3:41 PM |  status bar
//  |                                                             |
//  |        ~~~~ voice lines (move with Garrick's speech) ~~~~   |
//  |                                                             |
//  |   "Lord James, there is a visitor at the chamber door."     |  caption
//  | [COME IN] [KNOCK] [MEETING] [DND] [AWAY]                    |  touch buttons
//  +-------------------------------------------------------------+
//
// The Office Sentry phone POSTs /say {text, at, fps, env} — env is a hex string of
// loudness values (0-255) at `fps` frames per second, starting at epoch-ms `at`.
// The ATOM Voice plays the same audio at the same moment.
//
// First boot: join the Wi-Fi hotspot "Garrick-Panel-Setup" and enter your Wi-Fi,
// the phone's IP and (if set) the Office Sentry PIN.
// To redo setup: hold a finger on the top-left corner for 8 seconds.

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <Wire.h>
#include <Arduino_GFX_Library.h>
#include <sys/time.h>
#include <time.h>

// ---------------------------------------------------------------- board

#define I2C_SDA 8
#define I2C_SCL 9
#define TP_INT  4
#define CH422G_CFG 0x24   // CH422G "system parameter" command address
#define CH422G_OUT 0x38   // CH422G IO0-7 output command address
// EXIO bits: 1=TP_RST 2=LCD_BL 3=LCD_RST 4=SD_CS 5=USB_SEL(low = USB)
#define EXIO_RUN       0b00011110
#define EXIO_TP_RESET  0b00011100

Arduino_ESP32RGBPanel *rgb = new Arduino_ESP32RGBPanel(
    5 /* DE */, 3 /* VSYNC */, 46 /* HSYNC */, 7 /* PCLK */,
    1 /* R0 */, 2 /* R1 */, 42 /* R2 */, 41 /* R3 */, 40 /* R4 */,
    39 /* G0 */, 0 /* G1 */, 45 /* G2 */, 48 /* G3 */, 47 /* G4 */, 21 /* G5 */,
    14 /* B0 */, 38 /* B1 */, 18 /* B2 */, 17 /* B3 */, 10 /* B4 */,
    0 /* hsync_polarity */, 8 /* hsync_front_porch */, 4 /* hsync_pulse_width */, 8 /* hsync_back_porch */,
    0 /* vsync_polarity */, 8 /* vsync_front_porch */, 4 /* vsync_pulse_width */, 8 /* vsync_back_porch */,
    1 /* pclk_active_neg */, 16000000 /* prefer_speed */);
Arduino_RGB_Display *gfx = new Arduino_RGB_Display(800, 480, rgb, 0 /* rotation */, true /* auto_flush */);

// voice-line area is drawn off-screen then copied, so it never flickers
#define WAVE_Y 70
#define WAVE_H 230
Arduino_Canvas *wave = nullptr;

// ---------------------------------------------------------------- colours (RGB565)

uint16_t c565(uint8_t r, uint8_t g, uint8_t b) { return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3); }
const uint16_t BG      = c565(18, 14, 22);
const uint16_t PANEL   = c565(34, 28, 40);
const uint16_t GOLD    = c565(212, 168, 64);
const uint16_t GOLD_D  = c565(130, 96, 34);
const uint16_t AMBER   = c565(240, 140, 50);
const uint16_t BRONZE  = c565(150, 100, 60);
const uint16_t PARCH   = c565(238, 224, 190);
const uint16_t INK     = c565(48, 30, 18);
const uint16_t DIM     = c565(150, 140, 130);

struct StatusDef { const char *key; const char *label; const char *btn; uint16_t color; };
StatusDef STATUSES[] = {
  {"OPEN",    "COME IN",        "COME IN",  0},
  {"KNOCK",   "KNOCK FIRST",    "KNOCK",    0},
  {"MEETING", "IN A MEETING",   "MEETING",  0},
  {"DND",     "DO NOT DISTURB", "DND",      0},
  {"AWAY",    "AWAY",           "AWAY",     0},
};
const int NSTAT = 5;

// ---------------------------------------------------------------- state

WebServer server(80);
Preferences prefs;
String phoneIp, pin;

String curStatus = "OPEN", curNote = "", guardName = "Old Garrick";
int msgCount = 0;
bool visitor = false;
bool phoneOk = false;
String caption = "";
uint32_t captionAt = 0;

uint8_t *env = nullptr;   // loudness frames
int envLen = 0, envFps = 30;
int64_t envAt = 0;
float level = 0, phase = 0;

uint32_t lastPoll = 0, lastRegister = 0, lastClock = 0, holdStart = 0;
bool chromeDirty = true;

// ---------------------------------------------------------------- time

int64_t epochMs() { struct timeval tv; gettimeofday(&tv, nullptr); return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000; }
bool timeSynced() { return epochMs() > 1700000000000LL; }

// ---------------------------------------------------------------- CH422G + GT911 touch

void exioWrite(uint8_t v) { Wire.beginTransmission(CH422G_OUT); Wire.write(v); Wire.endTransmission(); }

uint8_t gtAddr = 0x5D;
bool gtReadReg(uint16_t reg, uint8_t *buf, uint8_t len) {
  Wire.beginTransmission(gtAddr); Wire.write(reg >> 8); Wire.write(reg & 0xFF);
  if (Wire.endTransmission(false) != 0) return false;
  if (Wire.requestFrom((int)gtAddr, (int)len) != len) return false;
  for (int i = 0; i < len; i++) buf[i] = Wire.read();
  return true;
}
void gtWriteReg(uint16_t reg, uint8_t v) {
  Wire.beginTransmission(gtAddr); Wire.write(reg >> 8); Wire.write(reg & 0xFF); Wire.write(v); Wire.endTransmission();
}
bool touchRead(int &x, int &y) {
  uint8_t st = 0;
  if (!gtReadReg(0x814E, &st, 1)) return false;
  bool got = false;
  if ((st & 0x80) && (st & 0x0F) > 0) {
    uint8_t p[4];
    if (gtReadReg(0x8150, p, 4)) { x = p[0] | (p[1] << 8); y = p[2] | (p[3] << 8); got = true; }
  }
  if (st & 0x80) gtWriteReg(0x814E, 0);
  return got;
}

void boardInit() {
  Wire.begin(I2C_SDA, I2C_SCL, 400000);
  Wire.beginTransmission(CH422G_CFG); Wire.write(0x01); Wire.endTransmission();   // IO0-7 = outputs
  // GT911 reset with INT low -> I2C address 0x5D
  pinMode(TP_INT, OUTPUT); digitalWrite(TP_INT, LOW);
  exioWrite(EXIO_TP_RESET); delay(20);
  exioWrite(EXIO_RUN); delay(80);
  pinMode(TP_INT, INPUT);
  delay(50);
  Wire.beginTransmission(0x5D);
  if (Wire.endTransmission() != 0) gtAddr = 0x14;
}

// ---------------------------------------------------------------- drawing helpers

void centerText(Arduino_GFX *g, const String &t, int cx, int y, int size, uint16_t col) {
  g->setTextSize(size); g->setTextColor(col);
  int w = t.length() * 6 * size;
  g->setCursor(cx - w / 2, y); g->print(t);
}

uint16_t statusColor(const String &k) {
  if (k == "OPEN") return c565(46, 160, 67);
  if (k == "KNOCK") return c565(226, 160, 30);
  if (k == "MEETING") return c565(205, 50, 50);
  if (k == "DND") return c565(130, 70, 190);
  return c565(110, 110, 125);
}
const char *statusLabel(const String &k) {
  for (auto &s : STATUSES) if (k == s.key) return s.label;
  return "AWAY";
}

String clockText() {
  if (!timeSynced()) return "--:--";
  time_t t = time(nullptr); struct tm lt; localtime_r(&t, &lt);
  char b[16]; int h = lt.tm_hour % 12; if (h == 0) h = 12;
  snprintf(b, sizeof(b), "%d:%02d %s", h, lt.tm_min, lt.tm_hour < 12 ? "AM" : "PM");
  return String(b);
}

void drawStatusBar() {
  gfx->fillRect(0, 0, 800, 66, BG);
  gfx->drawFastHLine(0, 66, 800, GOLD_D);
  gfx->setTextSize(2); gfx->setTextColor(GOLD);
  gfx->setCursor(16, 24); gfx->print(guardName);
  uint16_t sc = statusColor(curStatus);
  gfx->fillRoundRect(250, 8, 300, 50, 10, sc);
  gfx->drawRoundRect(250, 8, 300, 50, 10, GOLD);
  centerText(gfx, statusLabel(curStatus), 400, 22, 3, c565(255, 255, 255));
  gfx->setTextSize(3); gfx->setTextColor(GOLD);
  String c = clockText();
  gfx->setCursor(784 - c.length() * 18, 22); gfx->print(c);
  // small indicators under the clock
  gfx->setTextSize(1);
  gfx->setTextColor(phoneOk ? DIM : c565(220, 80, 80));
  gfx->setCursor(600, 54); gfx->print(phoneOk ? "phone ok" : "no phone");
  if (msgCount > 0) { gfx->setTextColor(AMBER); gfx->setCursor(680, 54); gfx->printf("%d message%s", msgCount, msgCount == 1 ? "" : "s"); }
}

void drawCaption() {
  const int y0 = WAVE_Y + WAVE_H + 4, h = 96;
  gfx->fillRect(0, y0, 800, h, BG);
  String t = caption;
  if (visitor && t.length() == 0) t = "A visitor stands at the door.";
  if (curNote.length() && t.length() == 0) t = curNote;
  if (t.length() == 0) return;
  gfx->fillRoundRect(16, y0, 768, h - 8, 12, PARCH);
  gfx->drawRoundRect(16, y0, 768, h - 8, 12, BRONZE);
  // word-wrap at size 2 (12 px per char) -> 60 chars per line, max 3 lines
  const int perLine = 60;
  String lines[3]; int n = 0; String cur = "";
  int i = 0;
  while (i <= (int)t.length() && n < 3) {
    int sp = t.indexOf(' ', i); if (sp < 0) sp = t.length();
    String word = t.substring(i, sp);
    if ((int)(cur.length() + word.length() + 1) > perLine && cur.length()) { lines[n++] = cur; cur = ""; }
    if (n >= 3) break;
    cur += (cur.length() ? " " : "") + word;
    i = sp + 1;
    if (sp >= (int)t.length()) break;
  }
  if (n < 3 && cur.length()) lines[n++] = cur;
  int ty = y0 + (h - 8 - n * 22) / 2 + 2;
  for (int k = 0; k < n; k++) { centerText(gfx, lines[k], 400, ty, 2, INK); ty += 22; }
}

const int BTN_Y = 404, BTN_H = 66;
void drawButtons() {
  gfx->fillRect(0, BTN_Y - 4, 800, 80, BG);
  int w = 148, gap = 8, x = 12;
  for (int i = 0; i < NSTAT; i++) {
    bool sel = curStatus == STATUSES[i].key;
    uint16_t col = statusColor(STATUSES[i].key);
    if (sel) gfx->fillRoundRect(x, BTN_Y, w, BTN_H, 10, col);
    else { gfx->fillRoundRect(x, BTN_Y, w, BTN_H, 10, PANEL); gfx->drawRoundRect(x, BTN_Y, w, BTN_H, 10, col); }
    if (sel) gfx->drawRoundRect(x - 1, BTN_Y - 1, w + 2, BTN_H + 2, 11, GOLD);
    centerText(gfx, STATUSES[i].btn, x + w / 2, BTN_Y + 25, 2, sel ? c565(255, 255, 255) : col);
    x += w + gap;
  }
}

void drawChrome() { drawStatusBar(); drawCaption(); drawButtons(); chromeDirty = false; }

// voice lines: three tapered sine layers whose height follows Garrick's loudness
void drawWave() {
  wave->fillScreen(BG);
  const int cy = WAVE_H / 2;
  struct Layer { float amp, freq, speed; uint16_t col; int thick; } layers[] = {
    {1.00f, 0.020f, 1.00f, GOLD, 3},
    {0.70f, 0.031f, -1.35f, AMBER, 2},
    {0.45f, 0.047f, 1.80f, BRONZE, 2},
  };
  float base = 4 + level * 92;     // pixels
  for (auto &L : layers) {
    int px = 0, py = cy;
    for (int x = 0; x <= 800; x += 5) {
      float taper = sinf(PI * x / 800.0f); taper *= taper;
      float y = cy + base * L.amp * taper * sinf(x * L.freq + phase * L.speed);
      if (x > 0) for (int t = 0; t < L.thick; t++) wave->drawLine(px, py + t, x, (int)y + t, L.col);
      px = x; py = (int)y;
    }
  }
  wave->flush();
}

// ---------------------------------------------------------------- phone comms

void handleSay() {
  JsonDocument doc;
  if (deserializeJson(doc, server.arg("plain"))) { server.send(400, "text/plain", "bad json"); return; }
  caption = doc["text"] | "";
  captionAt = millis();
  envAt = doc["at"] | (int64_t)0;
  envFps = doc["fps"] | 30;
  const char *hex = doc["env"] | "";
  int n = strlen(hex) / 2;
  uint8_t *buf = (uint8_t *)malloc(n > 0 ? n : 1);
  for (int i = 0; i < n; i++) { char b[3] = {hex[2 * i], hex[2 * i + 1], 0}; buf[i] = (uint8_t)strtol(b, nullptr, 16); }
  uint8_t *old = env; env = buf; envLen = n; free(old);
  if (!timeSynced() || envAt == 0) envAt = epochMs() + 900;
  chromeDirty = true;
  server.send(200, "application/json", "{\"ok\":true}");
}

void pollPhone() {
  if (phoneIp.length() == 0) { phoneOk = false; return; }
  HTTPClient http; http.setTimeout(1500);
  http.begin("http://" + phoneIp + ":8080/api/state");
  int code = http.GET();
  bool was = phoneOk; phoneOk = code == 200;
  if (phoneOk) {
    JsonDocument doc;
    if (!deserializeJson(doc, http.getStream())) {
      String st = doc["status"] | "OPEN";
      String note = doc["note"] | "";
      String g = doc["guard"] | "Old Garrick";
      int mc = doc["messages"].size();
      bool v = doc["visitor"] | false;
      if (st != curStatus || note != curNote || g != guardName || mc != msgCount || v != visitor) {
        curStatus = st; curNote = note; guardName = g; msgCount = mc; visitor = v; chromeDirty = true;
      }
    }
  }
  http.end();
  if (was != phoneOk) chromeDirty = true;
}

void sendStatus(const char *key) {
  curStatus = key; chromeDirty = true;   // optimistic
  if (phoneIp.length() == 0) return;
  HTTPClient http; http.setTimeout(2000);
  http.begin("http://" + phoneIp + ":8080/status");
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  String body = String("status=") + key;
  if (pin.length()) body += "&pin=" + pin;
  http.POST(body);
  http.end();
}

void registerWithPhone() {
  if (phoneIp.length() == 0) return;
  HTTPClient http; http.setTimeout(2000);
  http.begin("http://" + phoneIp + ":8080/api/register");
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  String body = "role=panel&ip=" + WiFi.localIP().toString();
  if (pin.length()) body += "&pin=" + pin;
  http.POST(body);
  http.end();
}

// ---------------------------------------------------------------- setup / loop

WiFiManager wm;

void setup() {
  Serial.begin(115200);
  boardInit();
  gfx->begin();
  gfx->fillScreen(BG);
  for (auto &s : STATUSES) s.color = statusColor(s.key);

  centerText(gfx, "OLD GARRICK", 400, 150, 5, GOLD);
  centerText(gfx, "Joining Wi-Fi...", 400, 230, 2, DIM);
  centerText(gfx, "First time? Connect your phone or PC to the Wi-Fi", 400, 290, 2, DIM);
  centerText(gfx, "\"Garrick-Panel-Setup\" and fill in the form.", 400, 316, 2, DIM);

  prefs.begin("garrick", false);
  phoneIp = prefs.getString("phone", "");
  pin = prefs.getString("pin", "");

  WiFiManagerParameter pPhone("phone", "Office Sentry phone IP (e.g. 192.168.0.50)", phoneIp.c_str(), 40);
  WiFiManagerParameter pPin("pin", "Office Sentry PIN (blank if none)", pin.c_str(), 16);
  wm.addParameter(&pPhone);
  wm.addParameter(&pPin);
  wm.setSaveParamsCallback([&]() {
    phoneIp = pPhone.getValue(); pin = pPin.getValue();
    prefs.putString("phone", phoneIp); prefs.putString("pin", pin);
  });
  wm.setConfigPortalTimeout(600);
  if (!wm.autoConnect("Garrick-Panel-Setup")) ESP.restart();
  if (strlen(pPhone.getValue())) { phoneIp = pPhone.getValue(); prefs.putString("phone", phoneIp); }
  if (strlen(pPin.getValue()) || pin.length()) { pin = pPin.getValue(); prefs.putString("pin", pin); }

  WiFi.setSleep(false);
  configTzTime("PST8PDT,M3.2.0,M11.1.0", "pool.ntp.org", "time.google.com");

  server.on("/say", HTTP_POST, handleSay);
  server.on("/config", HTTP_GET, []() {   // http://<panel-ip>/config?phone=192.168.0.50&pin=1234
    if (server.hasArg("phone")) { phoneIp = server.arg("phone"); prefs.putString("phone", phoneIp); }
    if (server.hasArg("pin")) { pin = server.arg("pin"); prefs.putString("pin", pin); }
    server.send(200, "text/plain", "phone=" + phoneIp);
  });
  server.on("/", HTTP_GET, []() { server.send(200, "text/plain", "Old Garrick's desk panel. phone=" + phoneIp); });
  server.begin();

  wave = new Arduino_Canvas(800, WAVE_H, gfx, 0, WAVE_Y);
  wave->begin(GFX_SKIP_OUTPUT_BEGIN);

  gfx->fillScreen(BG);
  caption = "Lord, your herald stands ready. " + WiFi.localIP().toString();
  captionAt = millis();
  registerWithPhone(); lastRegister = millis();
  pollPhone(); lastPoll = millis();
  drawChrome();
}

void loop() {
  server.handleClient();
  uint32_t now = millis();

  // ---- touch ----
  int tx, ty;
  if (touchRead(tx, ty)) {
    if (tx < 120 && ty < 90) {                       // hold top-left 8 s = redo setup
      if (holdStart == 0) holdStart = now;
      else if (now - holdStart > 8000) {
        gfx->fillScreen(BG); centerText(gfx, "Resetting setup...", 400, 220, 3, GOLD);
        wm.resetSettings(); prefs.clear(); delay(800); ESP.restart();
      }
    } else holdStart = 0;
    static uint32_t lastTap = 0;
    if (ty >= BTN_Y && ty <= BTN_Y + BTN_H && now - lastTap > 600) {
      int idx = (tx - 12) / 156;
      if (idx >= 0 && idx < NSTAT) { lastTap = now; sendStatus(STATUSES[idx].key); }
    }
  } else holdStart = 0;

  // ---- loudness from the envelope at the shared clock ----
  float target = 0.04f + 0.03f * sinf(now / 900.0f);   // idle breathing
  if (env && envLen > 0) {
    int64_t t = epochMs() - envAt;
    if (t >= 0) {
      int f = (int)(t * envFps / 1000);
      if (f < envLen) target = 0.08f + 0.92f * env[f] / 255.0f;
    }
  }
  level += (target - level) * (target > level ? 0.6f : 0.25f);
  phase += 0.12f + level * 0.35f;

  static uint32_t lastFrame = 0;
  if (now - lastFrame >= 50) { lastFrame = now; drawWave(); }

  // ---- housekeeping ----
  if (caption.length() && now - captionAt > 45000) { caption = ""; chromeDirty = true; }
  if (now - lastPoll > 2000) { lastPoll = now; pollPhone(); }
  if (now - lastRegister > 30000) { lastRegister = now; registerWithPhone(); }
  if (now - lastClock > 15000) { lastClock = now; chromeDirty = true; }
  if (chromeDirty) drawChrome();
}

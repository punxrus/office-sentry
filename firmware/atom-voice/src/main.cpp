// Old Garrick's desk voice — M5Stack ATOM Voice (ATOM Echo hardware)
//
// The Office Sentry phone tells this device: "fetch this WAV and start playing at
// wall-clock time T". We stream the 16 kHz mono WAV from the phone, pre-buffer,
// wait for T (NTP-synced) and play through the built-in NS4168 I2S amplifier.
//
// First boot: join the Wi-Fi hotspot "Garrick-Voice-Setup" and enter your Wi-Fi,
// the phone's IP and (if set) the Office Sentry PIN.
// To redo setup: hold the button on top while plugging in USB, keep holding 5 s.

#include <Arduino.h>
#include <WiFi.h>
#include <WiFiManager.h>
#include <WebServer.h>
#include <HTTPClient.h>
#include <Preferences.h>
#include <driver/i2s.h>
#include <sys/time.h>

// ---- ATOM Echo / ATOM Voice pins ----
#define PIN_I2S_BCK  19
#define PIN_I2S_WS   33
#define PIN_I2S_DOUT 22
#define PIN_LED      27
#define PIN_BUTTON   39

#define SAMPLE_RATE  16000
#define PREBUF_BYTES (32 * 1024)   // 1 s of 16 kHz mono 16-bit

WebServer server(80);
Preferences prefs;
String phoneIp, pin;

static uint8_t prebuf[PREBUF_BYTES];
static int16_t stereo[1024];

volatile bool playRequested = false;
String playUrl;
int64_t playAt = 0;
int playVol = 100;
uint32_t lastRegister = 0;

// ---------------------------------------------------------------- helpers

void led(uint8_t r, uint8_t g, uint8_t b) { neopixelWrite(PIN_LED, r, g, b); }

int64_t epochMs() {
  struct timeval tv; gettimeofday(&tv, nullptr);
  return (int64_t)tv.tv_sec * 1000 + tv.tv_usec / 1000;
}
bool timeSynced() { return epochMs() > 1700000000000LL; }

void i2sSetup() {
  i2s_config_t cfg = {};
  cfg.mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_TX);
  cfg.sample_rate = SAMPLE_RATE;
  cfg.bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT;
  cfg.channel_format = I2S_CHANNEL_FMT_RIGHT_LEFT;   // we duplicate mono to both slots
  cfg.communication_format = I2S_COMM_FORMAT_STAND_I2S;
  cfg.intr_alloc_flags = 0;
  cfg.dma_buf_count = 8;
  cfg.dma_buf_len = 256;
  cfg.use_apll = false;
  cfg.tx_desc_auto_clear = true;
  i2s_driver_install(I2S_NUM_0, &cfg, 0, nullptr);
  i2s_pin_config_t pins = {};
  pins.mck_io_num = I2S_PIN_NO_CHANGE;
  pins.bck_io_num = PIN_I2S_BCK;
  pins.ws_io_num = PIN_I2S_WS;
  pins.data_out_num = PIN_I2S_DOUT;
  pins.data_in_num = I2S_PIN_NO_CHANGE;
  i2s_set_pin(I2S_NUM_0, &pins);
  i2s_zero_dma_buffer(I2S_NUM_0);
}

// write mono 16-bit samples (bytes) as stereo with gain + soft clip
void writeMono(const uint8_t *data, size_t bytes) {
  const int16_t *in = (const int16_t *)data;
  size_t n = bytes / 2;
  while (n > 0) {
    size_t chunk = n > 512 ? 512 : n;
    for (size_t i = 0; i < chunk; i++) {
      int32_t v = (int32_t)in[i] * playVol / 100;
      if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
      stereo[2 * i] = stereo[2 * i + 1] = (int16_t)v;
    }
    size_t written;
    i2s_write(I2S_NUM_0, stereo, chunk * 4, &written, portMAX_DELAY);
    in += chunk; n -= chunk;
  }
}

size_t readUpTo(WiFiClient *s, uint8_t *buf, size_t want, uint32_t timeoutMs) {
  size_t got = 0; uint32_t t0 = millis();
  while (got < want && millis() - t0 < timeoutMs) {
    int avail = s->available();
    if (avail > 0) {
      int r = s->read(buf + got, min((size_t)avail, want - got));
      if (r > 0) { got += r; t0 = millis(); }
    } else if (!s->connected()) break;
    else delay(2);
  }
  return got;
}

// ---------------------------------------------------------------- playback

void play() {
  led(40, 20, 0);
  HTTPClient http;
  http.setTimeout(4000);
  if (!http.begin(playUrl)) { led(0, 6, 0); return; }
  int code = http.GET();
  if (code != 200) { http.end(); led(30, 0, 0); delay(300); led(0, 6, 0); return; }
  WiFiClient *s = http.getStreamPtr();
  int remaining = http.getSize();   // -1 if unknown

  uint8_t header[44];
  if (readUpTo(s, header, 44, 2000) < 44) { http.end(); led(0, 6, 0); return; }
  if (remaining > 0) remaining -= 44;

  size_t want = PREBUF_BYTES;
  if (remaining > 0 && (size_t)remaining < want) want = remaining;
  size_t pre = readUpTo(s, prebuf, want, 2000);
  if (remaining > 0) remaining -= pre;

  // wait for the shared start time (so the desk panel's voice lines line up)
  if (timeSynced()) {
    int64_t wait = playAt - epochMs();
    if (wait > 0 && wait < 4000) delay((uint32_t)wait);
  }

  writeMono(prebuf, pre);
  while (remaining != 0 && (s->connected() || s->available())) {
    size_t want2 = sizeof(prebuf);
    if (remaining > 0 && (size_t)remaining < want2) want2 = remaining;
    size_t got = readUpTo(s, prebuf, want2 > 4096 ? 4096 : want2, 1500);
    if (got == 0) break;
    writeMono(prebuf, got & ~1u);
    if (remaining > 0) remaining -= got;
  }
  http.end();
  // let the DMA drain, then silence
  delay(120);
  i2s_zero_dma_buffer(I2S_NUM_0);
  led(0, 6, 0);
}

// ---------------------------------------------------------------- web + registration

void handlePlay() {
  playUrl = server.arg("url");
  playAt = atoll(server.arg("at").c_str());
  playVol = server.hasArg("vol") ? constrain(server.arg("vol").toInt(), 10, 200) : 100;
  if (playUrl.length() == 0) { server.send(400, "text/plain", "missing url"); return; }
  playRequested = true;
  server.send(200, "application/json", "{\"ok\":true}");
}

void registerWithPhone() {
  if (phoneIp.length() == 0 || WiFi.status() != WL_CONNECTED) return;
  HTTPClient http;
  http.setTimeout(2500);
  http.begin("http://" + phoneIp + ":8080/api/register");
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");
  String body = "role=voice&ip=" + WiFi.localIP().toString();
  if (pin.length()) body += "&pin=" + pin;
  http.POST(body);
  http.end();
}

void setup() {
  Serial.begin(115200);
  pinMode(PIN_BUTTON, INPUT);
  led(0, 0, 30);

  prefs.begin("garrick", false);
  phoneIp = prefs.getString("phone", "");
  pin = prefs.getString("pin", "");

  WiFiManager wm;
  // hold the button 5 s at boot to wipe Wi-Fi + settings
  if (digitalRead(PIN_BUTTON) == LOW) {
    uint32_t t0 = millis();
    while (digitalRead(PIN_BUTTON) == LOW && millis() - t0 < 5000) { led(30, 0, 30); delay(50); }
    if (millis() - t0 >= 5000) { wm.resetSettings(); prefs.clear(); phoneIp = ""; pin = ""; led(30, 30, 30); delay(500); }
  }

  WiFiManagerParameter pPhone("phone", "Office Sentry phone IP (e.g. 192.168.0.50)", phoneIp.c_str(), 40);
  WiFiManagerParameter pPin("pin", "Office Sentry PIN (blank if none)", pin.c_str(), 16);
  wm.addParameter(&pPhone);
  wm.addParameter(&pPin);
  wm.setSaveParamsCallback([&]() {
    phoneIp = pPhone.getValue(); pin = pPin.getValue();
    prefs.putString("phone", phoneIp); prefs.putString("pin", pin);
  });
  wm.setConfigPortalTimeout(600);
  led(30, 0, 30);   // purple = setup hotspot may be open
  if (!wm.autoConnect("Garrick-Voice-Setup")) ESP.restart();
  // values may have been edited even when only the params page was used
  if (strlen(pPhone.getValue())) { phoneIp = pPhone.getValue(); prefs.putString("phone", phoneIp); }
  if (strlen(pPin.getValue()) || pin.length()) { pin = pPin.getValue(); prefs.putString("pin", pin); }

  WiFi.setSleep(false);
  configTime(0, 0, "pool.ntp.org", "time.google.com");
  i2sSetup();

  server.on("/play", HTTP_POST, handlePlay);
  server.on("/config", HTTP_GET, []() {   // http://<atom-ip>/config?phone=192.168.0.50&pin=1234
    if (server.hasArg("phone")) { phoneIp = server.arg("phone"); prefs.putString("phone", phoneIp); }
    if (server.hasArg("pin")) { pin = server.arg("pin"); prefs.putString("pin", pin); }
    server.send(200, "text/plain", "phone=" + phoneIp + (timeSynced() ? " time=ok" : " time=unsynced"));
  });
  server.on("/", HTTP_GET, []() {
    server.send(200, "text/plain", "Old Garrick's desk voice. phone=" + phoneIp);
  });
  server.begin();
  led(0, 6, 0);   // dim green = ready
  registerWithPhone();
  lastRegister = millis();
}

void loop() {
  server.handleClient();
  if (playRequested) { playRequested = false; play(); }
  if (millis() - lastRegister > 30000) { lastRegister = millis(); registerWithPhone(); }
  if (WiFi.status() != WL_CONNECTED) { led(30, 0, 0); WiFi.reconnect(); delay(1000); }
  delay(2);
}

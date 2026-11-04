/*
  Scala Ambient — firmware para ESP32
  ------------------------------------
  Controla 5 tiras LED WS2812B (salpicadero + 4 puertas) de un Škoda Scala.
  - Recibe órdenes por Bluetooth LE desde la app "Scala Ambient" (móvil / Android Auto).
  - Guarda la última configuración en la memoria interna.
  - Animación de bienvenida al abrir la puerta (señal de la luz de cortesía).
  - Se duerme cuando el coche lleva un rato apagado y cerrado, y despierta al abrir la puerta.

  Placa: ESP32 DevKit (ESP32-WROOM-32). Núcleo Arduino-ESP32 3.x. Librería: FastLED 3.9.x
  Antes de cargarlo, revisa la sección CONFIGURACIÓN (pines y número de LEDs de cada tira).
*/

#include <Arduino.h>
#include <FastLED.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <Preferences.h>
#include "esp_sleep.h"
#include "driver/rtc_io.h"

// ============================ CONFIGURACIÓN ============================

// Pines de datos de cada tira (pasan por el 74HCT245 para salir a 5 V)
#define PIN_DASH 16   // salpicadero
#define PIN_FL   17   // puerta delantera izquierda (conductor)
#define PIN_FR   18   // puerta delantera derecha
#define PIN_RL   19   // puerta trasera izquierda
#define PIN_RR   21   // puerta trasera derecha

#define PIN_RELAY 23  // relé que da corriente al convertidor de 5 V de los LEDs
#define RELAY_ON  HIGH
#define RELAY_OFF LOW

// Entradas por optoacoplador: LOW = activo
#define PIN_DOOR 33   // luz de cortesía / puerta (debe ser un pin RTC para despertar)
#define PIN_IGN  32   // contacto (12 V conmutado)

// Número de LEDs de cada tira: MÍDELAS y cámbialo (60 LED por metro)
const uint16_t LEN[5] = { 72, 36, 36, 30, 30 };   // dash, fl, fr, rl, rr
#define MAX_LEDS 72                                // el mayor de los de arriba

#define MAX_MILLIAMPS   3000     // límite de consumo de las tiras (convertidor de 5 A)
#define IDLE_SLEEP_MS   120000   // 2 min apagado y cerrado → reposo
#define WELCOME_COOLDOWN 60000   // no repetir la bienvenida antes de 1 min
#define DEVICE_NAME     "Scala Ambient"

// UUIDs del servicio Bluetooth (deben coincidir con la app)
#define SERVICE_UUID "5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0001"
#define CMD_UUID     "5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0002"
#define STATE_UUID   "5ca1a000-0b1e-4c3d-9a7e-2f6b8d1c0003"

// =======================================================================

enum Zone { DASH = 0, FL, FR, RL, RR, ZONES };
enum Effect { FX_FIXED = 0, FX_BREATHE, FX_WAVE, FX_PULSE, FX_COMET, FX_CANDLE, FX_RAINBOW, FX_AURORA, FX_COUNT };

struct State {
  uint8_t magic;
  uint8_t on;
  uint8_t fx;
  uint8_t scene;           // 0 = personalizado, 1..4 = escenas
  uint8_t rgb[ZONES][3];
  uint8_t bright[ZONES];   // 0..100
  uint8_t speed;           // 1 (lento) .. 10 (rápido)
  uint8_t welcomeOnDoor;   // 1 = bienvenida al abrir la puerta
};

static State st;
static CRGB leds[ZONES][MAX_LEDS];
static float level[ZONES];        // nivel mostrado 0..1 (para fundidos suaves)
static CRGB shown[ZONES];         // color mostrado (para fundidos suaves)

static Preferences prefs;
static bool dirty = false;
static uint32_t dirtyAt = 0;

static BLECharacteristic *stateChar = nullptr;
static volatile bool bleConnected = false;
static QueueHandle_t cmdQueue;

static uint32_t lastActivity = 0;
static uint32_t lastDoorSeen = 0;
static bool doorWasOpen = false;
static uint32_t lastWelcome = 0;

static bool welcomeRunning = false;
static uint32_t welcomeStart = 0;

// ---------- Escenas (iguales que en la app) ----------
struct SceneDef { uint32_t dash, front, rear; uint8_t bd, bf, br; };
static const SceneDef SCENES[5] = {
  { 0, 0, 0, 0, 0, 0 },
  { 0xF2F4F7, 0xE4002B, 0xE4002B, 70, 60, 50 },   // 1 Esencia: salpicadero blanco, puertas rojas
  { 0xFFC98A, 0xFFC98A, 0xFFC98A, 62, 55, 45 },   // 2 Confort
  { 0xE4002B, 0xE4002B, 0xE4002B, 92, 85, 75 },   // 3 Monte Carlo
  { 0xFF9F0A, 0xFF9F0A, 0xFF9F0A, 16, 12, 10 },   // 4 Noche
};

static void setZone(int z, uint32_t hex, uint8_t b) {
  st.rgb[z][0] = (hex >> 16) & 0xFF;
  st.rgb[z][1] = (hex >> 8) & 0xFF;
  st.rgb[z][2] = hex & 0xFF;
  st.bright[z] = b;
}

static void applyScene(uint8_t id) {
  if (id < 1 || id > 4) return;
  const SceneDef &s = SCENES[id];
  setZone(DASH, s.dash, s.bd);
  setZone(FL, s.front, s.bf);
  setZone(FR, s.front, s.bf);
  setZone(RL, s.rear, s.br);
  setZone(RR, s.rear, s.br);
  st.scene = id;
  st.on = 1;
}

static void defaults() {
  memset(&st, 0, sizeof(st));
  st.magic = 0x5E;
  st.fx = FX_FIXED;
  st.speed = 5;
  st.welcomeOnDoor = 1;
  applyScene(1);
}

static void loadState() {
  prefs.begin("ambient", false);
  size_t n = prefs.getBytes("st", &st, sizeof(st));
  if (n != sizeof(st) || st.magic != 0x5E) defaults();
}

static void markDirty() { dirty = true; dirtyAt = millis(); }

static void saveIfNeeded() {
  if (dirty && millis() - dirtyAt > 2000) {
    prefs.putBytes("st", &st, sizeof(st));
    dirty = false;
  }
}

// ---------- Estado → texto para la app ----------
// "<on> <fx> <scene> <speed> <welcome> RRGGBBbb RRGGBBbb RRGGBBbb RRGGBBbb RRGGBBbb"
static void notifyState() {
  if (!stateChar) return;
  char buf[96];
  int p = snprintf(buf, sizeof(buf), "%u %u %u %u %u", st.on, st.fx, st.scene, st.speed, st.welcomeOnDoor);
  for (int z = 0; z < ZONES; z++) {
    p += snprintf(buf + p, sizeof(buf) - p, " %02X%02X%02X%02X",
                  st.rgb[z][0], st.rgb[z][1], st.rgb[z][2], st.bright[z]);
  }
  stateChar->setValue((uint8_t *)buf, p);
  if (bleConnected) stateChar->notify();
}

static void startWelcome() {
  if (!st.on) return;
  welcomeRunning = true;
  welcomeStart = millis();
  lastWelcome = welcomeStart;
  for (int z = 0; z < ZONES; z++) { level[z] = 0; }
}

// ---------- Órdenes ----------
// P1 / P0 / PT           encender / apagar / alternar
// C <mask> <RRGGBB>      color en las zonas de la máscara (1 dash, 2 fl, 4 fr, 8 rl, 16 rr)
// B <mask> <0..100>      intensidad
// F <0..7>               efecto: fijo, respirar, ola, latido, cometa, vela, arcoíris, aurora
// V <1..10>              velocidad de los efectos
// O1 / O0                bienvenida al abrir la puerta: sí / no
// S <1..4>               escena: esencia, confort, monte carlo, noche
// W                      repetir bienvenida
// ?                      pedir estado
static void handleCommand(const char *cmd) {
  lastActivity = millis();
  char op = cmd[0];
  if (op == 'P') {
    if (cmd[1] == '1') st.on = 1;
    else if (cmd[1] == '0') st.on = 0;
    else st.on = !st.on;
    markDirty();
  } else if (op == 'C') {
    unsigned mask = 0; char hex[8] = {0};
    if (sscanf(cmd + 1, "%u %6s", &mask, hex) == 2) {
      uint32_t v = strtoul(hex, nullptr, 16);
      for (int z = 0; z < ZONES; z++) if (mask & (1 << z)) setZone(z, v, st.bright[z]);
      st.scene = 0; st.on = 1; markDirty();
    }
  } else if (op == 'B') {
    unsigned mask = 0, b = 0;
    if (sscanf(cmd + 1, "%u %u", &mask, &b) == 2) {
      if (b > 100) b = 100;
      for (int z = 0; z < ZONES; z++) if (mask & (1 << z)) st.bright[z] = b;
      st.scene = 0; st.on = 1; markDirty();
    }
  } else if (op == 'F') {
    int f = atoi(cmd + 1);
    if (f >= 0 && f < FX_COUNT) { st.fx = f; markDirty(); }
  } else if (op == 'V') {
    int v = atoi(cmd + 1);
    if (v >= 1 && v <= 10) { st.speed = v; markDirty(); }
  } else if (op == 'O') {
    st.welcomeOnDoor = cmd[1] == '1'; markDirty();
  } else if (op == 'S') {
    applyScene(atoi(cmd + 1)); markDirty();
  } else if (op == 'W') {
    bool wasOn = st.on;
    st.on = 1;                 // probar la bienvenida aunque estén apagadas
    startWelcome();
    if (!wasOn) markDirty();
  }
  notifyState();
}

class ServerCb : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { bleConnected = true; }
  void onDisconnect(BLEServer *s) override {
    bleConnected = false;
    s->getAdvertising()->start();
  }
};

class CmdCb : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String v = c->getValue();
    char msg[32] = {0};
    strncpy(msg, v.c_str(), sizeof(msg) - 1);
    xQueueSend(cmdQueue, msg, 0);
  }
};

static void setupBle() {
  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCb());
  BLEService *svc = server->createService(SERVICE_UUID);
  BLECharacteristic *cmd = svc->createCharacteristic(
      CMD_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  cmd->setCallbacks(new CmdCb());
  stateChar = svc->createCharacteristic(
      STATE_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
  stateChar->addDescriptor(new BLE2902());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  notifyState();
}

// ---------- Dibujo ----------
static inline float clamp01(float x) { return x < 0 ? 0 : (x > 1 ? 1 : x); }
static inline float smooth(float x) { x = clamp01(x); return x * x * (3 - 2 * x); }

// Brillo perceptual: 0..100 → 0..1 con curva gamma
static inline float perceptual(uint8_t b) { return powf(b / 100.0f, 2.2f); }

static inline float speedMul() { return 0.4f + st.speed * 0.16f; }   // 1 → 0,56× · 5 → 1,2× · 10 → 2×
static inline float gauss(float x, float c, float w) { float d = (x - c) / w; return expf(-d * d); }

// Intensidad 0..1 de un LED según el efecto. t = segundos.
static float effectLed(int z, int i, float t) {
  float sp = speedMul();
  int n = LEN[z] > 1 ? LEN[z] : 1;
  float pos = (float)i / n;
  switch (st.fx) {
    case FX_BREATHE: {
      float k = fmodf(t * sp / 4.2f, 1.0f);
      return 0.3f + 0.7f * (0.5f + 0.5f * cosf(k * 2 * PI));
    }
    case FX_WAVE: {
      static const float delay[ZONES] = { 0.0f, 0.35f, 0.35f, 0.7f, 0.7f };
      float k = fmodf(t * sp / 2.2f - delay[z] / 2.2f + 10.0f, 1.0f);
      return 0.25f + 0.75f * (0.5f - 0.5f * cosf(k * 2 * PI));
    }
    case FX_PULSE: {                      // dos latidos y pausa
      float k = fmodf(t * sp / 1.5f, 1.0f);
      return 0.22f + 0.78f * fmaxf(gauss(k, 0.10f, 0.045f), 0.7f * gauss(k, 0.30f, 0.05f));
    }
    case FX_COMET: {                      // una luz recorre cada tira dejando estela
      float head = fmodf(t * sp * 0.55f + (z == DASH ? 0 : 0.25f * z), 1.0f);
      float d = head - pos; if (d < 0) d += 1.0f;
      return 0.10f + 0.90f * expf(-d * 9.0f);
    }
    case FX_CANDLE: {                     // parpadeo suave y cálido, distinto en cada zona
      float o = z * 1.7f;
      float f = 0.5f * sinf(t * sp * 3.1f + o) + 0.3f * sinf(t * sp * 7.3f + o * 2) + 0.2f * sinf(t * sp * 13.7f + o * 3);
      return 0.62f + 0.30f * f + 0.06f * sinf(i * 0.9f + t * 5);
    }
    default:
      return 1.0f;
  }
}

// Color de un LED: igual al de la zona salvo en arcoíris y aurora.
static CRGB effectColor(int z, int i, float t, const CRGB &base) {
  float sp = speedMul();
  if (st.fx == FX_RAINBOW) {
    uint8_t hue = (uint8_t)(t * sp * 28.0f + i * 4 + z * 40);
    return CHSV(hue, 230, 255);
  }
  if (st.fx == FX_AURORA) {               // el color de la zona se funde con uno vecino que se mueve
    CHSV h = rgb2hsv_approximate(base);
    CRGB other = CHSV(h.hue + 48, h.sat < 60 ? 160 : h.sat, 255);
    float k = 0.5f + 0.5f * sinf(i * 0.22f + t * sp * 1.2f + z);
    return blend(base, other, (uint8_t)(k * 200));
  }
  return base;
}

// Bienvenida: la tira del salpicadero crece desde el centro y luego se encienden las puertas,
// empezando por la del conductor. Devuelve la intensidad 0..1 de cada LED.
static float welcomeLed(int z, int i, float t) {
  if (z == DASH) {
    float p = smooth(t / 1.4f);
    float c = (LEN[DASH] - 1) / 2.0f;
    float d = fabsf(i - c) / (c + 0.5f);
    return clamp01((p - d) * 6.0f);
  }
  static const float start[ZONES] = { 0, 1.2f, 1.5f, 1.8f, 2.0f };
  float k = clamp01((t - start[z]) / 0.6f);
  // la luz recorre la puerta de delante a atrás
  float pos = (float)i / (LEN[z] > 1 ? LEN[z] - 1 : 1);
  return clamp01((k * 1.4f - pos * 0.4f));
}

static void render(uint32_t now) {
  float wt = welcomeRunning ? (now - welcomeStart) / 1000.0f : 0;
  if (welcomeRunning && wt > 3.2f) welcomeRunning = false;

  for (int z = 0; z < ZONES; z++) {
    CRGB target(st.rgb[z][0], st.rgb[z][1], st.rgb[z][2]);
    float goal = st.on ? perceptual(st.bright[z]) : 0.0f;

    // fundido suave hacia el objetivo (~0,5 s)
    level[z] += (goal - level[z]) * 0.09f;
    nblend(shown[z], target, 24);

    bool fx = st.on && !welcomeRunning && st.fx != FX_FIXED;
    float ts = now / 1000.0f;
    for (int i = 0; i < LEN[z]; i++) {
      float a = welcomeRunning ? welcomeLed(z, i, wt) * goal : level[z] * (fx ? effectLed(z, i, ts) : 1.0f);
      CRGB c = welcomeRunning ? target : (fx ? effectColor(z, i, ts, shown[z]) : shown[z]);
      c.nscale8_video((uint8_t)(clamp01(a) * 255));
      leds[z][i] = c;
    }
  }
  if (welcomeRunning && wt > 3.0f) {
    for (int z = 0; z < ZONES; z++) { level[z] = st.on ? perceptual(st.bright[z]) : 0; shown[z] = CRGB(st.rgb[z][0], st.rgb[z][1], st.rgb[z][2]); }
  }
  FastLED.show();
}

// ---------- Reposo ----------
static void goToSleep() {
  // despedida: fundido de 1,5 s
  uint32_t t0 = millis();
  while (millis() - t0 < 1500) {
    uint8_t k = 255 - (uint8_t)((millis() - t0) * 255 / 1500);
    for (int z = 0; z < ZONES; z++) for (int i = 0; i < LEN[z]; i++) leds[z][i].nscale8(k > 250 ? 250 : k);
    FastLED.show();
    delay(16);
  }
  FastLED.clear(true);
  prefs.putBytes("st", &st, sizeof(st));
  delay(50);
  const int dataPins[ZONES] = { PIN_DASH, PIN_FL, PIN_FR, PIN_RL, PIN_RR };
  for (int z = 0; z < ZONES; z++) { pinMode(dataPins[z], OUTPUT); digitalWrite(dataPins[z], LOW); }
  digitalWrite(PIN_RELAY, RELAY_OFF);

  rtc_gpio_pullup_en((gpio_num_t)PIN_DOOR);
  rtc_gpio_pulldown_dis((gpio_num_t)PIN_DOOR);
  esp_sleep_enable_ext0_wakeup((gpio_num_t)PIN_DOOR, 0);
  esp_deep_sleep_start();
}

// ---------- Arranque ----------
void setup() {
  pinMode(PIN_RELAY, OUTPUT);
  digitalWrite(PIN_RELAY, RELAY_ON);     // primero la corriente de las tiras, luego los datos
  pinMode(PIN_DOOR, INPUT_PULLUP);
  pinMode(PIN_IGN, INPUT_PULLUP);
  delay(120);

  FastLED.addLeds<WS2812B, PIN_DASH, GRB>(leds[DASH], LEN[DASH]);
  FastLED.addLeds<WS2812B, PIN_FL, GRB>(leds[FL], LEN[FL]);
  FastLED.addLeds<WS2812B, PIN_FR, GRB>(leds[FR], LEN[FR]);
  FastLED.addLeds<WS2812B, PIN_RL, GRB>(leds[RL], LEN[RL]);
  FastLED.addLeds<WS2812B, PIN_RR, GRB>(leds[RR], LEN[RR]);
  FastLED.setMaxPowerInVoltsAndMilliamps(5, MAX_MILLIAMPS);
  FastLED.setBrightness(255);
  FastLED.clear(true);

  loadState();
  for (int z = 0; z < ZONES; z++) { level[z] = 0; shown[z] = CRGB(st.rgb[z][0], st.rgb[z][1], st.rgb[z][2]); }

  cmdQueue = xQueueCreate(16, 32);
  setupBle();

  lastActivity = millis();
  if (esp_sleep_get_wakeup_cause() == ESP_SLEEP_WAKEUP_EXT0) {
    doorWasOpen = true;
    lastDoorSeen = millis();
    if (st.welcomeOnDoor) startWelcome();
  }
}

void loop() {
  uint32_t now = millis();

  char msg[32];
  while (xQueueReceive(cmdQueue, msg, 0) == pdTRUE) handleCommand(msg);

  // La luz de cortesía puede ir regulada (PWM): se considera "abierta" si se ha visto activa hace < 300 ms
  if (digitalRead(PIN_DOOR) == LOW) lastDoorSeen = now;
  bool doorOpen = now - lastDoorSeen < 300;
  bool ignition = digitalRead(PIN_IGN) == LOW;

  if (st.welcomeOnDoor && doorOpen && !doorWasOpen && !ignition && now - lastWelcome > WELCOME_COOLDOWN) startWelcome();
  doorWasOpen = doorOpen;

  if (doorOpen || ignition || welcomeRunning) lastActivity = now;   // las órdenes de la app también cuentan (handleCommand)

  static uint32_t lastFrame = 0;
  if (now - lastFrame >= 16) { lastFrame = now; render(now); }

  saveIfNeeded();

  if (now - lastActivity > IDLE_SLEEP_MS) goToSleep();
}

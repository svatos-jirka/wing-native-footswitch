/*
 * WING Monitor – three-button foot pedal
 *
 * ESP32 (WROOM-32) + 3 × MS-1A-L14.5-P (KW11-style) micro switches + WS2812B strip with 5 LEDs.
 * The pedal is a BLE client: it looks for the phone running WING Monitor
 * (the app advertises the pedal service once its "BLE" badge is switched on),
 * connects to the nearest one and writes two bytes [button, pressed] on every
 * press and release – the same protocol as tools/pc-simulator/pedal_simulator.py.
 *
 * LEDs (LED 1 = left end of the strip, LED 5 = right end):
 *   looking for phone    blue snake crawling from left to right, over and over
 *   connected            all dim green (resting colour)
 *   flap pressed         short red flash: left flap LED 1, centre LED 3, right LED 5
 *   centre held 0.65 s   short red flash of LEDs 2, 3 and 4 (the app mutes / goes back)
 *
 * Libraries: NimBLE-Arduino 2.x, FastLED 3.x. Board: "ESP32 Dev Module".
 *
 * Wokwi simulator: Wokwi has no Bluetooth, so set WING_PEDAL_SIMULATION to 1. The phone is then
 * faked by a fourth button on GPIO 14 (toggles "phone in range"), and BLE writes go to Serial.
 */

#ifndef WING_PEDAL_SIMULATION
#define WING_PEDAL_SIMULATION 0
#endif

#include <Arduino.h>
#include <FastLED.h>
#if !WING_PEDAL_SIMULATION
#include <NimBLEDevice.h>
#endif

// ---------------------------------------------------------------- Hardware
// Micro switch: COM -> GND, NO -> GPIO (internal pull-up, pressed = LOW). NC stays unconnected.
constexpr uint8_t PIN_LEFT = 25;
constexpr uint8_t PIN_CENTER = 33;
constexpr uint8_t PIN_RIGHT = 32;
// WS2812B DIN through a ~330 ohm resistor.
constexpr uint8_t PIN_LED_DATA = 13;
constexpr uint8_t LED_COUNT = 5;
// Set to true if the strip is mounted so that its first LED (DIN end) sits at the right flap.
constexpr bool LED_REVERSED = false;
// 0–255. Kept moderate so the pedal does not glare on a dark stage.
constexpr uint8_t LED_BRIGHTNESS = 90;

// LED positions counted from the left, 0-based.
constexpr uint8_t LED_LEFT = 0;
constexpr uint8_t LED_CENTER = 2;
constexpr uint8_t LED_RIGHT = 4;
constexpr uint8_t CENTER_HOLD_LEDS[] = {1, 2, 3};

const CRGB COLOR_SEARCHING = CRGB(0, 0, 255);
const CRGB COLOR_CONNECTED = CRGB(0, 60, 0);
const CRGB COLOR_FLASH = CRGB(255, 0, 0);

// Searching snake: the head is full blue, the tail fades out behind it.
constexpr uint8_t SNAKE_LEVELS[] = {255, 90, 25};
constexpr uint8_t SNAKE_LENGTH = sizeof(SNAKE_LEVELS);
constexpr uint32_t SNAKE_STEP_MS = 120;
constexpr uint32_t FLASH_MS = 180;
// Same as the app: holding the centre flap this long mutes (level page) or goes back (list).
constexpr uint32_t CENTER_HOLD_MS = 650;
constexpr uint32_t DEBOUNCE_MS = 20;
constexpr uint32_t SCAN_TIME_MS = 3000;
// Phones weaker than this are ignored so the pedal does not grab a bandmate's phone across the stage.
constexpr int MIN_RSSI = -80;

// ---------------------------------------------------------------- BLE protocol (shared with the app)
static const char* SERVICE_UUID = "8d53dc1d-1db7-4cd3-868b-8a527460aa84";
static const char* COMMAND_UUID = "8d53dc1d-1db7-4cd3-868b-8a527460aa85";
constexpr uint8_t BUTTON_LEFT = 1;
constexpr uint8_t BUTTON_CENTER = 2;
constexpr uint8_t BUTTON_RIGHT = 3;

// ---------------------------------------------------------------- State
enum class LinkState : uint8_t { Searching, Connecting, Connected };

struct Flap {
  uint8_t pin;
  uint8_t buttonId;
  uint8_t ledIndex;
  bool pressed;
  bool lastRaw;
  uint32_t lastChangeMs;
  uint32_t pressedAtMs;
  bool holdShown;
};

Flap flaps[] = {
    {PIN_LEFT, BUTTON_LEFT, LED_LEFT, false, false, 0, 0, false},
    {PIN_CENTER, BUTTON_CENTER, LED_CENTER, false, false, 0, 0, false},
    {PIN_RIGHT, BUTTON_RIGHT, LED_RIGHT, false, false, 0, 0, false},
};

CRGB leds[LED_COUNT];
// Per LED position: millis() when its red flash ends.
uint32_t flashUntilMs[LED_COUNT] = {};

volatile LinkState linkState = LinkState::Searching;

#if WING_PEDAL_SIMULATION
// ---------------------------------------------------------------- Simulated phone (Wokwi)
constexpr uint8_t PIN_SIM_PHONE = 14;
constexpr uint32_t SIM_SEARCH_MS = 3000;
bool simPhoneInRange = true;
bool simButtonWasDown = false;
uint32_t simButtonChangedMs = 0;
uint32_t simSearchStartedMs = 0;

void startScan() {
  linkState = LinkState::Searching;
  simSearchStartedMs = millis();
}

void sendEdge(const Flap& flap) {
  if (linkState != LinkState::Connected) return;
  Serial.printf("[SIM] BLE write %02X %02X\n", flap.buttonId, flap.pressed ? 1 : 0);
}

void simulatePhone(uint32_t now) {
  bool down = digitalRead(PIN_SIM_PHONE) == LOW;
  if (down != simButtonWasDown && now - simButtonChangedMs >= 50) {
    simButtonWasDown = down;
    simButtonChangedMs = now;
    if (down) {
      simPhoneInRange = !simPhoneInRange;
      Serial.printf("[SIM] phone %s\n", simPhoneInRange ? "in range" : "out of range");
      if (!simPhoneInRange && linkState == LinkState::Connected) {
        Serial.println("[SIM] BLE disconnected");
        startScan();
      }
    }
  }
  if (linkState == LinkState::Searching && simPhoneInRange && now - simSearchStartedMs >= SIM_SEARCH_MS) {
    linkState = LinkState::Connected;
    Serial.println("[SIM] Pedal connected to WING Monitor");
  }
}
#else
volatile bool connectRequested = false;
volatile bool restartScanRequested = false;
const NimBLEAdvertisedDevice* bestPhone = nullptr;
int bestRssi = -127;
NimBLEClient* client = nullptr;
NimBLERemoteCharacteristic* commandCharacteristic = nullptr;

// ---------------------------------------------------------------- BLE callbacks
class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice* device) override {
    if (!device->isAdvertisingService(NimBLEUUID(SERVICE_UUID))) return;
    int rssi = device->getRSSI();
    Serial.printf("Phone with WING Monitor: %s RSSI %d\n", device->getAddress().toString().c_str(), rssi);
    if (rssi >= MIN_RSSI && rssi > bestRssi) {
      bestRssi = rssi;
      bestPhone = device;
    }
  }

  // Collect all phones for SCAN_TIME_MS, then connect to the nearest one.
  void onScanEnd(const NimBLEScanResults& results, int reason) override {
    if (bestPhone != nullptr) {
      connectRequested = true;
    } else {
      restartScanRequested = true;
    }
  }
} scanCallbacks;

class ClientCallbacks : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient* connectedClient) override {
    Serial.printf("BLE connected to %s\n", connectedClient->getPeerAddress().toString().c_str());
  }

  void onDisconnect(NimBLEClient* disconnectedClient, int reason) override {
    Serial.printf("BLE disconnected, reason %d\n", reason);
    commandCharacteristic = nullptr;
    linkState = LinkState::Searching;
    restartScanRequested = true;
  }
} clientCallbacks;

// ---------------------------------------------------------------- BLE helpers
void startScan() {
  bestPhone = nullptr;
  bestRssi = -127;
  linkState = LinkState::Searching;
  NimBLEDevice::getScan()->start(SCAN_TIME_MS, false, true);
}

bool connectToPhone() {
  if (bestPhone == nullptr) return false;
  if (client == nullptr) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(&clientCallbacks, false);
    client->setConnectionParams(12, 12, 0, 150);
    client->setConnectTimeout(5000);
  }
  if (!client->connect(bestPhone)) {
    Serial.println("Connection failed");
    return false;
  }
  NimBLERemoteService* service = client->getService(SERVICE_UUID);
  NimBLERemoteCharacteristic* characteristic =
      service != nullptr ? service->getCharacteristic(COMMAND_UUID) : nullptr;
  if (characteristic == nullptr || !characteristic->canWrite()) {
    Serial.println("Pedal service not found on the phone");
    client->disconnect();
    return false;
  }
  commandCharacteristic = characteristic;
  return true;
}

void sendEdge(const Flap& flap) {
  if (linkState != LinkState::Connected || commandCharacteristic == nullptr) return;
  uint8_t payload[2] = {flap.buttonId, static_cast<uint8_t>(flap.pressed ? 1 : 0)};
  // Write with response, like the app expects: one write per press and per release.
  if (!commandCharacteristic->writeValue(payload, sizeof(payload), true)) {
    Serial.println("BLE write failed");
  }
}
#endif

// ---------------------------------------------------------------- Flaps
void flash(uint8_t position, uint32_t now) {
  flashUntilMs[position] = now + FLASH_MS;
}

void pollFlaps(uint32_t now) {
  for (Flap& flap : flaps) {
    bool raw = digitalRead(flap.pin) == LOW;
    if (raw != flap.lastRaw) {
      flap.lastRaw = raw;
      flap.lastChangeMs = now;
    }
    if (raw != flap.pressed && now - flap.lastChangeMs >= DEBOUNCE_MS) {
      flap.pressed = raw;
      Serial.printf("Flap %u %s\n", flap.buttonId, flap.pressed ? "down" : "up");
      if (flap.pressed) {
        flap.pressedAtMs = now;
        flap.holdShown = false;
        flash(flap.ledIndex, now);
      }
      sendEdge(flap);
    }
    if (flap.buttonId == BUTTON_CENTER && flap.pressed && !flap.holdShown &&
        now - flap.pressedAtMs >= CENTER_HOLD_MS) {
      flap.holdShown = true;
      Serial.printf("Flap %u held\n", flap.buttonId);
      for (uint8_t position : CENTER_HOLD_LEDS) flash(position, now);
    }
  }
}

// ---------------------------------------------------------------- LEDs
uint8_t ledFor(uint8_t position) {
  return LED_REVERSED ? LED_COUNT - 1 - position : position;
}

void renderSnake(uint32_t now) {
  fill_solid(leds, LED_COUNT, CRGB::Black);
  // The head enters at the left end, the whole snake crawls out at the right end, one dark step, repeat.
  int head = (now / SNAKE_STEP_MS) % (LED_COUNT + SNAKE_LENGTH);
  for (uint8_t segment = 0; segment < SNAKE_LENGTH; segment++) {
    int position = head - segment;
    if (position < 0 || position >= LED_COUNT) continue;
    CRGB color = COLOR_SEARCHING;
    leds[ledFor(position)] = color.nscale8(SNAKE_LEVELS[segment]);
  }
}

void renderLeds(uint32_t now) {
  if (linkState == LinkState::Connected) {
    fill_solid(leds, LED_COUNT, COLOR_CONNECTED);
  } else {
    renderSnake(now);
  }
  // Flashes show even without a phone, which helps when checking the wiring.
  for (uint8_t position = 0; position < LED_COUNT; position++) {
    if (static_cast<int32_t>(flashUntilMs[position] - now) > 0) leds[ledFor(position)] = COLOR_FLASH;
  }
  FastLED.show();
}

// ---------------------------------------------------------------- Arduino
void setup() {
  Serial.begin(115200);

  for (Flap& flap : flaps) {
    pinMode(flap.pin, INPUT_PULLUP);
    flap.lastRaw = digitalRead(flap.pin) == LOW;
    flap.pressed = flap.lastRaw;
    // A flap already held at power-on sent no press, so it gets no hold flash either.
    flap.holdShown = flap.pressed;
  }

  FastLED.addLeds<WS2812B, PIN_LED_DATA, GRB>(leds, LED_COUNT);
  FastLED.setBrightness(LED_BRIGHTNESS);
  renderLeds(millis());

#if WING_PEDAL_SIMULATION
  pinMode(PIN_SIM_PHONE, INPUT_PULLUP);
  Serial.println("WING pedal ready (Wokwi simulation, no Bluetooth)");
#else
  NimBLEDevice::init("WING Pedal");
  NimBLEDevice::setPower(3);
  NimBLEScan* scan = NimBLEDevice::getScan();
  scan->setScanCallbacks(&scanCallbacks, false);
  scan->setInterval(100);
  scan->setWindow(100);
  scan->setActiveScan(true);
  Serial.println("WING pedal ready");
#endif
  startScan();
}

void loop() {
#if WING_PEDAL_SIMULATION
  simulatePhone(millis());
#else
  if (connectRequested) {
    connectRequested = false;
    linkState = LinkState::Connecting;
    if (connectToPhone()) {
      linkState = LinkState::Connected;
      Serial.println("Pedal connected to WING Monitor");
    } else {
      startScan();
    }
  }

  if (restartScanRequested) {
    restartScanRequested = false;
    if (linkState != LinkState::Connected) startScan();
  }
#endif

  // Read the clock after the BLE work above, which can block while connecting.
  uint32_t now = millis();
  pollFlaps(now);
  renderLeds(now);
  delay(5);
}

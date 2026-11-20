/*
  Kilowhat - ESP32 DevKit V1 hardware simulation
  ------------------------------------------------
  Simulates the PZEM-004T readings in firmware while using real:
    - MFRC522 / RC522 RFID reader
    - One pushbutton (plug / remove simulation)
    - BLE GATT notifications for live telemetry (multi-client)
    - Wi-Fi HTTP POST for completed sessions

  Simulation behavior:
    - Every simulated plug is ACTIVE and lasts at most 60 seconds.
    - RFID identification window: 5 seconds.
    - If no tag is read within 5 seconds, appliance ID becomes "unknown_id".
    - A completed session closes 5 seconds after unplugging / power drop.

  IMPORTANT:
  - This sketch is for LOW-VOLTAGE breadboard testing only.
  - Do not connect mains voltage to the breadboard or ESP32.
  - It assumes an MFRC522-compatible 13.56 MHz RFID reader.
  - Install the MFRC522 library by GithubCommunity (or miguelbalboa).
    WiFi, HTTPClient, SPI, and the ESP32 BLE library come with the
    ESP32 Arduino board package.
*/

#include <WiFi.h>
#include <HTTPClient.h>
#include <WiFiClient.h>
#include <SPI.h>
#include <time.h>
#include <math.h>
#include <new>

#include <MFRC522.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ---------------------------------------------------------------------------
// USER CONFIGURATION - CHANGE THESE VALUES
// ---------------------------------------------------------------------------
const char *WIFI_SSID     = "HUAWEI-Fhh8";
const char *WIFI_PASSWORD = "DkXgm4eP";

// Use your COMPUTER'S LAN IPv4 address here, not localhost / 127.0.0.1.
// Example: http://192.168.1.100:8080/api/sessions
const char *BACKEND_URL = "http://192.168.18.68:8080/api/sessions";

const char *OUTLET_ID = "OUTLET_NODE_01";
const char *UNKNOWN_ID = "unknown_id";
const char *NO_APPLIANCE = "NONE";

// ---------------------------------------------------------------------------
// BLE STREAM LIMIT - CHANGE THIS VALUE
// Maximum number of phones that may be connected and receiving the live
// telemetry stream at the same time. Extra phones that try to connect while
// the limit is reached are disconnected immediately.
//   - Minimum: 1
//   - Hardware ceiling: 9 (ESP32 Bluedroid ACL limit)
//   - Practical: 2-3. Every extra client costs RAM and shares the radio with
//     Wi-Fi, so higher values may delay HTTP POSTs or drop notifications.
// ---------------------------------------------------------------------------
#define MAX_BLE_STREAMS 3

static_assert(MAX_BLE_STREAMS >= 1, "MAX_BLE_STREAMS must be at least 1");
static_assert(MAX_BLE_STREAMS <= 9, "MAX_BLE_STREAMS cannot exceed 9 on ESP32");

// ---------------------------------------------------------------------------
// PIN ASSIGNMENTS - ESP32 DEVKIT V1 (ONE HEADER SIDE ONLY)
// ---------------------------------------------------------------------------
// This layout is intended for the common 30-pin DOIT-style ESP32 DevKit V1.
// Verify the printed GPIO labels on your exact board before wiring.
// The RC522 module pin labelled SDA is used as SPI chip-select (SS).
// SPI is explicitly remapped so every signal is on the same header side.
static const uint8_t RFID_SS_PIN   = 21;
static const uint8_t RFID_RST_PIN  = 22;
static const uint8_t RFID_SCK_PIN  = 18;
static const uint8_t RFID_MISO_PIN = 19;
static const uint8_t RFID_MOSI_PIN = 23;

// One button: connect GPIO 4 to GND when pressed.
static const uint8_t BUTTON_PLUG_PIN = 4;

// ---------------------------------------------------------------------------
// SIMULATOR PARAMETERS - kept consistent with the Python simulator / README
// ---------------------------------------------------------------------------
static const float NOISE_GATE_W = 4.0f;
static const float NOMINAL_VOLTAGE = 230.0f;
static const float PZEM_MIN_V = 80.0f;
static const float PZEM_MAX_V = 260.0f;
static const float MIN_SESSION_WH = 0.01f;
static const uint32_t RFID_WAIT_MS = 5000;
static const uint32_t COOLDOWN_MS = 5000;
static const uint32_t PLUG_DURATION_MS = 60000; // fixed 60-second ACTIVE simulation
static const uint32_t SAMPLE_INTERVAL_MS = 1000;
static const uint32_t DEBOUNCE_MS = 35;

// ---------------------------------------------------------------------------
// BLE GATT IDs
// The Android app should scan for this service and subscribe to the notify
// characteristic. Each notification carries the simulated PZEM snapshot plus
// outlet/RFID identity. The legacy "watts" key remains for app compatibility.
// ---------------------------------------------------------------------------
static const char *BLE_DEVICE_NAME = "Kilowhat_OUTLET_NODE_01";
static const char *BLE_SERVICE_UUID = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
static const char *BLE_TELEMETRY_UUID = "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

// ---------------------------------------------------------------------------
// HARDWARE OBJECTS
// ---------------------------------------------------------------------------
MFRC522 rfid(RFID_SS_PIN, RFID_RST_PIN);

BLEServer *bleServer = nullptr;
BLECharacteristic *telemetryCharacteristic = nullptr;

// bleConnected is true while at least one phone is connected.
// bleClientCount is the number of accepted (streaming) phones.
// bleClientIds holds the connection IDs of the accepted phones.
volatile bool bleConnected = false;
volatile uint8_t bleClientCount = 0;
static const uint16_t BLE_SLOT_FREE = 0xFFFF;
volatile uint16_t bleClientIds[MAX_BLE_STREAMS];

// ---------------------------------------------------------------------------
// BUTTON DEBOUNCE
// ---------------------------------------------------------------------------
struct DebouncedButton {
  uint8_t pin;
  bool lastRaw;
  bool stableState;
  uint32_t lastChangeMs;
};

DebouncedButton plugButton = {BUTTON_PLUG_PIN, HIGH, HIGH, 0};

bool buttonPressed(DebouncedButton &button, uint32_t now) {
  bool raw = digitalRead(button.pin);
  if (raw != button.lastRaw) {
    button.lastRaw = raw;
    button.lastChangeMs = now;
  }

  if ((uint32_t)(now - button.lastChangeMs) >= DEBOUNCE_MS &&
      raw != button.stableState) {
    button.stableState = raw;
    return button.stableState == LOW; // only report the press edge
  }
  return false;
}

// ---------------------------------------------------------------------------
// FIXED SIMULATION CONFIGURATION AND CURRENT PLUG STATE
// ---------------------------------------------------------------------------
bool plugged = false;
bool rfidPending = false;
bool rfidResolved = false;
String activeRfid = NO_APPLIANCE;
uint32_t plugStartedMs = 0;
uint32_t plugDeadlineMs = 0;
uint32_t rfidDeadlineMs = 0;
int lastRfidCountdownShown = -1;

// ---------------------------------------------------------------------------
// ENERGY ODOMETER AND SESSION STATE
// Energy is simulated as in the Python VirtualPZEM. It is held in RAM only.
// ---------------------------------------------------------------------------
float totalEnergyWh = 0.0f;
float lastWatts = 0.0f;
float lastVoltage = NOMINAL_VOLTAGE;
float lastCurrent = 0.0f;
float lastFrequency = 60.0f;
float lastPowerFactor = 0.0f;
float lastRawWatts = 0.0f;

bool sessionOpen = false;
String sessionRfid;
float sessionStartWh = 0.0f;
String sessionStartTimestamp;
String sessionEndTimestamp;
float sessionEndWh = 0.0f;
bool cooldownActive = false;
uint32_t cooldownStartedMs = 0;

uint32_t lastSampleMs = 0;
uint32_t lastWifiRetryMs = 0;
bool ntpConfigured = false;

// ---------------------------------------------------------------------------
// SMALL HELPERS
// ---------------------------------------------------------------------------
String jsonEscape(const String &input) {
  String out;
  out.reserve(input.length() + 8);
  for (size_t i = 0; i < input.length(); ++i) {
    char c = input[i];
    if (c == '"' || c == '\\') out += '\\';
    if (c == '\n') out += "\\n";
    else if (c == '\r') out += "\\r";
    else out += c;
  }
  return out;
}

String isoNow() {
  time_t epoch = time(nullptr);
  // Do not fabricate a date if NTP has not synchronized yet.
  if (epoch < 1700000000) return "";

  struct tm timeInfo;
  localtime_r(&epoch, &timeInfo);
  char buffer[32];
  // Philippines time: UTC+08:00, no daylight-saving time.
  strftime(buffer, sizeof(buffer), "%Y-%m-%dT%H:%M:%S+08:00", &timeInfo);
  return String(buffer);
}

float gaussianRandom(float mean, float standardDeviation) {
  // Box-Muller transform. Avoid log(0).
  float u1 = (random(1, 1000000) / 1000000.0f);
  float u2 = (random(1, 1000000) / 1000000.0f);
  float z0 = sqrtf(-2.0f * logf(u1)) * cosf(2.0f * PI * u2);
  return mean + z0 * standardDeviation;
}

uint32_t crc32String(const String &value) {
  uint32_t crc = 0xFFFFFFFFUL;
  for (size_t i = 0; i < value.length(); ++i) {
    crc ^= (uint8_t)value[i];
    for (uint8_t bit = 0; bit < 8; ++bit) {
      if (crc & 1) crc = (crc >> 1) ^ 0xEDB88320UL;
      else crc >>= 1;
    }
  }
  return ~crc;
}

String currentTelemetryId() {
  if (plugged && rfidResolved) return activeRfid;
  return String(NO_APPLIANCE);
}

// ---------------------------------------------------------------------------
// BLE CLIENT SLOT HELPERS
// ---------------------------------------------------------------------------
void resetBleClientSlots() {
  for (uint8_t i = 0; i < MAX_BLE_STREAMS; ++i) bleClientIds[i] = BLE_SLOT_FREE;
  bleClientCount = 0;
  bleConnected = false;
}

// Returns true if the connection was stored in a free slot.
bool addBleClient(uint16_t connId) {
  for (uint8_t i = 0; i < MAX_BLE_STREAMS; ++i) {
    if (bleClientIds[i] == BLE_SLOT_FREE) {
      bleClientIds[i] = connId;
      bleClientCount++;
      bleConnected = true;
      return true;
    }
  }
  return false;
}

// Returns true if the connection was one of the accepted clients.
bool removeBleClient(uint16_t connId) {
  for (uint8_t i = 0; i < MAX_BLE_STREAMS; ++i) {
    if (bleClientIds[i] == connId) {
      bleClientIds[i] = BLE_SLOT_FREE;
      if (bleClientCount > 0) bleClientCount--;
      bleConnected = (bleClientCount > 0);
      return true;
    }
  }
  return false;
}

// ---------------------------------------------------------------------------
// BLE SERVER CALLBACKS
// ---------------------------------------------------------------------------
class KilowhatServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    uint16_t connId = param->connect.conn_id;

    if (!addBleClient(connId)) {
      // Limit reached: refuse the extra phone. It stays out of the slot list,
      // so its later onDisconnect does not change the client count.
      Serial.printf("[HW ][%d BLE] stream limit reached (%u/%u); rejecting extra client\n",
                    bleConnected ? 1 : 0, bleClientCount, (unsigned)MAX_BLE_STREAMS);
      server->disconnect(connId);
      return;
    }

    Serial.printf("[HW ][1 BLE] phone connected to %s (%u/%u streams)\n",
                  OUTLET_ID, bleClientCount, (unsigned)MAX_BLE_STREAMS);

    // Keep advertising until the configured limit is reached.
    if (bleClientCount < MAX_BLE_STREAMS) {
      BLEDevice::startAdvertising();
    } else {
      Serial.println("[BLE ] stream limit reached; advertising paused");
    }
  }

  void onDisconnect(BLEServer *server, esp_ble_gatts_cb_param_t *param) override {
    uint16_t connId = param->disconnect.conn_id;

    if (removeBleClient(connId)) {
      Serial.printf("[HW ][%d BLE] phone disconnected (%u/%u streams)\n",
                    bleConnected ? 1 : 0, bleClientCount, (unsigned)MAX_BLE_STREAMS);
      BLEDevice::startAdvertising(); // a slot is free again
    }
    // A rejected client disconnecting needs no further action.
  }
};

void startBleServer() {
  resetBleClientSlots();

  BLEDevice::init(BLE_DEVICE_NAME);
  BLEDevice::setMTU(247);

  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new KilowhatServerCallbacks());

  BLEService *service = bleServer->createService(BLE_SERVICE_UUID);
  telemetryCharacteristic = service->createCharacteristic(
    BLE_TELEMETRY_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
  );
  telemetryCharacteristic->addDescriptor(new BLE2902());
  telemetryCharacteristic->setValue("{}");
  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLE_SERVICE_UUID);
  advertising->setScanResponse(true);
  BLEDevice::startAdvertising();

  Serial.printf("[BLE ] advertising as %s (max %u simultaneous streams)\n",
                BLE_DEVICE_NAME, (unsigned)MAX_BLE_STREAMS);
  Serial.printf("[BLE ] service UUID: %s\n", BLE_SERVICE_UUID);
  Serial.printf("[BLE ] telemetry UUID: %s\n", BLE_TELEMETRY_UUID);
}

String makeTelemetryJson() {
  String id = jsonEscape(currentTelemetryId());
  String json = "{\"outlet_id\":\"" + String(OUTLET_ID) +
                "\",\"active_appliance_rfid\":\"" + id +
                "\",\"voltage\":" + String(lastVoltage, 1) +
                ",\"current\":" + String(lastCurrent, 3) +
                ",\"power\":" + String(lastWatts, 1) +
                ",\"watts\":" + String(lastWatts, 1) +
                ",\"energy\":" + String(totalEnergyWh, 3) +
                ",\"frequency\":" + String(lastFrequency, 1) +
                ",\"power_factor\":" + String(lastPowerFactor, 2) + "}";
  return json;
}

void notifyBleTelemetry() {
  if (telemetryCharacteristic == nullptr) return;
  String json = makeTelemetryJson();
  telemetryCharacteristic->setValue(json.c_str()); // keep latest value readable even while disconnected
  // notify() sends to every connected client that subscribed.
  if (bleConnected) telemetryCharacteristic->notify();
}

// ---------------------------------------------------------------------------
// WI-FI SESSION POST
// The POST is sent on a separate FreeRTOS task so a slow backend does not
// freeze button handling, RFID polling, or BLE notifications.
// ---------------------------------------------------------------------------
struct PostJob {
  String payload;
};

void printPayload(const String &payload) {
  Serial.println("[WIFI] session payload:");
  Serial.println(payload);
}

void sessionPostTask(void *parameter) {
  PostJob *job = static_cast<PostJob *>(parameter);
  String payload = job->payload;
  delete job;

  if (WiFi.status() != WL_CONNECTED) {
    Serial.println("[WIFI] backend not contacted: Wi-Fi is disconnected");
    printPayload(payload);
    vTaskDelete(nullptr);
    return;
  }

  WiFiClient client;
  HTTPClient http;
  http.setTimeout(5000);

  Serial.printf("[WIFI] pushing session summary -> %s\n", BACKEND_URL);
  if (!http.begin(client, BACKEND_URL)) {
    Serial.println("[WIFI] could not initialize HTTP connection");
    printPayload(payload);
    vTaskDelete(nullptr);
    return;
  }

  http.addHeader("Content-Type", "application/json");
  int responseCode = http.POST((uint8_t *)payload.c_str(), payload.length());

  if (responseCode >= 200 && responseCode < 300) {
    Serial.printf("[WIFI] backend accepted session (HTTP %d)\n", responseCode);
  } else {
    Serial.printf("[WIFI] backend replied / connection failed (code %d)\n", responseCode);
    if (responseCode > 0) {
      Serial.println("[WIFI] response body: " + http.getString());
    }
    printPayload(payload);
  }

  http.end();
  vTaskDelete(nullptr);
}

void pushSession(const String &rfidId, const String &startTimestamp,
                 const String &endTimestamp, float consumedWh) {
  if (startTimestamp.length() == 0 || endTimestamp.length() == 0) {
    Serial.println("[WIFI] session not posted because the ESP32 clock was not synchronized.");
    Serial.println("[WIFI] Check Wi-Fi/internet and NTP, then test again.");
    return;
  }

  String payload = "{\"outlet_id\":\"" + String(OUTLET_ID) +
                   "\",\"appliance_rfid\":\"" + jsonEscape(rfidId) +
                   "\",\"start_timestamp\":\"" + startTimestamp +
                   "\",\"end_timestamp\":\"" + endTimestamp +
                   "\",\"total_wh_consumed\":" + String(consumedWh, 4) + "}";

  PostJob *job = new (std::nothrow) PostJob{payload};
  if (job == nullptr) {
    Serial.println("[WIFI] could not allocate a POST job; payload follows");
    printPayload(payload);
    return;
  }

  BaseType_t result = xTaskCreatePinnedToCore(
    sessionPostTask,
    "sessionPOST",
    8192,
    job,
    1,
    nullptr,
    0
  );

  if (result != pdPASS) {
    Serial.println("[WIFI] could not start POST task; payload follows");
    printPayload(payload);
    delete job;
  }
}

// ---------------------------------------------------------------------------
// SESSION STATE MACHINE
// ---------------------------------------------------------------------------
void beginCooldown(uint32_t now) {
  if (!sessionOpen || cooldownActive) return;
  cooldownActive = true;
  cooldownStartedMs = now;
  sessionEndTimestamp = isoNow();
  sessionEndWh = totalEnergyWh;
  Serial.printf("[HW ][%d BLE] power dropped to 0 W, starting %lu-second cooldown\n",
                bleConnected ? 1 : 0, (unsigned long)(COOLDOWN_MS / 1000));
}

void clearSessionState() {
  sessionOpen = false;
  sessionRfid = "";
  sessionStartWh = 0.0f;
  sessionStartTimestamp = "";
  sessionEndTimestamp = "";
  sessionEndWh = 0.0f;
  cooldownActive = false;
  cooldownStartedMs = 0;
}

void finalizeSession() {
  if (!sessionOpen) return;

  float endingWh = cooldownActive ? sessionEndWh : totalEnergyWh;
  float deltaWh = endingWh - sessionStartWh;
  if (deltaWh < 0.0f) deltaWh = 0.0f;

  Serial.printf("[HW ][%d BLE] session closed (%s) | start %.4f Wh -> end %.4f Wh | delta %.4f Wh\n",
                bleConnected ? 1 : 0, sessionRfid.c_str(),
                sessionStartWh, endingWh, deltaWh);

  if (deltaWh < MIN_SESSION_WH) {
    Serial.printf("[HW ][%d BLE] delta below %.2f Wh, session discarded (not sent)\n",
                  bleConnected ? 1 : 0, MIN_SESSION_WH);
  } else {
    pushSession(sessionRfid, sessionStartTimestamp, sessionEndTimestamp, deltaWh);
  }

  clearSessionState();
}

void startSession(const String &tag) {
  sessionOpen = true;
  sessionRfid = tag;
  sessionStartWh = totalEnergyWh;
  sessionStartTimestamp = isoNow();
  sessionEndTimestamp = "";
  sessionEndWh = 0.0f;
  cooldownActive = false;
  cooldownStartedMs = 0;

  Serial.printf("[HW ][%d BLE] load detected (> %.1f W) with id %s, session started | starting Wh locked in RAM: %.4f\n",
                bleConnected ? 1 : 0, NOISE_GATE_W,
                sessionRfid.c_str(), sessionStartWh);
  if (sessionStartTimestamp.length() == 0) {
    Serial.println("[HW ] warning: clock is not synchronized; this session cannot be POSTed yet.");
  }
}

// Called only once a real card is read or the 5-second wait expires.
void resolveRfid(const String &tag, bool wasTimeout) {
  if (!plugged || !rfidPending) return;

  if (sessionOpen && cooldownActive) {
    if (tag == sessionRfid) {
      cooldownActive = false;
      sessionEndTimestamp = "";
      sessionEndWh = 0.0f;
      Serial.printf("[HW ][%d BLE] same appliance (%s) plugged back in, session continues\n",
                    bleConnected ? 1 : 0, tag.c_str());
    } else {
      Serial.printf("[HW ][%d BLE] different appliance (%s) detected during cooldown; closing previous session (%s) now\n",
                    bleConnected ? 1 : 0, tag.c_str(), sessionRfid.c_str());
      finalizeSession();
    }
  }

  activeRfid = tag;
  rfidPending = false;
  rfidResolved = true;
  rfid.PCD_AntennaOff(); // stop RF polling after identification / timeout

  if (wasTimeout) {
    Serial.printf("[HW ][%d BLE] no RFID tag found in 5 seconds; assigning %s\n",
                  bleConnected ? 1 : 0, UNKNOWN_ID);
  } else {
    Serial.printf("[HW ][%d BLE] RFID scanned: %s\n", bleConnected ? 1 : 0, tag.c_str());
  }
}

// ---------------------------------------------------------------------------
// PLUG / REMOVE CONTROLS
// ---------------------------------------------------------------------------
void startPlugSimulation(uint32_t now) {
  if (plugged) {
    Serial.println("[HW ] outlet already occupied; plug command ignored");
    return;
  }

  plugged = true;
  rfidPending = true;
  rfidResolved = false;
  activeRfid = NO_APPLIANCE;

  plugStartedMs = now;
  plugDeadlineMs = now + PLUG_DURATION_MS;
  rfidDeadlineMs = now + RFID_WAIT_MS;
  rfid.PCD_AntennaOn(); // RFID field is enabled only during the identification window
  lastRfidCountdownShown = -1;

  Serial.println("[HW ] simulated appliance plugged in | ACTIVE | 60-second maximum duration");
  Serial.println("[HW ] RFID reader enabled; present the appliance tag now (5-second window)");
}

void removePlugSimulation(const char *reason) {
  if (!plugged) {
    Serial.println("[HW ] nothing is plugged in, nothing to remove");
    return;
  }

  bool wasWaitingForTag = rfidPending;
  plugged = false;
  rfidPending = false;
  rfidResolved = false;
  activeRfid = NO_APPLIANCE;

  Serial.printf("[HW ][%d BLE] %s\n", bleConnected ? 1 : 0, reason);
  if (wasWaitingForTag) {
    rfid.PCD_AntennaOff();
    Serial.println("[HW ] RFID wait stopped early because the simulated appliance was removed");
  }

  // If a prior session was already cooling down, this was only a new plug
  // attempt. Leave its original cooldown timestamp untouched.
  if (sessionOpen && !cooldownActive) {
    beginCooldown(millis());
  }
}

void handleRfidReader(uint32_t now) {
  if (!plugged || !rfidPending) return;

  if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
    String tag;
    tag.reserve(rfid.uid.size * 2);
    for (byte i = 0; i < rfid.uid.size; ++i) {
      if (rfid.uid.uidByte[i] < 0x10) tag += "0";
      tag += String(rfid.uid.uidByte[i], HEX);
    }
    tag.toUpperCase();

    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
    resolveRfid(tag, false);
    return;
  }

  if ((int32_t)(now - rfidDeadlineMs) >= 0) {
    resolveRfid(String(UNKNOWN_ID), true);
    return;
  }

  int remaining = (int)((rfidDeadlineMs - now + 999UL) / 1000UL);
  if (remaining != lastRfidCountdownShown) {
    lastRfidCountdownShown = remaining;
    Serial.printf("[HW ][%d BLE] RFID wait: %d second(s) remaining\n",
                  bleConnected ? 1 : 0, remaining);
  }
}

// ---------------------------------------------------------------------------
// SIMULATED PZEM READING
// Uses the deterministic per-tag power profile from the Python simulator.
// Empty outlet has no stray-current spikes (surge/stray behavior omitted).
// ---------------------------------------------------------------------------
void simulateMeterReading(uint32_t now, float dtSeconds) {
  float rawWatts = 0.0f;

  if (plugged && rfidResolved) {
    uint32_t seed = crc32String(activeRfid);
    float baseWatts = 60.0f + (float)(seed % 541UL); // 60..600 W
    float phase = (float)((seed >> 8) % 628UL) / 100.0f;

    // All simulated appliances are always ACTIVE; no standby mode exists.
    float elapsedSeconds = (uint32_t)(now - plugStartedMs) / 1000.0f;
    float drift = 1.0f + 0.04f * sinf(elapsedSeconds / 7.0f + phase);
    float jitter = gaussianRandom(0.0f, baseWatts * 0.008f);
    rawWatts = max(0.0f, baseWatts * drift + jitter);
  }

  float watts = (rawWatts < NOISE_GATE_W) ? 0.0f : rawWatts;
  float voltage = gaussianRandom(NOMINAL_VOLTAGE, 0.8f);
  voltage = min(PZEM_MAX_V, max(PZEM_MIN_V, voltage));

  // Simulated PZEM-style auxiliary readings. For a non-zero load, choose a
  // plausible power factor and derive current from P = V * I * PF. Frequency
  // remains close to the Philippine nominal 60 Hz supply frequency.
  float powerFactor = watts > 0.0f ? random(85, 100) / 100.0f : 0.0f;
  float current = watts > 0.0f ? watts / (voltage * powerFactor) : 0.0f;
  float frequency = gaussianRandom(60.0f, 0.03f);
  frequency = min(60.2f, max(59.8f, frequency));

  lastRawWatts = rawWatts;
  lastWatts = watts;
  lastVoltage = voltage;
  lastCurrent = current;
  lastFrequency = frequency;
  lastPowerFactor = powerFactor;

  // Session state machine. Only a resolved RFID ID may start/extend a session.
  bool identifiedLoad = plugged && rfidResolved;

  if (!sessionOpen) {
    if (identifiedLoad && watts > 0.0f) {
      startSession(activeRfid);
    }
  } else if (identifiedLoad && watts > 0.0f) {
    if (cooldownActive) {
      cooldownActive = false;
      sessionEndTimestamp = "";
      sessionEndWh = 0.0f;
      Serial.printf("[HW ][%d BLE] power resumed, cooldown cancelled\n", bleConnected ? 1 : 0);
    }
  } else if (!cooldownActive) {
    beginCooldown(now);
  }

  // The virtual meter's odometer advances only with gated watts.
  totalEnergyWh += watts * dtSeconds / 3600.0f;

  // A session closes only while the outlet is idle. A pending RFID scan pauses
  // cooldown closure until its tag (or unknown_id timeout) is resolved.
  if (sessionOpen && cooldownActive && !plugged && !rfidPending &&
      (uint32_t)(now - cooldownStartedMs) >= COOLDOWN_MS) {
    finalizeSession();
  }

  String idForLog = currentTelemetryId();
  Serial.printf("[HW ][%d BLE] tick | rfid=%s | power %.1f W | voltage %.1f V | current %.3f A | energy %.4f Wh | frequency %.1f Hz | PF %.2f",
                bleConnected ? 1 : 0,
                idForLog.c_str(), lastWatts, lastVoltage, lastCurrent,
                totalEnergyWh, lastFrequency, lastPowerFactor);
  if (identifiedLoad && lastRawWatts > 0.0f && lastRawWatts < NOISE_GATE_W) {
    Serial.printf(" | noise gate: %.1f W -> 0 W", lastRawWatts);
  }
  Serial.println();

  notifyBleTelemetry();
}

// ---------------------------------------------------------------------------
// SETUP AND MAIN LOOP
// ---------------------------------------------------------------------------
void connectWiFi() {
  if (String(WIFI_SSID) == "YOUR_WIFI_NAME") {
    Serial.println("[WIFI] Set WIFI_SSID and WIFI_PASSWORD in the sketch first.");
    return;
  }

  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  Serial.printf("[WIFI] connecting to %s", WIFI_SSID);

  uint32_t started = millis();
  while (WiFi.status() != WL_CONNECTED && (uint32_t)(millis() - started) < 15000UL) {
    delay(300);
    Serial.print(".");
  }
  Serial.println();

  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("[WIFI] connected; ESP32 IP: %s\n", WiFi.localIP().toString().c_str());
    // Philippines: UTC+8. NTP is used to create API-compatible timestamps.
    configTime(8 * 3600, 0, "pool.ntp.org", "time.nist.gov");
    ntpConfigured = true;
    Serial.print("[TIME] waiting for NTP time");
    uint32_t timeStarted = millis();
    while (time(nullptr) < 1700000000 && (uint32_t)(millis() - timeStarted) < 10000UL) {
      delay(250);
      Serial.print(".");
    }
    Serial.println();
    if (time(nullptr) >= 1700000000) {
      Serial.println("[TIME] clock synchronized");
    } else {
      Serial.println("[TIME] NTP not synchronized; session timestamps cannot be POSTed yet");
    }
  } else {
    Serial.println("[WIFI] not connected; will retry while the firmware runs");
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println();
  Serial.println("============================================");
  Serial.println(" Kilowhat ESP32 DevKit V1 - one-button RFID");
  Serial.printf(" Outlet: %s\n", OUTLET_ID);
  Serial.printf(" Max BLE streams: %u\n", (unsigned)MAX_BLE_STREAMS);
  Serial.println("============================================");

  pinMode(BUTTON_PLUG_PIN, INPUT_PULLUP);

  // Explicit custom SPI mapping keeps all RC522 signal wires on one side
  // of the common 30-pin ESP32 DevKit V1 header.
  SPI.begin(RFID_SCK_PIN, RFID_MISO_PIN, RFID_MOSI_PIN, RFID_SS_PIN);
  rfid.PCD_Init();
  delay(4);
  rfid.PCD_AntennaOff(); // idle until Button 2 simulates a plug event
  Serial.println("[RFID] MFRC522 initialized (13.56 MHz tags); antenna idle until plug event");

  randomSeed((uint32_t)esp_random());

  // Start BLE before the potentially blocking Wi-Fi/NTP connection sequence.
  // The phone can discover the node even while Wi-Fi is still connecting.
  startBleServer();
  connectWiFi();

  lastSampleMs = millis() - SAMPLE_INTERVAL_MS; // first reading immediately
  Serial.println("[READY] Press the button once to plug in an ACTIVE appliance for 60 seconds.");
  Serial.println("[READY] Press it again to remove the appliance early.");
  Serial.println("[READY] Present an RFID tag within 5 seconds; otherwise ID becomes unknown_id.");
  Serial.println("[READY] A session closes after 5 seconds of cooldown following unplugging.");
}
void loop() {
  uint32_t now = millis();

  // One button toggles between simulated plug-in and removal.
  if (buttonPressed(plugButton, now)) {
    if (plugged) {
      removePlugSimulation("appliance removed by user (plug button)");
    } else {
      startPlugSimulation(now);
    }
  }

  // Check RFID before the 60-second auto-remove boundary.
  // If a tag is not presented during the full 5-second window, resolve as unknown_id.
  handleRfidReader(now);

  // Automatically remove the simulated appliance after 60 seconds.
  if (plugged && (int32_t)(now - plugDeadlineMs) >= 0) {
    removePlugSimulation("60-second simulation finished; appliance automatically removed");
  }

  // Retry Wi-Fi if it was unavailable at boot or later disconnected.
  if (WiFi.status() == WL_CONNECTED && !ntpConfigured) {
    configTime(8 * 3600, 0, "pool.ntp.org", "time.nist.gov");
    ntpConfigured = true;
    Serial.println("[TIME] NTP sync started after Wi-Fi reconnection");
  }

  if (String(WIFI_SSID) != "YOUR_WIFI_NAME" &&
      WiFi.status() != WL_CONNECTED &&
      (uint32_t)(now - lastWifiRetryMs) >= 10000UL) {
    lastWifiRetryMs = now;
    Serial.println("[WIFI] retrying connection...");
    WiFi.reconnect();
  }

  if ((uint32_t)(now - lastSampleMs) >= SAMPLE_INTERVAL_MS) {
    float dtSeconds = (uint32_t)(now - lastSampleMs) / 1000.0f;
    lastSampleMs = now;
    simulateMeterReading(now, dtSeconds);
  }

  delay(5);
}

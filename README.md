# Device Hub

Simulierte Messgeräte verbinden sich dauerhaft per WebSocket mit einem Backend, senden regelmäßig Status und Messwert und empfangen Befehle (z. B. „Messintervall ändern“). Eine Weboberfläche zeigt die Geräte live an.

## Aufbau

```
.
├── backend/     Spring Boot (Kotlin): Geräteverbindungen, REST/SSE-API, Web-UI
└── simulator/   eigenständiges Kotlin-Programm, simuliert N Geräte
```

Backend und Simulator sind **zwei unabhängige Maven-Projekte ohne gemeinsamen Code**. Ein echtes Messgerät kennt die Klassen des Backends nicht. Es spricht nur das unten dokumentierte JSON-Protokoll. Der Simulator verhält sich genauso: Er baut seine Nachrichten selbst als JSON und hängt in keiner Form vom Backend ab. Der einzige Vertrag zwischen beiden ist das [Protokoll](#protokoll).

## Starten

Voraussetzung: JDK 21 oder neuer. Maven ist nicht nötig, jedes Projekt bringt den Maven Wrapper mit.

```bash
cd backend && ./mvnw spring-boot:run
```

```bash
cd simulator && ./mvnw compile exec:java
```

Optional mit Argumenten: `-Dexec.args="ws://localhost:8080/ws/device 5"` (Hub-URL, Anzahl Geräte).

Ohne Argumente startet der Simulator 10 Geräte (`device-0` bis `device-9`) gegen `ws://localhost:8080/ws/device`. Jedes Gerät sendet zunächst alle 5 s einen Messwert zwischen 0 und 100. Bricht die Verbindung ab, verbindet es sich mit exponentiellem Backoff (1 s bis 30 s, mit Jitter) neu.

Damit die Oberfläche alle Zustände zeigt, verhalten sich die letzten vier Geräte absichtlich fehlerhaft (bei 10 Geräten `device-6` bis `device-9`):

| Gerät | Verhalten | Sichtbar in der UI |
|---|---|---|
| `device-6` | `REJECTING`: lehnt jeden Befehl mit Fehler ab | Befehl „abgelehnt“ mit Grund |
| `device-7` | `DEAF`: ignoriert Befehle, sendet kein `ack` | Befehl „wartet“, nach 10 s „keine Antwort“ |
| `device-8` | `HANGING`: verstummt nach 3 Messungen, die Verbindung bleibt aber offen | nach ca. 15 s offline durch Timeout (halboffene Verbindung) |
| `device-9` | `FLAKY`: bricht alle 10–20 s die Verbindung ab | kurz offline, dann wieder online |

Weboberfläche: <http://localhost:8080>

## Protokoll

Verbindung: WebSocket auf `ws://<host>:8080/ws/device`. Alle Nachrichten sind JSON-Textframes mit einem Feld `type`, das die Art der Nachricht bestimmt. Feldnamen sind `snake_case`. Unbekannte Felder werden ignoriert.

### Gerät → Backend

| `type` | Felder | Bedeutung |
|---|---|---|
| `register` | `name` | erste Nachricht nach dem Verbindungsaufbau, meldet das Gerät an |
| `telemetry` | `status`, `value` (Zahl), `timestamp` (ISO-8601, UTC) | Status und Messwert, im aktuellen Intervall gesendet |
| `ack` | `command_id` (optional), `error` (optional) | Antwort auf einen Befehl. Ohne `error` erfolgreich, mit `error` abgelehnt. `command_id` fehlt nur, wenn der Befehl nicht lesbar war. |

```json
{"type":"register","name":"device-1"}
{"type":"telemetry","status":"active","value":42.17,"timestamp":"2026-10-28T09:41:23.512Z"}
{"type":"ack","command_id":"c-1"}
{"type":"ack","command_id":"c-2","error":"new interval must be greater than zero"}
```

### Backend → Gerät

| `type` | Felder | Bedeutung |
|---|---|---|
| `set_interval` | `command_id`, `new_interval_millis` (> 0) | Messintervall ändern; gilt ab der nächsten Messung |

```json
{"type":"set_interval","command_id":"c-1","new_interval_millis":2000}
```

Jeder Befehl trägt eine `command_id`. Das Gerät antwortet darauf mit genau einem `ack` mit derselben `command_id`.

### Verhalten des Backends

- Eine Nachricht, die kein gültiges JSON ist oder nicht zum Protokoll passt, wird geloggt und **ignoriert**. Die Verbindung bleibt offen.
- `telemetry` oder `ack` vor `register` ist ein Protokollverstoß, und die Verbindung wird mit Code `1008` geschlossen.
- Meldet sich ein Gerät mit einem Namen an, der bereits verbunden ist, ersetzt die neue Verbindung die alte (z. B. bei einem Reconnect, bevor die alte als tot erkannt wurde).
- Ein Gerät gilt als **offline**, wenn die Verbindung geschlossen wird oder es länger als `device-hub.offline-after` (Standard 15 s) bzw. 3 × sein Messintervall nichts sendet. Das erkennt auch halboffene Verbindungen, etwa nach einem Stromausfall.
- Das Messintervall eines Geräts kennt das Backend erst, wenn das Gerät ein `set_interval` bestätigt hat. Nach jedem `register` gilt es wieder als unbekannt, weil das Gerät neu gestartet sein kann.
- Ein Befehl ohne `ack` gilt nach `device-hub.command-timeout` (Standard 10 s) als unbeantwortet.

## Backend

```
com.example.devicehub
├── protocol/    Nachrichtentypen + ProtocolCodec (JSON ↔ Typen, eigener ObjectMapper)
├── device/      DeviceRegistry (Zustand aller Geräte), DeviceConnection, Timeout-Job
├── websocket/   /ws/device: Handler, Konfiguration, WebSocket-Implementierung von DeviceConnection
└── api/         REST-Endpunkte und SSE-Stream für die Weboberfläche
```

```
Gerät ──WS──► DeviceWebSocketHandler ──► DeviceRegistry ──DeviceChanged──► DeviceEventStream ──SSE──► Browser
  ▲                                            │    ▲                                                     │
  └──────────── set_interval ◄─────────────────┘    └──────────── DeviceController ◄──── POST ────────────┘
```

### HTTP-API

| Methode | Pfad | Beschreibung |
|---|---|---|
| `GET` | `/api/devices` | alle bekannten Geräte mit Zustand |
| `GET` | `/api/devices/stream` | SSE: zuerst `snapshot` (alle Geräte), danach `device` bei jeder Änderung |
| `POST` | `/api/devices/{name}/commands/set-interval` | Body `{"intervalMillis": 2000}`. Antwort `202` mit Befehlsstatus `PENDING`; das Ergebnis kommt über den Stream. `404` für unbekannte, `409` für Offline-Geräte, `400` für ungültige Werte |

## Tests

```bash
cd backend && ./mvnw verify
```

- `ProtocolCodecTest`: Parsen und Serialisieren des Protokolls, inklusive ungültiger Nachrichten
- `DeviceRegistryTest`: Registry-Logik mit Fake-Verbindung und steuerbarer Uhr (Timeouts ohne `sleep`)
- `DeviceHubIntegrationTest`: echter Server; ein Test-Client verbindet sich per WebSocket als Gerät und nutzt die HTTP-API wie der Browser

```bash
cd simulator && ./mvnw test
```

- `MapperTest`: Parsen von Befehlen, inklusive ungültiger Werte, unbekannter Typen und kaputtem JSON
- `MessageTest`: JSON-Form der Nachrichten an das Backend
- `SimulatorTest`: echter Simulator gegen einen lokalen WebSocket-Server (`MockWebServer`), der das Backend spielt: Anmeldung, Telemetrie, `ack` auf gültige und ungültige Befehle, Reconnect, Verhalten `REJECTING` und `DEAF`
- `BehaviorTest`: Verteilung der Fehlerverhalten auf die Geräte

## Designentscheidungen

- **Raw WebSocket statt STOMP** für Geräte: Das Protokoll bleibt einfach und kommt ohne Messaging-Framework auf dem Gerät aus.
- **Server-Sent Events + REST** für die UI: Live-Daten fließen nur in Richtung Browser, Befehle gehen per `POST`.
- **Kein geteilter Code** zwischen Backend und Simulator (siehe oben).
- **`DeviceConnection` als Interface**: Die Registry kennt keine WebSockets. Das macht sie ohne Server testbar und den Transport austauschbar (z. B. MQTT).
- **Ein Lock in der Registry**: Alle Zustandsänderungen laufen unter einem Lock, so bleiben Geräte und Verbindungen konsistent. Senden, Schließen und Events passieren außerhalb, damit ein langsames Gerät die anderen nicht blockiert. Bei wenigen Geräten ist das einfacher und sicherer als feingranulare Nebenläufigkeit.
- **`ConcurrentWebSocketSessionDecorator`**: Spring-WebSocket-Sessions sind nicht thread-safe beim Senden, Befehle (HTTP-Thread) und Schließen (Scheduler) können aber gleichzeitig passieren.
- **Eigene DTOs für die HTTP-API** (`DeviceView`, `CommandView`): Die Protokolltypen gelangen nie in die API. Geräteprotokoll und UI-Schnittstelle können sich unabhängig voneinander ändern.
- **Eigener ObjectMapper für das Protokoll**: Das Geräteprotokoll ändert sich nicht, wenn jemand die JSON-Konfiguration der REST-API anpasst.
- **Zustand nur im Speicher**: Nach einem Neustart melden sich die Geräte per Reconnect neu an, der Verlauf geht verloren.

## Bekannte Grenzen

- **Start-Intervall unbekannt**: Das Protokoll überträgt das Intervall nicht. Ein Gerät, das ab Werk seltener als alle 15 s sendet, gilt deshalb als offline, bis es einen `set_interval` bestätigt hat. Abhilfe: Das Gerät schickt sein Intervall in `register` mit.
- **Langsame Browser bremsen**: SSE-Events werden synchron auf dem Thread gesendet, der die Änderung auslöst. Ein hängender Browser verzögert so die Verarbeitung von Gerätenachrichten und den Timeout-Job. Abhilfe: pro Browser eine eigene Warteschlange mit eigenem Sender, die bei vollem Puffer getrennt wird.
- **Keine Untergrenze fürs Intervall**: Bei 1 ms sendet ein Gerät 1000 Nachrichten pro Sekunde, und jede wird an alle Browser weitergereicht.
- **Kein Schutz der Gerätenamen**: Jede Verbindung kann sich unter jedem Namen anmelden und damit ein verbundenes Gerät verdrängen (siehe Authentifizierung unten).

## Mögliche Erweiterungen

- Messwert-Verlauf in einer Datenbank, Diagramm in der UI
- Authentifizierung der Geräte (Token beim Handshake)
- Mehrere Backend-Instanzen: Befehle müssen die Instanz erreichen, an der das Gerät hängt (z. B. über Redis Pub/Sub)
- Weitere Befehle, Befehl an alle Geräte

# Device Hub

Simulierte Messgeräte verbinden sich dauerhaft per WebSocket mit einem Backend, senden regelmäßig Status und Messwert und empfangen Befehle (z. B. „Messintervall ändern“). Eine Weboberfläche zeigt die Geräte live an.

> Status: Grundgerüst. Die Abschnitte unten beschreiben das Zielbild.

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

Weboberfläche: <http://localhost:8080>

## Protokoll

Verbindung: WebSocket auf `ws://<host>:8080/ws/device`. Alle Nachrichten sind JSON-Objekte mit einem Feld `type`.

### Gerät → Backend

| `type` | Felder | Bedeutung |
|---|---|---|
| `hello` | `deviceId` | erste Nachricht nach dem Verbindungsaufbau |
| `telemetry` | `status`, `value`, `timestamp` | Status und Messwert, im aktuellen Intervall gesendet |
| `ack` | `commandId`, `ok`, optional `error` | Bestätigung eines Befehls |

### Backend → Gerät

| `type` | Felder | Bedeutung |
|---|---|---|
| `command` | `commandId`, `name`, `params` | Befehl, z. B. `name: "set_interval"`, `params: {"seconds": 2}` |

Das genaue Format und das Verhalten bei ungültigen Nachrichten werden während der Implementierung ergänzt.

## Designentscheidungen

- **Raw WebSocket statt STOMP** für Geräte: Das Protokoll bleibt einfach und kommt ohne Messaging-Framework auf dem Gerät aus.
- **Server-Sent Events + REST** für die UI: Live-Daten fließen nur in Richtung Browser, Befehle gehen per `POST`.
- **Kein geteilter Code** zwischen Backend und Simulator (siehe oben).

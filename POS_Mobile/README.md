# LATAM Payment Application

## Overview

LATAM Payment Application is a POS payment solution built by **UIC** for Nexgo Android POS devices deployed across Latin American merchant environments. It handles card and cash transactions, terminal configuration via TMS, and operates as a managed payment client under the **UIC Home** launcher application.

## Features

- Card payment processing: Sales, Refunds, Loyalty, Quota-based transactions
- Cash and check-in/check-out operations
- Post-transaction tip adjustment and quick-tip flows
- Multi-acquirer batch settlement with automated scheduling
- Dynamic terminal configuration delivered via TMS (Terminal Management System)
- Multi-language and multi-currency support with runtime locale switching
- SQLCipher-encrypted local transaction database
- Coordinated application update and parameter update lifecycle managed by UIC Home

## Architecture

```
UIC Home Launcher  ──►  TMS Server (MQTT/HTTP)
       │
       │  Broadcast Intents
       ▼
LATAM Payment App
       │
       ├── Jetpack Compose UI (Material 3)
       ├── Room + SQLCipher (encrypted DB)
       ├── Nexgo EMV SDK (card reader)
       └── ISO 8583 Module (host messaging)
```

For full details on the UIC Home ↔ Payment App protocol, see [docs/uic_home_interaction.md](docs/uic_home_interaction.md).

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- Android SDK API 29+
- Nexgo POS device (N86, N87, or compatible)

### Clone and Build

```bash
git clone https://uicugit.uicusa.com/fabian.ramirez/latam-payment-application.git
```

Open in Android Studio via **File → Open...**, select the `POS_Mobile` project root. Build and deploy via Gradle.

### Remotes

| Name   | URL                                                                           |
|--------|-------------------------------------------------------------------------------|
| latam  | https://uicugit.uicusa.com/fabian.ramirez/latam-payment-application.git      |
| origin | git@uicugit.uicusa.com:fabian.ramirez/androidpaymentapp.git                  |

## ISO 8583 Module

The `iso8583` Gradle module is a UIC-authored implementation for composing and parsing ISO 8583 financial messages. All public classes include Javadoc and are namespaced under `com.uic.pos.iso8583`.

Default field layout: `app/src/main/assets/iso8583_ISSWITCH_config.xml`

Add additional host profiles by shipping extra XML assets and selecting them at runtime:

```kotlin
val factory = Iso8583Provider.createFactory(context, "iso8583_MY_HOST_config.xml")
```

## TMS Configuration

Terminal parameters are delivered as a JSON document (`TMS_Database`) by UIC Home on startup and whenever the TMS server publishes an update. The app validates the `StructVersion` field before applying any parameter set. See [docs/uic_home_interaction.md](docs/uic_home_interaction.md) for the full parameter exchange protocol.

## Documentation Index

| Document | Description |
|----------|-------------|
| [docs/uic_home_interaction.md](docs/uic_home_interaction.md) | UIC Home ↔ Payment App IPC protocol: parameter requests, pushed updates, coordinated app update consent flow |
| [docs/settlement_flow_overview.md](docs/settlement_flow_overview.md) | Settlement batch processing flow inherited from legacy C++ reference |
| [AGENTS.md](AGENTS.md) | Agent and AI-assisted development guidelines for this project |

## Support

Contact the UIC development team through your standard support channel for questions, feature requests, or issue reports.

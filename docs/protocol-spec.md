# Pulse protocol spec

Status: **v0**, 2026-10-23. This is the only protocol input for implementers.
Spec revision tracked in code: `app.pulse.wire.ProtocolSpecVersion.CURRENT` = `0`.

The codec is **not** part of this revision. Do not add frame builders, parsers, checksum code, reassembly, or command correlation until this spec has been reviewed on its own.

Device, nominative: a WHOOP 5.0 or MG strap the user owns. Baseline firmware for the 5.0/MG contracts below is **50.42.1.0** unless a row names an older capture. WHOOP 4 layouts are out of scope and must not be reused by shifting offsets.

## How to read a row

| Confidence | Meaning |
|---|---|
| `documented` | Stated by the cited public prose for the firmware in that row. |
| `observed` | Reported from a device capture in the cited write-up. Not yet confirmed by a Pulse capture. |
| `unconfirmed` | Sources disagree, the field meaning is unresolved, or the 50.42.1.0 contract does not state it. |

A project rule is a decision for our app. It is not a claim about the strap. Project rules are collected at the end and are not given a firmware confidence.

### Sources

| Id | Document | URL |
|---|---|---|
| W5 | 5.0/MG profile | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md |
| TR | Transport | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md |
| SE | Sensor records | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md |
| CM | Commands | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md |
| IM | Implementation notes (packet and event catalogues, hazards) | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md |
| BL | Capture notes | https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md |
| CO | Shared concepts | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md |
| PR | Scope | https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL.md |
| RD | Pairing notes | https://github.com/ryanbr/noop/blob/main/README.md |
| JU | Independent 5.0 capture write-up | https://judes.club/writing/cracking-the-whoop-5-bluetooth-protocol/ |
| SIG-HR | Heart Rate Service 1.0 | https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/ |
| SIG-BAS | Battery Service | https://www.bluetooth.com/specifications/specs/battery-service-1-0/ |
| SIG-DIS | Device Information Service | https://www.bluetooth.com/specifications/specs/device-information-service-1-1/ |
| CRC16 | CRC-16/MODBUS parameters | https://reveng.sourceforge.io/crc-catalogue/16.htm#crc.cat.crc-16-modbus |
| CRC32 | CRC-32/ISO-HDLC parameters | https://reveng.sourceforge.io/crc-catalogue/17plus.htm#crc.cat.crc-32-iso-hdlc |

Integers and IEEE-754 floats in format 1 are little-endian. Offsets are from the start of a reassembled frame unless a row says the offset is into a command body.

## 1. Scope

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| Family covered here | 5.0 and MG share one profile. ECG still depends on MG hardware. | [PR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL.md#scope-and-compatibility), [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#version-and-validation-boundary) | 50.42.1.0 | documented |
| Baseline | Command, transport, and sensor contracts apply to 50.42.1.0 unless a paragraph names an older observation. | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#version-and-validation-boundary) | 50.42.1.0 | documented |
| Do not shift 4.0 layouts | 5.0 history records are not 4.0 layouts with an added envelope offset. | [PR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL.md#scope-and-compatibility), [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#version-and-validation-boundary) | 50.42.1.0 | documented |
| Older captures that must not be mixed in silently | Hello decoding notes exist for 50.38.1.0. ECG notes exist for MG 50.39.1.0. Optical/IMU and reboot notes exist for 50.40.1.0. R-R conversion notes exist for 50.41.1.0. | [PR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL.md#scope-and-compatibility) | as named | documented |

Generation 4 (service `61080001-8d6d-82b8-614a-1c8cb0f8dcc6`, CRC-8 header, inner record at byte 4) is cited only so it is not implemented by mistake. It is not a Pulse target.

## 2. GATT map

The 128-bit suffix `-cce1-4033-93ce-002d5875f58a` is part of the published service UUID. Characteristic UUIDs use that same suffix with the first group replaced.

| Role | UUID | Source | Firmware | Confidence |
|---|---|---|---|---|
| Proprietary service | `fd4b0001-cce1-4033-93ce-002d5875f58a` | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) | 50.42.1.0 | documented |
| Command write | `fd4b0002-cce1-4033-93ce-002d5875f58a` | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) | 50.42.1.0 | documented |
| Notify | `fd4b0003-cce1-4033-93ce-002d5875f58a` | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) | 50.42.1.0 | documented |
| Notify | `fd4b0004-cce1-4033-93ce-002d5875f58a` | same | 50.42.1.0 | documented |
| Notify | `fd4b0005-cce1-4033-93ce-002d5875f58a` | same | 50.42.1.0 | documented |
| Notify | `fd4b0007-cce1-4033-93ce-002d5875f58a` | same | 50.42.1.0 | documented |

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| Session subscribe set | Subscribe `fd4b0003`, `fd4b0004`, `fd4b0005`, and `fd4b0007`, then write the client hello to `fd4b0002`. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) section "Bonding and the puffin session"; [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#connection-and-frame-format) | 5.0 session described there; hello write is in the 50.42.1.0 profile | documented |
| Hello write type | The client hello is written **with response**. | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#connection-and-frame-format) | 50.42.1.0 | documented |
| Write without response | Silently ignored on the command characteristic in that capture. | [JU](https://judes.club/writing/cracking-the-whoop-5-bluetooth-protocol/) | 50.38.1.0 | observed |
| `fd4b0003` carries command responses | Same characteristic number as the generation-4 command-response notify. The 50.42.1.0 profile table lists it only as "notify". | Generation-4 role map in [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) section "The GATT layout"; 5.0 UUID list in [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) | 4.0 role is documented; 5.0 role is by characteristic-number parallel | documented |
| `fd4b0004` carries events | Same parallel as above (generation-4 event notify). | same | same | documented |
| `fd4b0005` carries fragmented data | Same parallel as above. Generation-4 data notifications can split frames. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) GATT layout; [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md) fragmentation is stated for generation 4 and reassembly is required before format-1 decode | 4.0 observed for split notifications; format-1 reassembly is documented for 50.42.1.0 | documented |
| `fd4b0007` role | Extra notify characteristic on 5.0/MG. No role is assigned on the profile page. | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) | 50.42.1.0 | unconfirmed |
| Other advertised families | `11500001-6215-11ee-8c99-0242ac120002`, `8a580001-2fe8-4796-9267-b87a2b0c8234`, `59830001-5955-419b-bb8d-c8262926af23`. Framing unresolved. Do not connect. | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#whoop-50--mg--service-fd4b0001-) for the first UUID; all three in [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#diagnostic-only-whoop-service-families) | 50.42.1.0 profile for the first; catalogue for the other two | documented that they are seen; framing unconfirmed |

### Standard services

Bluetooth base UUID: `0000XXXX-0000-1000-8000-00805f9b34fb`.

| Service | Service UUID | Characteristic | UUID | Fact | Source | Firmware | Confidence |
|---|---|---|---|---|---|---|---|
| Heart Rate | `0000180d-0000-1000-8000-00805f9b34fb` | Heart Rate Measurement | `00002a37-0000-1000-8000-00805f9b34fb` | HR and R-R, works unbonded | [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md), [SIG-HR](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/) | both generations; SIG is versionless | documented |
| Battery | `0000180f-0000-1000-8000-00805f9b34fb` | Battery Level | `00002a19-0000-1000-8000-00805f9b34fb` | One byte, percent | [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md), [SIG-BAS](https://www.bluetooth.com/specifications/specs/battery-service-1-0/) | both generations | documented |
| Device Information | `0000180a-0000-1000-8000-00805f9b34fb` | Model Number String | `00002a24-0000-1000-8000-00805f9b34fb` | Used by the variant heuristics below | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#9-whoop-50-vs-mg--telling-the-hardware-apart), [SIG-DIS](https://www.bluetooth.com/specifications/specs/device-information-service-1-1/) | 50.42.1.0 | documented |
| Device Information | same | Serial Number String | `00002a25-0000-1000-8000-00805f9b34fb` | Used by the variant heuristics below | same | 50.42.1.0 | documented |
| Device Information | same | Hardware Revision String | `00002a27-0000-1000-8000-00805f9b34fb` | Used by the variant heuristics below | same | 50.42.1.0 | documented |
| Device Information | same | Firmware Revision String | `00002a26-0000-1000-8000-00805f9b34fb` | Standard DIS characteristic. The 50.42.1.0 profile does not give it a field table. Hello body bytes 91..93 are the specified version source. | [SIG-DIS](https://www.bluetooth.com/specifications/specs/device-information-service-1-1/) | SIG | documented as a SIG characteristic; population on the strap is unconfirmed |

### Variant heuristics

These do not change framing and are not a universal rule. Conflicting signals leave the variant unresolved. An unresolved variant does not prove the strap lacks MG hardware.

| Signal | Reads as | Source | Firmware | Confidence |
|---|---|---|---|---|
| Model number string `MG` | MG | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#9-whoop-50-vs-mg--telling-the-hardware-apart) | 50.42.1.0 | documented |
| Serial prefix `5AM` | MG | same | 50.42.1.0 | documented |
| Serial prefix `5AG` | 5.0 | same | 50.42.1.0 | documented |
| Hardware revision contains `WG50` | 5.0 | same | 50.42.1.0 | documented |
| No universal MG hardware-revision token | Absence of a known prefix does not mean "not MG" | same | 50.42.1.0 | documented |

## 3. Bond

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| One bond | The strap holds an encrypted bond with one central at a time. | [RD](https://github.com/ryanbr/noop/blob/main/README.md) section "Pairing a WHOOP 5.0 / MG" | described for 5.0/MG, not a frame field | documented |
| Proprietary channel needs that bond | Custom-channel operations are refused while another central holds the bond. The pairing note quotes the strap log as "Encryption is insufficient" / "bond refused". | [RD](https://github.com/ryanbr/noop/blob/main/README.md) | 5.0/MG pairing note | documented |
| Standard HR does not need the bond | Heart Rate Measurement streams without the proprietary bond. | [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md), [RD](https://github.com/ryanbr/noop/blob/main/README.md) | both | documented |
| Pairing mode | Repeated firm taps until the LEDs flash blue, after the other central has released the bond. | [RD](https://github.com/ryanbr/noop/blob/main/README.md) | 5.0/MG pairing note | documented |
| Bonding can move the strap off the official app | The official app may need to pair again afterwards. | [RD](https://github.com/ryanbr/noop/blob/main/README.md) | pairing note | documented |
| Bond strength | Public notes disagree. An older revision of the capture notes called the bond "just works" ([blob `ef165de4`](https://github.com/ryanbr/noop/blob/ef165de4/docs/BLE_REVERSE_ENGINEERING.md)). A macOS capture reported "Authentication is insufficient" and could not pair from that Mac ([JU](https://judes.club/writing/cracking-the-whoop-5-bluetooth-protocol/)). | those two | older note vs 50.38.1.0 capture | unconfirmed |
| No application-layer cipher | CRC checks are integrity checks, not authentication. | [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md#25-checksums) | both | documented |
| Hello identity blocks | Hello may contain identifiers. A decoder should take only the fields it needs. There is no requirement to echo them. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#clock-and-identity-contracts) | 50.42.1.0 | documented |

## 4. Format 1 frame

Authoritative section: [TR format 1](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing). Firmware 50.42.1.0. Confidence `documented` for every row in this section unless marked otherwise.

| Offset | Width | Field |
|---:|---:|---|
| 0 | 1 | Start byte `0xAA` |
| 1 | 1 | Format `0x01` |
| 2 | 2 | Declared length L = complete frame length − 8 |
| 4 | 2 | Header word. Requests are commonly `00 01`. Responses are commonly `01 00`. Do not require one constant value. Meaning beyond those observed bytes is unresolved (`unconfirmed`). |
| 6 | 2 | CRC-16/MODBUS over bytes `[0, 6)` |
| 8 | 1 | Packet type. `35` is a command. |
| 9 | 1 | Sequence, `u8`, wraps |
| 10 | 1 | Command number on a command frame. Other packet types use this byte differently. |
| 11 | variable | Body, then zero padding until the byte count from offset 8 through the end of the padding is divisible by 4 |
| end−4 | 4 | CRC-32 over the padded bytes beginning at offset 8, excluding the CRC itself |

Construction facts from the same section:

- The padded span is packet type, sequence, command, command-specific bytes, then zero pad.
- Declared length L = padded size + 4.
- Complete frame size = padded size + 12, which is the same as L + 8.
- An explicit body byte `00` and a missing body are not interchangeable, even when padding makes the frames look similar.

### Checksums

| Algorithm | Parameters | Covers | Source | Confidence |
|---|---|---|---|---|
| CRC-16/MODBUS | Reflected polynomial `0xA001`, init `0xFFFF`, no final XOR. Stored little-endian. | Bytes `[0, 6)` | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing), [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md#25-checksums), [CRC16](https://reveng.sourceforge.io/crc-catalogue/16.htm#crc.cat.crc-16-modbus) | documented |
| CRC-32/ISO-HDLC (zlib) | Reflected polynomial `0xEDB88320`, init `0xFFFFFFFF`, final XOR `0xFFFFFFFF`. Stored little-endian. | Bytes `[8, length−4)`, padding included | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing), [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md#25-checksums), [CRC32](https://reveng.sourceforge.io/crc-catalogue/17plus.htm#crc.cat.crc-32-iso-hdlc) | documented |

### Acceptance

From [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing), firmware 50.42.1.0, confidence `documented`:

- Complete frame longer than 15 bytes.
- Declared length and the bytes actually supplied agree (complete size = L + 8).
- `(L − 4)` is divisible by 4.
- Header CRC matches.
- Body CRC matches.
- A bad format, size, or CRC is a **local** failure. It is not a command-result code on the wire.

The capture notes describe a 13-byte empirical floor used by one implementation, and say a 12-byte zero-payload envelope is self-consistent but was not in the committed fixtures ([BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 envelope"). That 13-byte floor disagrees with the transport rule "longer than 15 bytes". For 50.42.1.0, follow the transport rule. The 13-versus-16 question for a typeless frame is `unconfirmed` and is not required for command frames, whose smallest padded hello is 16 bytes.

### Format 2

A second outer format exists (outer type `0x40`). How a session negotiates it is unresolved. Ordinary traffic uses format 1. Do not apply format-1 offsets to format 2. Source: [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-2-boundary). Firmware 50.42.1.0. Confidence: existence `documented`; negotiation `unconfirmed`.

## 5. Command response (packet type 36)

Source: [TR responses](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#responses-and-correlation). Firmware 50.42.1.0. Confidence `documented`.

| Offset | Field |
|---:|---|
| 8 | Packet type `36` |
| 9 | This response frame's own sequence. Not the request echo. |
| 10 | Command number |
| 11 | Originating request sequence |
| 12 | Result |
| 13 | Command body. CRC and outer padding are not body fields. |

Match a reply by command, origin sequence, and connection. The sequence is 8 bits and wraps. A late notification after reconnect is not automatically the new session's reply. One request can produce more than one CRC-valid response. A result of success means the command was accepted at that response boundary. It does not prove asynchronous work finished.

| Result | Meaning |
|---:|---|
| 0 | Failure. Do not invent a cause. |
| 1 | Success at this command's response boundary. |
| 2 | Pending. Keep the request open for a later result. |
| 3 | Unsupported in this command context. |
| other | Unknown. Keep the numeric value. Do not treat it as success. |

The first body byte is command-specific. On several revision-1 commands it is a revision. On command 26 it is the start of a `u32` whole-percent value. It is not a universal success flag.

## 6. Packet types

Packet type, record layout, command number, and event number are different namespaces ([SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity)).

| Value | Role on 5.0/MG | Source | Firmware | Confidence |
|---:|---|---|---|---|
| 35 | Command, app to strap | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing), [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#packettype-offset-4-or-8-on-50) | 50.42.1.0 | documented |
| 36 | Command response | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#responses-and-correlation) | 50.42.1.0 | documented |
| 37 | Older label only. Role on 50.42.1.0 not confirmed. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#packettype-offset-4-or-8-on-50) | 50.42.1.0 | unconfirmed |
| 38 | Older label, aliased to the command response by one client. Role on 50.42.1.0 not confirmed. | same | 50.42.1.0 | unconfirmed |
| 40 | Live HR and R-R. Separate from standard Heart Rate Measurement. | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-40-live-hr-and-r-r) | 50.42.1.0 | documented |
| 43 | Live sensor data. Layout byte at offset 9. Type alone does not identify a waveform. | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity) | 50.42.1.0 | documented |
| 47 | Historical record. Layout byte at offset 9. | same | 50.42.1.0 | documented |
| 48 | Event. Event number at byte 10. `u32` timestamp at byte 12. | same | 50.42.1.0 | documented |
| 49 | History metadata (start / end / complete). | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity), [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| 50 | Console log text | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#packettype-offset-4-or-8-on-50) | catalogue; not a field layout | documented as a name |
| 51 | Realtime IMU stream. Current strap output unconfirmed. | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity) | 50.42.1.0 | documented name; output unconfirmed |
| 52 | Historical IMU stream. Do not decode as layout 21. | same | 50.42.1.0 | documented |
| 53 | Named as a 5.0 event-like type in the catalogue. Layout not specified on the sensor page. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#packettype-offset-4-or-8-on-50) | catalogue | unconfirmed |
| 54 | Named as a 5.0 strap-event type in the catalogue. | same | catalogue | unconfirmed |
| 55 | Named in the catalogue. Not given a 5.0 layout here. | same | catalogue | unconfirmed |
| 56 | Named as 5.0 metadata and aliased to type 49 by one client. The transport page says types 49 and 56 both participate in known history routing, and that no universal boundary between them is established. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#packettype-offset-4-or-8-on-50), [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented that 56 exists in routing notes; when it replaces 49 is unconfirmed |

The catalogue says it is not a guarantee that every named packet is produced. Preserve unknown types as raw bytes.

## 7. Commands used for the first milestone

Our names are the ones in the first column. The documented identifier is the public command name, recorded so a fact can be checked. Command numbers are bytes.

| Our name | Number | Documented identifier | Request body | Response body | Source | Firmware | Confidence |
|---|---:|---|---|---|---|---|---|
| HELLO | 145 (`0x91`) | `GET_HELLO` | Revision `1` (revision `3` is also defined; this milestone sends `1`) | Pending, then final. Revision 1 pending body is 107 bytes, zero-filled except the echoed revision. Final revision-1 body is 107 bytes. See the hello table. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg), [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#hello--command-145) | 50.42.1.0 | documented |
| BATTERY | 26 (`0x1A`) | `GET_BATTERY_LEVEL` | Older requests use an empty body or `00`. The current query is described as asynchronous. | Final body is 4 bytes, `u32` whole percent. Result 1 on success. Result 0 with four zero bytes on the documented error. A zero value can also be a conversion fallback, so it does not by itself prove an empty battery. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg), [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#battery-level--command-26) | 50.42.1.0 | documented |
| CLOCK_GET | 147 (`0x93`) | `GET_CLOCK` | Revision `1` | 7 bytes: revision, `u32` Unix seconds, `u16` ticks of 1/32768 s. Returned ticks are `floor(hundredths × 32768 / 100)`. Success with zero time can be a failed read. There is no separate validity flag. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#clock-and-identity-contracts) | 50.42.1.0 | documented |
| CLOCK_SET | 146 (`0x92`) | `SET_CLOCK` | Revision `1`, `u32` Unix seconds, `u16` ticks in 1/32768 s. Use a fraction of 0..32767. Stored precision is hundredths: `floor(ticks × 100 / 32768)`. | One-byte revision-1 body, success or failure. | same | 50.42.1.0 | documented |
| DATA_RANGE | 34 (`0x22`) | `GET_DATA_RANGE` | No semantic fields. | Pending with an empty body, then a final 65-byte body. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#data-range--command-34), [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg) | 50.42.1.0 | documented |
| HISTORY_START | 22 (`0x16`) | `SEND_HISTORICAL_DATA` | Explicit `00` on the 50.42.1.0 transport contract. | Acceptance is not the history stream. The command-22 response is described as a state byte plus two zero bytes. States 6, 7, 9, and 10 fail; other states succeed, and asynchronous delivery is still separate. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership), [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg) | 50.42.1.0 | documented |
| HISTORY_ACK | 23 (`0x17`) | `HISTORICAL_DATA_RESULT` | `01` followed by the original 8-byte end token, only after the chunk is committed locally. Write with response. | A successful acknowledgement can let the strap reclaim that history. A non-success does not perform the normal boundary update. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership), [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg) | 50.42.1.0 | documented |

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| Empty history-start body | The transport text says an explicit `00` and a missing argument must not be treated as the same. Whether an empty body is also accepted is not stated. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing) | 50.42.1.0 | unconfirmed |
| Clock commands 10 and 11 | Deprecated on this profile. Do not send them in place of 146 and 147. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#clock-and-identity-contracts) | 50.42.1.0 | documented |
| Command 35 | Legacy hello. No command reply is documented on 50.42.1.0. Do not send it as a substitute for 145. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#hello--command-145) | 50.42.1.0 | documented |
| Command 63 | Unsupported on this profile (result 3). It is a generation-4 raw-stream switch. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) matrix row 63, status U for 5/MG | 50.42.1.0 | documented |

### Hello final body (command 145)

Offsets are into the command body (they start after command, origin sequence, and result). Source: [TR hello](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#hello--command-145). Firmware 50.42.1.0. Confidence `documented` except where the table itself says a meaning is unresolved.

| Body offset | Width | Field |
|---:|---:|---|
| 0 | 1 | Revision 1 or 3 |
| 1 | 4 | Battery, tenths of a percent |
| 5 | 1 | Opaque state byte |
| 6 | 4 | Unix seconds |
| 10 | 4 | Fractional ticks, units 1/32768 s. The field is 4 bytes even though the clock fraction has 16 significant bits. |
| 14 | 11 | Identity text block A |
| 25 | 24 | Opaque identity block |
| 49 | 30 | Identity text block B |
| 79 | 4 | Version-specific word, value 13 |
| 83 | 4 | Opaque cached word |
| 87 | 4 | Opaque state word |
| 91 | 3 | Version components. The baseline contract's example components are 50, 42, 1. |
| 94 | 4 | Version suffix 0 |
| 98 | 3 | Opaque three-byte field |
| 101 | 1 | Profile/configuration value, 0–2 |
| 102 | 1 | Opaque cached state byte |
| 103 | 4 | Preparation status bits |
| 107 | 4 | Revision 3 only: opaque cached state word |

The final reply reports success even if preparation bits are set. Bit `0x10` means identity block A could not be filled. Bit `0x20` means the same for block B. Revision 3 can set `0x40`; that bit's full meaning is `unconfirmed`. Other preparation bits are preserved, not decoded.

An earlier capture (firmware 50.38.1.0) anchored a firmware field at a different payload index (`pay[93]`) ([BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) command-response section). Do not use that index for 50.42.1.0. Confidence of the old index: `observed` on 50.38.1.0 only.

Identity blocks can contain personal device information. Logs and exports redact them.

### Data-range final body (command 34)

65 bytes. Offsets are command-body offsets. Source: [TR data range](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#data-range--command-34). Firmware 50.42.1.0.

| Body offset | Width | Field | Confidence |
|---:|---:|---|---|
| 0 | 1 | Revision 1 | documented |
| 1 | 4 | Ring boundary A. Full oldest/erase role unresolved. | unconfirmed |
| 5 | 4 | Read-page cursor | documented |
| 9 | 4 | Write-page cursor | documented |
| 13 | 4 | Acknowledged/trim boundary | documented |
| 17 | 4 | Write-wrap state/count | documented |
| 21 | 4 | Ring capacity in page slots | documented |
| 25 | 4 | Record-count estimate. May fall back to 15 records per page. Not an exact count. | documented |
| 29 | 4 | Derived quantity. Not an all-path formula. | documented |
| 33 and 37 | 4 each | Clock pair from the boundary-A page | documented as a pair; boundary-A units unconfirmed |
| 41 and 45 | 4 each | Clock pair from the trim-side page | documented |
| 49 and 53 | 4 each | Clock pair from the read-side page | documented |
| 57 and 61 | 4 each | Clock pair from the last-written page | documented |

The second member of each clock pair is a 16-bit value stored in 32 bits. Failed page reads use seconds-like `0xFFFFFFFF` and companion 65535. The ring page size used by this contract is 4096 bytes ([TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#historical-synchronization-boundaries-retries-and-range-interpretation)). Positions are page slots, not timestamps. Confidence `documented`.

## 8. History metadata and the end token

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| Metadata packet | Type 49 carries START, END, and COMPLETE. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| Kind values | 1 = START, 2 = END, 3 = COMPLETE. | same | 50.42.1.0 | documented |
| Kind byte | Frame byte 10 (`meta_type`). Described as the generation-4 metadata offset plus 4. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 historical offload" | 5.0 capture on that page; not restated as a 50.42.1.0 field table | observed |
| Token length | 8 bytes. Echo the original bytes. Do not rebuild them from timestamps or from a saved cursor. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| Token location | `frame[21:29]` in that note's half-open slice, which is frame offsets 21 through 28 inclusive. The first `u32` is the trim/read position. The second `u32` is wrap state. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 historical offload"; word meanings in [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | capture for the absolute offsets; 50.42.1.0 for the two-word meaning | observed for the absolute offsets |
| Why the ack matters | Without the ack the strap was observed to resend the same chunk and not advance. With the ack the cursor advanced. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 historical offload" | 5.0 capture | observed |
| Ack frees storage | A successful chunk acknowledgement can allow the strap to reclaim that history. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| Retries | An unanswered END is resent up to four times. A repeat may carry a new clock and the same token. The fifth failed outcome ends the attempt. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#historical-synchronization-boundaries-retries-and-range-interpretation) | 50.42.1.0 | documented |
| Replay | A new attempt starts from the acknowledged boundary when stored positions are valid. Unacknowledged records can recur. Delivery is not exactly-once across disconnects. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#interruption-and-recovery) | 50.42.1.0 | documented |
| COMPLETE | Closes the attempt. It does not prove every stored record reached the phone. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#historical-synchronization-boundaries-retries-and-range-interpretation) | 50.42.1.0 | documented |
| Record size bound | History delivery emits positive-length framed records only up to 2140 bytes. | same | 50.42.1.0 | documented |
| Range before history | 5.0/MG requests the data range before history. | [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| Special tokens | First word `0xFFFFFFFF`, or word pairs `0xFEFEFEFE` / `0xFDFDFDFD`, are special wire controls. They are not tokens to synthesize. | same section | 50.42.1.0 | documented |

The absolute kind and token offsets are capture-backed and are the best public description. They are not yet confirmed by a Pulse capture. Live acknowledgements stay disabled until a `pulse-capture-YYYYMMDD` row updates this section. See project rules.

## 9. R18 biometric summary

Source: [SE R18](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r18-biometric-summary). Offsets are absolute format-1 frame offsets. Firmware 50.42.1.0.

The record is **124 bytes**, with CRC-32 at offset 120 covering `[8, 120)`. That length and CRC placement are `documented`.

Layout byte, record index, Unix seconds, heart-rate byte, R-R count, and R-R ticks are the fields the sensor page states as the record's HR/R-R/time content. Other meanings are interpretations and stay `unconfirmed` as physiology even when the byte position is documented. Keep the raw byte beside any interpretation.

| Offset | Width | Field | Confidence of the meaning |
|---:|---|---|---|
| 9 | u8 | Layout 18 | documented |
| 11 | u32 | Record index. Not a timestamp. | documented |
| 15 | u32 | Unix seconds | documented |
| 22 | u8 | Heart rate, bpm. Quality context is byte 36. | documented as a bpm field; when to hide it is a project rule |
| 23 | u8 | R-R count. At most four complete positive words in this layout. | documented |
| 24 + 2i | u16 | R-R ticks, 1/1024 s. Milliseconds = `(ticks * 1000 + 512) / 1024` for positive ticks. | documented |
| 33 | u8 | Cardiac-adjacent flags | unconfirmed |
| 36 | u8 | HR/R-R quality flags. Preserve the raw byte. Bit 7's validity story is not established for this baseline. | documented as flags; bit meanings unconfirmed |
| 37 | u8 | Alternate HR in bpm. Do not show it. Validity for this baseline is unresolved. | unconfirmed |
| 38 | u16 | Packed word next to the R-R block | unconfirmed |
| 41 | f32 | Dynamic acceleration, retained convention is g, finite values in `[0, 8]` | unconfirmed as a physical unit |
| 45, 49, 53 | f32 | Per-axis acceleration, retained convention is g | unconfirmed as a physical unit |
| 57 | u16 | Cumulative step/motion counter. Hardware count unless a software override is active. Not a midnight-reset daily total. The counter is described as over-counting relative to an official total. | documented as a counter; equality with any official step total is unconfirmed |
| 59 | u16 | Cadence-like raw value stored from a u8, high byte 0. Not a documented steps-per-minute unit. | documented as raw |
| 61 | u16 | Hardware counter when software override is active, otherwise 0 | documented |
| 63 | u8 | Activity class: 0 still, 1 walk, 2 run. Other classes become `0xFF` and must not be shown as those three labels. | documented |
| 64 | u8 | `0x10` when software counter override is active, otherwise 0 | documented |
| 69, 71 | i16 | Auxiliary temperatures, retained scale raw/10 °C. Accepted range in the contract is 0–60 °C. Which sensors they are is unresolved. | scale documented as a retained interpretation; sensor identity unconfirmed |
| 73 | u16 | Skin temperature, convention raw/100 °C. Accepted range 5–45 °C. | documented as that convention |
| 75, 77, 79 | u16 | Raw status words. Not sleep stages. | documented as raw |
| 81 | bitfield | Bits 4–5: `(byte >> 4) & 3` is 0 WAKE, 1 STILL, 2 SLEEP, 3 UP. This is band motion/rest state, not light/deep/REM. Bits 0–1, 2–3, and 6–7 do not have a complete vocabulary. | labels documented; they are not a hypnogram |
| 82 | u8 | Sleep-adjacent raw byte. Not a validated SpO₂. Do not show it. | unconfirmed |
| 120 | u32 | CRC-32 over `[8, 120)` | documented |

There is **no validated SpO₂ field** on this record ([SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r18-biometric-summary): the record supplies no established SpO₂ value). Confidence `documented`.

A −50.00 °C skin-temperature sentinel is **not** stated by the 50.42.1.0 R18 contract. The contract's acceptance window is 5–45 °C. Treating −50 as "unavailable" is `unconfirmed`.

### Other history layouts (archive raw; do not decode in this revision)

| Layout | Length | CRC | Source | Firmware | Confidence |
|---:|---:|---|---|---|---|
| 20 | 2140 | at 2136, covers `[8, 2136)` | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r20-optical-blocks) | 50.42.1.0 | documented |
| 21 | 1244 | at 1240, covers `[8, 1240)` | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r21-six-axis-imu) | 50.42.1.0 | documented |
| 22 | 188 | not expanded here | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r22-inner-version) | 50.42.1.0 | documented |
| 26 | 88 | at 84, covers `[8, 84)` | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#r26-compact-optical-window) | 50.42.1.0 | documented |

For layouts 18, 20, 21, and 26, byte 9 is the layout, `u32` at 11 is the record index, and `u32` at 15 is Unix seconds ([SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity)). Confidence `documented`. Unknown layouts are preserved whole.

R21 acceleration scale `1/4096` g and gyroscope scale `2000/32768` °/s are an earlier convention. The fixed layout does not establish them. Confidence `unconfirmed`.

## 10. Events (packet type 48)

| Fact | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| Event number | Frame byte 10 | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-types-record-layouts-and-integrity) | 50.42.1.0 | documented |
| Timestamp | `u32` Unix seconds at frame byte 12 | same | 50.42.1.0 | documented |
| Payload length | `u16` at frame byte 18. Payload starts at frame byte 20. | [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 EVENT" | 5.0 capture on that page (the same page's command-response notes name 50.38.1.0) | observed |
| Event-3 battery payload | On that capture, state of charge is deci-percent (`/10`) at frame 21, millivolts at frame 25, charging bit at frame 30. This is not command 26's `u32` whole percent. | same | that capture | observed |

Named event numbers from the public catalogue ([IM event table](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#4-eventnumber-event-type-48)). The page says these names come from a schema and that the full table lives beside the code. Confidence of each name below is `documented` as a catalogue name, not as proof that 50.42.1.0 emits it.

| Value | Catalogue name | Value | Catalogue name |
|---:|---|---:|---|
| 3 | battery level | 42 | accelerometer saturation |
| 7 | charging on | 46 | raw collection on |
| 8 | charging off | 47 | raw collection off |
| 9 | wrist on | 56 | strap-driven alarm set |
| 10 | wrist off | 57 | strap-driven alarm executed |
| 13 | RTC lost | 58 | app-driven alarm executed |
| 14 | double tap | 59 | strap-driven alarm disabled |
| 17 | temperature level | 60 | haptics fired |
| 23 | bonded | 63 | extended battery information |
| 32 | captouch autothreshold | 96 | high-frequency sync prompt |
| 33 | realtime HR on | 97 | high-frequency sync enabled |
| 34 | realtime HR off | 98 | high-frequency sync disabled |
| 40 | channel-1 saturation | 100 | haptics terminated |
| 41 | channel-2 saturation | | |

A 5.0 capture also emitted unnamed numbers 61, 62, 110, 112, 116, 120, and 123. They stay unnamed. Command 123 is a different namespace (wrist selection) and must not be used as the name of event 123. Source: [BL](https://github.com/ryanbr/noop/blob/main/docs/BLE_REVERSE_ENGINEERING.md) "WHOOP 5.0 EVENT". Confidence `observed`.

## 11. Standard Heart Rate Measurement (`2A37`)

Source: [SIG-HR](https://www.bluetooth.com/specifications/specs/heart-rate-service-1-0/) and the summary in [CO](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_CONCEPTS.md). Confidence `documented`.

| Flags bit | Meaning |
|---|---|
| 0 | 0: heart rate is `u8`. 1: heart rate is `u16`. |
| 1–2 | Sensor contact bits, as defined by the service spec. |
| 3 | Energy Expended field present (`u16`). Skip it when set. |
| 4 | R-R intervals present. Each following value is `u16` in units of 1/1024 s. |

The proprietary R-R millisecond rounding `(ticks * 1000 + 512) / 1024` is specified for packet 40 and R18 ([SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-40-live-hr-and-r-r)). Using that same rounding for `2A37` is a project rule so both paths match. The SIG unit itself is 1/1024 s.

Packet 40 (proprietary live HR) is documented and is **not** used in this milestone. Its prerequisites on 50.42.1.0 are unresolved ([CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#whoop-5mg) live-HR row: "current acceptance and complete prerequisites unresolved"). Confidence of the layout: `documented`. Confidence that command 3 alone starts it on 50.42.1.0: `unconfirmed`.

| Packet 40 offset | Field | Source | Confidence |
|---:|---|---|---|
| 10 | `u32` Unix seconds | [SE](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_SENSORS.md#packet-40-live-hr-and-r-r) | documented |
| 14 | `u16` extra time field. Scale unresolved. | same | unconfirmed |
| 16 | `u8` heart rate, bpm | same | documented |
| 17 | `u8` interval count | same | documented |
| 18 + 2i | `u16` R-R ticks, 1/1024 s | same | documented |

## 12. Deny-list

These command numbers are not sent. There is no release-build override. "Supported" in a public matrix means the strap may accept them. It does not mean Pulse sends them.

| Number | Documented identifier | Why it is denied | Source | Firmware | Confidence |
|---:|---|---|---|---|---|
| 25 | `FORCE_TRIM` | Invasive history cursor. Not a substitute for a committed-chunk ack. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#destructive-commands--do-not-send), [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 5/MG and 4 share the number | documented |
| 29 | `REBOOT_STRAP` | Reboots the strap and drops the link. An empty body on the 5/MG path was confirmed to reboot a 5.0. Stored data is kept. Still denied. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 29; [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md) payload notes | 5.0 hardware-confirmed for the reboot effect | documented |
| 32 | `POWER_CYCLE_STRAP` | Power-cycles the strap. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#destructive-commands--do-not-send) | number shared | documented |
| 33 | `SET_READ_POINTER` | Invasive cursor mutation. Not a recovery method. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 33; [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#history-sequencing-and-storage-ownership) | 50.42.1.0 | documented |
| 36 | `START_FIRMWARE_LOAD` | Firmware write. Matrix marks it unsupported on 5/MG. Still denied. | [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#destructive-commands--do-not-send) | catalogue | documented |
| 37 | `LOAD_FIRMWARE_DATA` | Firmware write. Unsupported on the 5/MG matrix. Still denied. | same | catalogue | documented |
| 38 | `PROCESS_FIRMWARE_IMAGE` | Firmware write. Unsupported on the 5/MG matrix. Still denied. | same | catalogue | documented |
| 45 | `ENTER_BLE_DFU` | Enters a DFU bootloader. Unsupported on the 5/MG matrix. Still denied. | same | catalogue | documented |
| 99 | `RESET_FUEL_GAUGE` | Resets the fuel gauge. Unsupported on the 5/MG matrix. Still denied. | same | catalogue | documented |
| 107 | `ENABLE_OPTICAL_DATA` on 5/MG | Can change persistent optical collection. Denied until a later spec revision studies it. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 107 | 50.42.1.0 name | documented name; effect details live in the configuration page and are not expanded here |
| 119 | `SET_DEVICE_CONFIG_VALUE` | Config write. Denied in this revision. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 119 | 50.42.1.0 | documented |
| 120 | `SET_FF_VALUE` | Feature-flag write. Denied in this revision. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 120 | 50.42.1.0 | documented |
| 142 | `START_FIRMWARE_LOAD_NEW` | Firmware image transfer. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 142; [IM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_IMPLEMENTATION.md#destructive-commands--do-not-send) | 50.42.1.0 | documented |
| 143 | `LOAD_FIRMWARE_DATA_NEW` | Firmware image transfer. | same | 50.42.1.0 | documented |
| 144 | `PROCESS_FIRMWARE_IMAGE_NEW` | Firmware image transfer. | same | 50.42.1.0 | documented |
| 153 | `TOGGLE_PERSISTENT_R20` | Persistent optical policy. Denied until studied. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 153 | 50.42.1.0 | documented name |
| 154 | `TOGGLE_PERSISTENT_R21` | Persistent IMU policy. Denied until studied. | [CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md) row 154 | 50.42.1.0 | documented name |

Commands 155–159 are certificate and authorization operations ([CM](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_COMMANDS.md#image-transfer-command-boundaries)). They are not used. Confidence `documented` that the family exists. Pulse does not send them.

## 13. Golden frame 1 — client hello

Published bytes, command 145, revision 1, sequence 1, header word bytes `00 01`:

```text
AA 01 08 00 00 01 E6 71 23 01 91 01 36 3E 5C 8D
```

| Check | Value | Source | Firmware | Confidence |
|---|---|---|---|---|
| The 16 bytes above | Fixed client hello | [W5](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_WHOOP5.md#connection-and-frame-format) | 50.42.1.0 | documented |
| CRC-16 over the first 6 bytes | `0x71E6`, stored little-endian as `E6 71` | Same bytes, parameters in [TR](https://github.com/ryanbr/noop/blob/main/docs/PROTOCOL_TRANSPORT.md#format-1-framing). Recomputed while writing this spec. | 50.42.1.0 | documented |
| CRC-32 over bytes `[8, 12)` | `0x8D5C3E36`, stored little-endian as `36 3E 5C 8D` | same | 50.42.1.0 | documented |
| Declared length | `0x0008`. Complete size 16. `(8−4)` is divisible by 4. | same | 50.42.1.0 | documented |

The locked copy is `testing/golden/src/main/resources/frames/hello-command.hex`. The codec that must reproduce it is not in this revision.

## 14. Project rules

These are decisions for Pulse. They are not additional strap facts.

1. No `INTERNET` permission. The app is local-only.
2. `:wire` is pure JVM. The codec, when it is written, is written from this spec only.
3. Command writes use write-with-response.
4. Scan filter is the service UUID `fd4b0001-cce1-4033-93ce-002d5875f58a`. Do not trust the advertised name.
5. Standard Heart Rate Measurement is the live HR source for the first milestone. Packet 40 is deferred.
6. Clock: read 147 before history. Send 146 only when the strap clock differs from the phone by more than 2 seconds, then read 147 again. The 2-second threshold is ours.
7. History start body is `00`. If the result is 0 or 3, one retry with an empty body is allowed, and the form that worked is added to this spec. Do not treat the two forms as the same without that result.
8. Persist a chunk, then acknowledge it. The acknowledgement is the 8 token bytes copied from the frame, prefixed with `01`. Any failure skips the acknowledgement.
9. An empty END is still stored (count 0) and then acknowledged, once acknowledgements are enabled.
10. Disconnect mid-chunk drops the in-memory chunk and sends no acknowledgement.
11. Unknown layouts are stored raw in the same commit as the chunk. They are not dropped.
12. History records of 2140 bytes must fit in the reassembly buffer. Request MTU 247 after connect. 247 is our request, not a strap requirement.
13. Do not enable live HISTORY_ACK until a Pulse capture confirms metadata kind at byte 10 and the 8-byte token at frame offsets 21..28.
14. Heart rate outside 25..230 becomes a null display value. That window is ours. The raw byte stays stored.
15. Skin temperature outside 5..45 °C becomes a null display value. The raw value stays stored.
16. Do not show alternate HR (byte 37) or byte 82.
17. Do not offer SpO₂.
18. The deny-list in section 12 cannot be overridden in a release build.
19. Redact hello identity blocks in logs and exports.

## 15. Open items

| Item | Confidence | What would close it |
|---|---|---|
| Absolute metadata offsets (byte 10, token at 21..28) | observed | `pulse-capture-YYYYMMDD` on a named firmware |
| `fd4b0007` role | unconfirmed | our capture, subscribe and archive only until then |
| Bond type (just-works vs authenticated) | unconfirmed | pairing on more than one phone, recorded with the OS error |
| Empty vs `00` history-start body | unconfirmed | the retry rule in section 14 |
| Packet 40 prerequisites on 50.42.1.0 | unconfirmed | deferred; not required while `2A37` is the live source |
| R18 fields other than time, HR, and R-R | unconfirmed as physiology | keep raw values; do not invent units |
| SpO₂ on the wire | documented absent | do not add a metric |

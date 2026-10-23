# Contributing to Pulse

These rules apply to everyone who writes code or docs for this project, human or agent.

The single input for implementers is [`docs/protocol-spec.md`](docs/protocol-spec.md). It contains facts: UUIDs, byte offsets, field widths, CRC parameters, opcode numbers, message sequences, and observed behaviour. Implementers must not read implementation source from the projects named in the clean-room list below while building a feature. The public prose documents that the spec cites can be consulted to check a fact.

## Clean-room rules

1. **Every fact in the spec carries a provenance note**: which public document it came from (URL plus section), the firmware it was observed on, and a confidence label (`observed`, `documented`, or `unconfirmed`). Facts with no source are not allowed.
2. **No code, comments, identifiers, or file layout may be copied or paraphrased** from those other implementations. That includes class names (avoid `Reassembler`, `Backfiller`, `FrameRouter`, `DeviceFamily`, `BandEntry`, `Substrate`), package structure, and test names. Our module names are `:wire`, `:ble`, `:link`, `:sync`, `:data`, `:metrics`, `:feature:connect`, `:feature:live`, `:feature:sync`, `:feature:debug`, `:app`, `:testing:fakestrap`, and `:testing:golden`.
3. **Do not use these words in our names** (packages, modules, classes, files, resources, or the display name): WHOOP, NOOP, OpenStrap, Strand, Edge. The device may be named nominatively in prose ("works with WHOOP 5.0"). Source URLs in the spec are citations, not our names.
4. **Algorithms come from the papers**, not from another app's implementation. Each metric cites the primary publication and its tests use values worked out from the paper or from public datasets. Metrics are out of scope for this skeleton.
5. **Protocol facts we discover ourselves** (from our own captures of a strap the user owns) are added to the spec with provenance `pulse-capture-YYYYMMDD` and the firmware version.
6. **Separation of roles where practical.** Whoever writes the spec from public docs should not also write the codec in the same sitting. The spec is committed and reviewed first. The codec is then written against the spec alone. This revision stops at the skeleton and spec v0. Do not start the codec here.
7. **Licences to respect.** One public client's licence is PolyForm Noncommercial; its own licence text says the protocol facts are free to reuse. Another client's application, protocol, and analytics code are AGPL-3.0. We use neither project's code, so neither licence applies to our code. `NOTICE` credits the public write-ups as sources of facts.
8. **No circumvention.** We only talk to a strap the user owns, use standard OS bonding, and never send firmware, DFU, flash-erase, force-trim, or reboot commands. The deny-list is in the spec.

## Build guardrails

- `:wire` is a pure JVM module. `./gradlew check` fails if it applies an Android plugin, imports `android.*`, or depends on Android or AndroidX.
- No manifest may declare `android.permission.INTERNET`. `./gradlew check` scans source manifests and the app's merged manifests.
- Display name is **Pulse**. Application id and namespace root are `app.pulse`.
- `minSdk` is 31. `targetSdk` and `compileSdk` are 35.

## Local check

```sh
export ANDROID_HOME="$HOME/android-sdk"   # platform android-35, build-tools 35.0.0
./gradlew check
```

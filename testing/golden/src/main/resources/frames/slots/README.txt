Captured frames go in this directory. They are not in the repo until a tester
saves a scrubbed capture from a strap they own.

captured-hello-response.hex
    Final command-145 response. Zero the identity blocks before committing:
    command-body offsets 14..24, 25..48, and 49..78. Do not commit those bytes
    in the clear.

captured-metadata.hex
    One history metadata frame. Confirm kind at frame byte 10 and the 8-byte
    token at frame offsets 21..28 before any history acknowledgement is enabled.

Neither file is a stand-in. Do not commit a synthesized capture.

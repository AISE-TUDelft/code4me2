"""Framing tests: partial frames, multiple frames, byte preservation, errors."""

from __future__ import annotations

import io
import json

from conftest import fixture_path  # type: ignore[import-not-found]

from telemetry_acp_proxy.framing import (
    CONTENT_LENGTH,
    NEWLINE,
    PARSE_FAILED_CODE,
    FrameReader,
    encode_frame,
    encode_message,
    iter_frames,
)


def _line(message: dict) -> bytes:
    return encode_message(message)


def test_partial_frame_is_buffered_until_complete():
    reader = FrameReader()
    frame_bytes = _line({"jsonrpc": "2.0", "id": 1, "method": "initialize"})

    assert reader.feed(frame_bytes[:5]) == []
    assert reader.buffered_bytes == 5
    frames = reader.feed(frame_bytes[5:])

    assert len(frames) == 1
    assert frames[0].raw == frame_bytes
    assert frames[0].payload["method"] == "initialize"


def test_multiple_frames_in_one_read():
    reader = FrameReader()
    first = _line({"jsonrpc": "2.0", "id": 1, "method": "a"})
    second = _line({"jsonrpc": "2.0", "id": 2, "method": "b"})

    frames = reader.feed(first + second)

    assert [frame.payload["id"] for frame in frames] == [1, 2]
    assert frames[0].raw == first
    assert frames[1].raw == second


def test_original_bytes_preserved_including_newline():
    unusual = b'{"jsonrpc":"2.0","id":7,"method":"x","params":{"note":"  spaced  "}}\r\n'
    frames = FrameReader().feed(unusual)

    assert frames[0].raw == unusual  # exact bytes, CRLF included
    assert frames[0].body.endswith(b"}")  # body excludes the delimiter
    assert frames[0].payload["params"]["note"] == "  spaced  "


def test_malformed_frame_yields_typed_parse_error_and_never_raises():
    frames = FrameReader().feed(b"{ this is not json }\n")

    assert len(frames) == 1
    assert frames[0].ok is False
    assert frames[0].error_code == PARSE_FAILED_CODE
    assert "json decode error" in (frames[0].parse_error or "")


def test_malformed_frame_does_not_block_later_frames():
    reader = FrameReader()
    good = _line({"jsonrpc": "2.0", "id": 3, "method": "ok"})

    frames = reader.feed(b"{broken}\n" + good)

    assert frames[0].ok is False
    assert frames[1].ok is True
    assert frames[1].payload["method"] == "ok"


def test_content_length_framing_is_supported():
    body = json.dumps({"jsonrpc": "2.0", "id": 9, "method": "z"}).encode("utf-8")
    raw = encode_frame(body, framing=CONTENT_LENGTH)
    reader = FrameReader()

    frames = reader.feed(raw[:10])
    assert frames == []
    frames = reader.feed(raw[10:])

    assert frames[0].framing == CONTENT_LENGTH
    assert frames[0].raw == raw
    assert frames[0].payload["method"] == "z"


def test_iter_frames_handles_chunks_and_flushes_partial_tail():
    stream = io.BytesIO(
        _line({"jsonrpc": "2.0", "id": 1, "method": "a"})
        + _line({"jsonrpc": "2.0", "id": 2, "method": "b"})
        + b'{"jsonrpc":"2.0","id":3'  # incomplete tail, no newline
    )

    frames = list(iter_frames(stream, read_size=7))

    assert [frame.payload["id"] for frame in frames if frame.ok] == [1, 2]
    assert frames[-1].ok is False
    assert frames[-1].error_code == PARSE_FAILED_CODE


def test_newline_framing_constant_is_stable():
    assert NEWLINE == "newline"
    assert CONTENT_LENGTH == "content-length"


def test_frame_reader_round_trips_a_fixture_transcript():
    import json as _json

    messages = _json.loads(fixture_path("acp_transcript.json").read_text())
    payload = b"".join(_line(entry["message"]) for entry in messages)

    frames = list(iter_frames(io.BytesIO(payload)))

    assert len(frames) == len(messages)
    assert all(frame.ok for frame in frames)
    assert all(frame.payload is not None for frame in frames)

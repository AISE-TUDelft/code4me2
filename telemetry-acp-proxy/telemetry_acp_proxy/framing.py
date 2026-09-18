"""Streaming JSON-RPC frame reader/writer for stdio forwarding.

Two framing conventions are supported because an ACP stdio stream may use
either:

* **newline-delimited JSON** - one JSON document per line; and
* **``Content-Length:`` header framing** (LSP style).

The reader is streaming and stateful: it never assumes one message per read.
``feed()`` accepts an arbitrary chunk and returns every complete frame now
decodable, buffering the remainder. A :class:`Frame` exposes the exact original
bytes (including the delimiter) so a caller can forward a frame byte-for-byte
without re-serializing. Malformed input yields a frame with ``parse_error`` set;
it never raises and never discards the stream.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any, BinaryIO, Iterator, Optional

__all__ = [
    "CONTENT_LENGTH",
    "Frame",
    "FrameReader",
    "NEWLINE",
    "encode_frame",
    "encode_message",
    "iter_frames",
]

NEWLINE = "newline"
CONTENT_LENGTH = "content-length"

PARSE_FAILED_CODE = "acp_parse_failed"

DEFAULT_MAX_BODY_BYTES = 64 * 1024 * 1024
DEFAULT_MAX_HEADER_BYTES = 64 * 1024
DEFAULT_READ_SIZE = 64 * 1024

_CONTENT_LENGTH_RE = re.compile(rb"(?im)^content-length\s*:\s*(\d+)\s*$")


@dataclass(frozen=True)
class Frame:
    """One decoded protocol frame.

    ``raw`` is the exact byte sequence consumed for this frame (including the
    newline delimiter or header block), so forwarding it needs no re-encoding.
    """

    raw: bytes
    body: bytes
    payload: Optional[Any]
    parse_error: Optional[str]
    framing: str
    error_code: Optional[str] = None

    @property
    def ok(self) -> bool:
        """Whether the frame parsed as JSON."""
        return self.parse_error is None


def _decode_body(body: bytes) -> tuple[Optional[Any], Optional[str]]:
    try:
        text = body.decode("utf-8")
    except UnicodeDecodeError as error:
        return None, f"utf-8 decode error: {error}"
    try:
        return json.loads(text), None
    except json.JSONDecodeError as error:
        return None, f"json decode error: {error}"


class FrameReader:
    """Incremental framer for a mixture of newline and Content-Length frames."""

    def __init__(
        self,
        *,
        max_body_bytes: int = DEFAULT_MAX_BODY_BYTES,
        max_header_bytes: int = DEFAULT_MAX_HEADER_BYTES,
    ) -> None:
        self._buffer = bytearray()
        self._pending_length: Optional[int] = None
        self._pending_header = b""
        self.max_body_bytes = max_body_bytes
        self.max_header_bytes = max_header_bytes

    @property
    def buffered_bytes(self) -> int:
        """Number of undecoded bytes currently buffered."""
        return len(self._buffer)

    def reset(self) -> None:
        """Drop all buffered bytes and pending state."""
        self._buffer.clear()
        self._pending_length = None
        self._pending_header = b""

    # -- internals ---------------------------------------------------------

    @staticmethod
    def _find_header_end(buffer: bytearray) -> tuple[int, int]:
        crlf = buffer.find(b"\r\n\r\n")
        lf = buffer.find(b"\n\n")
        if crlf != -1 and (lf == -1 or crlf <= lf):
            return crlf, crlf + 4
        if lf != -1:
            return lf, lf + 2
        return -1, -1

    def _looks_like_header(self) -> bool:
        if not self._buffer:
            return False
        head = bytes(self._buffer[:32]).lstrip().lower()
        return head.startswith(b"content-length")

    def _error_frame(self, raw: bytes, message: str, framing: str) -> Frame:
        return Frame(
            raw=raw,
            body=raw,
            payload=None,
            parse_error=message,
            framing=framing,
            error_code=PARSE_FAILED_CODE,
        )

    @staticmethod
    def _parse_headers(header_block: bytes) -> Optional[int]:
        match = _CONTENT_LENGTH_RE.search(header_block)
        if match is None:
            return None
        try:
            length = int(match.group(1))
        except ValueError:
            return None
        return length if length >= 0 else None

    # -- public API --------------------------------------------------------

    def feed(self, data: bytes) -> list[Frame]:
        """Consume ``data`` and return all complete frames decoded so far."""
        if data:
            self._buffer.extend(data)
        frames: list[Frame] = []
        while True:
            frame = self._next_frame()
            if frame is None:
                break
            frames.append(frame)
        return frames

    def _next_frame(self) -> Optional[Frame]:
        if self._pending_length is None:
            return self._start_next_frame()
        return self._finish_content_length_frame()

    def _start_next_frame(self) -> Optional[Frame]:
        if not self._buffer:
            return None

        if self._looks_like_header():
            content_end, block_end = self._find_header_end(self._buffer)
            if content_end == -1:
                if len(self._buffer) > self.max_header_bytes:
                    raw = bytes(self._buffer)
                    self._buffer.clear()
                    return self._error_frame(
                        raw, "header block exceeded maximum size", CONTENT_LENGTH
                    )
                return None
            header_block = bytes(self._buffer[:content_end])
            length = self._parse_headers(header_block)
            if length is None or length > self.max_body_bytes:
                raw = bytes(self._buffer[:block_end])
                del self._buffer[:block_end]
                reason = (
                    "invalid Content-Length header"
                    if length is None
                    else "declared body exceeds maximum frame size"
                )
                return self._error_frame(raw, reason, CONTENT_LENGTH)
            self._pending_length = length
            self._pending_header = bytes(self._buffer[:block_end])
            del self._buffer[:block_end]
            return self._finish_content_length_frame()

        newline = self._buffer.find(b"\n")
        if newline == -1:
            if len(self._buffer) > self.max_body_bytes:
                raw = bytes(self._buffer)
                self._buffer.clear()
                return self._error_frame(
                    raw, "line exceeded maximum frame size", NEWLINE
                )
            return None
        raw = bytes(self._buffer[: newline + 1])
        del self._buffer[: newline + 1]
        body = raw.rstrip(b"\r\n")
        payload, error = _decode_body(body)
        return Frame(
            raw=raw,
            body=body,
            payload=payload,
            parse_error=error,
            framing=NEWLINE,
            error_code=None if error is None else PARSE_FAILED_CODE,
        )

    def _finish_content_length_frame(self) -> Optional[Frame]:
        length = self._pending_length
        assert length is not None
        if len(self._buffer) < length:
            return None
        body = bytes(self._buffer[:length])
        del self._buffer[:length]
        trailing = b""
        if self._buffer.startswith(b"\r\n"):
            trailing = b"\r\n"
            del self._buffer[:2]
        elif self._buffer.startswith(b"\n"):
            trailing = b"\n"
            del self._buffer[:1]
        raw = self._pending_header + body + trailing
        self._pending_length = None
        self._pending_header = b""
        payload, error = _decode_body(body)
        return Frame(
            raw=raw,
            body=body,
            payload=payload,
            parse_error=error,
            framing=CONTENT_LENGTH,
            error_code=None if error is None else PARSE_FAILED_CODE,
        )

    def close(self) -> list[Frame]:
        """Flush a trailing partial frame as an explicit parse error."""
        if not self._buffer and self._pending_length is None:
            return []
        raw = self._pending_header + bytes(self._buffer)
        framing = CONTENT_LENGTH if self._pending_length is not None else NEWLINE
        self.reset()
        return [self._error_frame(raw, "incomplete frame at end of stream", framing)]


def iter_frames(
    stream: BinaryIO, *, read_size: int = DEFAULT_READ_SIZE, reader: Optional[FrameReader] = None
) -> Iterator[Frame]:
    """Yield frames read from a binary ``stream`` until EOF.

    The stream is read incrementally, so partial frames, multiple frames per
    read, and a trailing partial frame are all handled. The reader is flushed on
    EOF, yielding a typed parse-error frame for any incomplete tail.
    """
    active = reader or FrameReader()
    while True:
        chunk = stream.read(read_size)
        if not chunk:
            break
        yield from active.feed(chunk)
    yield from active.close()


# ---------------------------------------------------------------------------
# Writers (used for test/out-of-band frames; never for proxied bytes)
# ---------------------------------------------------------------------------


def encode_frame(body: bytes, *, framing: str = NEWLINE) -> bytes:
    """Encode a raw JSON ``body`` using the requested framing."""
    if framing == NEWLINE:
        return body + b"\n"
    if framing == CONTENT_LENGTH:
        header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
        return header + body
    raise ValueError(f"unknown framing: {framing!r}")


def encode_message(message: Any, *, framing: str = NEWLINE) -> bytes:
    """Serialize ``message`` with compact JSON and wrap it in a frame."""
    body = json.dumps(message, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return encode_frame(body, framing=framing)

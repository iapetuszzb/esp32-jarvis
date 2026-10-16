"""Bounded latest-frame ownership, with explicit PIL buffer release."""
from dataclasses import dataclass
import time


@dataclass
class Frame:
    image: object
    received: float
    sequence: int


class FrameStore:
    def __init__(self, ttl=4, limit=32, clock=time.monotonic):
        self.ttl, self.limit, self.clock = ttl, limit, clock
        self.frames = {}

    def expire(self):
        now = self.clock()
        for frame in self.frames.values():
            if frame.image is not None and now - frame.received > self.ttl:
                frame.image.close()
                frame.image = None

    def put(self, session, image, age=0):
        self.expire()
        previous = self.frames.get(session)
        received = self.clock() - age
        if previous and received < previous.received:
            # A slow old upload must never replace a newer capture.
            image.close()
            return previous.sequence
        if previous is None and len(self.frames) >= self.limit:
            # Metadata is bounded too; only expired sessions may be evicted.
            stale = next((k for k, f in self.frames.items() if f.image is None), None)
            if stale is None:
                image.close()
                raise OverflowError('Too many camera sessions')
            del self.frames[stale]
        sequence = previous.sequence + 1 if previous else 1
        if previous and previous.image is not None:
            previous.image.close()
        self.frames[session] = Frame(image, received, sequence)
        return sequence

    def latest(self, session, copy=True):
        self.expire()
        f = self.frames.get(session)
        age = self.clock() - f.received if f else None
        image = f.image.copy() if copy and f and f.image is not None else None
        return image, age, f

    def close(self):
        for f in self.frames.values():
            if f.image is not None:
                f.image.close()
        self.frames.clear()

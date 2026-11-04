"""Per-user limits on the calls that cost money or queue work (Ask AI, agreement questions, uploads).

A sliding one-minute window per user and kind, kept in the process. Each API replica counts on its
own, so with N replicas a user may reach N times the limit; that still stops a stuck script or a
runaway loop, which is what the limit is for.
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict, deque

from dps.errors import ApiError

WINDOW_SECONDS = 60.0


class RateLimiter:
    def __init__(self, clock=time.monotonic) -> None:
        self._clock = clock
        self._hits: dict[tuple[str, str], deque[float]] = defaultdict(deque)
        self._lock = threading.Lock()

    def check(self, user: str, kind: str, per_minute: int) -> None:
        """Counts one call; raises 429 when ``user`` made ``per_minute`` calls of ``kind`` in the last minute."""
        if per_minute <= 0:
            return
        now = self._clock()
        with self._lock:
            hits = self._hits[(user, kind)]
            while hits and now - hits[0] >= WINDOW_SECONDS:
                hits.popleft()
            if len(hits) >= per_minute:
                wait = max(1, int(WINDOW_SECONDS - (now - hits[0])) + 1)
                raise ApiError(429, "RATE_LIMITED",
                               f"Too many requests. Try again in {wait} seconds.", {"retryAfterSeconds": wait})
            hits.append(now)

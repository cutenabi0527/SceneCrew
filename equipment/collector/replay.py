"""--raw-log로 남긴 수신 원문을 다시 해석한다. 실기 응답을 장비 없이 회귀 시험할 때 쓴다.

사용 (equipment/collector에서):
    uv run python replay.py captures/live-raw.jsonl --out captures/replayed.jsonl

결과 기록은 mode=REPLAY이며 receivedAt은 원래 수신 시각을 쓴다. 실기 검증 횟수로 세지 않는다.
"""

import argparse
import asyncio
import json

import protocol
from decode import decode
from observation import RecordFactory
from sinks import JsonlSink, MemorySink


async def replay(lines, sink, source_ref: str = "replay") -> dict:
    current = {"at": None}
    factory = RecordFactory(collector_id="replay", source_ref=source_ref, mode="REPLAY",
                            clock=lambda: current["at"])
    framers: dict[str | None, protocol.Framer] = {}
    for line in lines:
        if not line.strip():
            continue
        entry = json.loads(line)
        if entry["dir"] != "rx":
            continue
        current["at"] = entry["at"]
        session = entry["sessionId"]
        framer = framers.setdefault(session, protocol.Framer())
        for frame in framer.feed(entry["bytes"].encode("latin-1")):
            msg = protocol.parse(frame)
            readings = decode(msg)
            if msg.kind == "UNKNOWN" or (not readings and msg.kind in ("REP", "SAMPLE")):
                await sink.emit(factory.unparsed(session, frame))
                continue
            origin = "SAMPLE" if msg.kind == "SAMPLE" else "REPORT"
            for reading in readings:
                await sink.emit(factory.reading(session, origin, reading, frame))
    return {
        "sessions": len(framers),
        "discardedBytes": sum(f.discarded for f in framers.values()),
        "overflows": sum(f.overflows for f in framers.values()),
        "pendingBytes": sum(f.pending for f in framers.values()),
    }


async def main() -> None:
    p = argparse.ArgumentParser(description="수신 원문 재해석")
    p.add_argument("raw_log")
    p.add_argument("--out", default=None)
    p.add_argument("--source-ref", default="replay")
    args = p.parse_args()
    sink = JsonlSink(args.out) if args.out else MemorySink()
    with open(args.raw_log, encoding="utf-8") as fh:
        stats = await replay(fh, sink, args.source_ref)
    await sink.close()
    print(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())

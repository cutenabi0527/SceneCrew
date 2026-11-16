"""수집 JSONL을 요약한다. #302·#305 실기 결과표의 수치 근거로 쓴다.

사용 (equipment/collector에서):
    uv run python summarize.py captures/run.jsonl

출력: 실행/세션 수, 수집 시간, SAMPLE 수와 간격(p50/p95/최대), 파싱 실패, 값 상태 분포,
연결 변화와 사유, 최신성 상태 변화. 수신 시각 기준이며 화면 반영 지연은 따로 잰다.
"""

import json
import sys
from collections import Counter
from datetime import datetime


def _t(iso: str) -> float:
    return datetime.fromisoformat(iso.replace("Z", "+00:00")).timestamp()


def _pct(values: list[float], p: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p * (len(s) - 1))))]


def summarize(records: list[dict]) -> dict:
    samples = [r for r in records if r["recordType"] == "READING" and r["param"] == "RF_ANTENNA"]
    gaps: list[float] = []
    for a, b in zip(samples, samples[1:]):
        if a["sessionId"] == b["sessionId"]:
            gaps.append(round(_t(b["receivedAt"]) - _t(a["receivedAt"]), 3))
    links = [r for r in records if r["recordType"] == "LINK"]
    status_changes = []
    last = None
    for r in records:
        if r["recordType"] == "STATUS":
            key = (r["linkState"], r["metering"])
            if key != last:
                status_changes.append({"at": r["receivedAt"], "link": key[0], "metering": key[1]})
                last = key
    last_seq: dict[str, int] = {}
    ordered = True
    for r in records:
        if r["sequence"] <= last_seq.get(r["runId"], 0):
            ordered = False
        last_seq[r["runId"]] = r["sequence"]
    return {
        "records": len(records),
        "runs": len({r["runId"] for r in records}),
        "sessions": len({r["sessionId"] for r in records if r["sessionId"]}),
        "modes": sorted({r["mode"] for r in records}),
        "from": records[0]["receivedAt"] if records else None,
        "to": records[-1]["receivedAt"] if records else None,
        "durationS": round(_t(records[-1]["receivedAt"]) - _t(records[0]["receivedAt"]), 1) if records else 0,
        "samples": len(samples),
        "sampleGapS": {"p50": _pct(gaps, 0.5), "p95": _pct(gaps, 0.95), "max": max(gaps) if gaps else None},
        "unparsed": sum(1 for r in records if r["recordType"] == "UNPARSED"),
        "valueStatus": dict(Counter(r["valueStatus"] for r in records if r["recordType"] == "READING")),
        "sequenceOrdered": ordered,
        "links": [{"at": r["receivedAt"], "state": r["linkState"], "reason": r["reason"]} for r in links],
        "statusChanges": status_changes,
    }


def main(path: str) -> None:
    with open(path, encoding="utf-8") as fh:
        records = [json.loads(line) for line in fh if line.strip()]
    print(json.dumps(summarize(records), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main(sys.argv[1])

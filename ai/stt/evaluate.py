"""STT 모델별 정확도(CER, 개체 인식률)와 처리 시간을 비교한다.

manifest(JSONL)는 라벨 샘플 형식(ai/labeling/samples)을 그대로 받는다. 사용하는 필드:
    {"id": "RPT-0001", "text": "정답 문장",
     "entities": [{"type": "ZONE", "start": 0, "end": 5, "text": "B 체험존"}, ...]}
entities는 문자열 배열도 허용한다. 음성 파일은 <audio-dir>/<id>.<ext> 에서 찾는다.
manifest의 모든 id에 음성이 있어야 하며, 없으면 누락 id를 출력하고 실패한다.

사용법:
    uv run evaluate.py manifest.jsonl audio_dir --models large-v3-turbo small
    uv run evaluate.py manifest.jsonl audio_dir --prompt "" -o result.jsonl
    uv run evaluate.py manifest.jsonl audio_dir --allow-missing
"""

import argparse
import json
import sys
import time
from pathlib import Path

from audio import load_audio
from transcribe import DEFAULT_MODEL, DEFAULT_PROMPT, Transcriber, normalize

AUDIO_EXTS = (".wav", ".m4a", ".mp3", ".webm", ".ogg", ".flac")


def edit_distance(a, b):
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def entity_texts(sample):
    return [e["text"] if isinstance(e, dict) else e for e in sample.get("entities", [])]


def find_audio(audio_dir, sample_id):
    for ext in AUDIO_EXTS:
        path = audio_dir / f"{sample_id}{ext}"
        if path.exists():
            return path
    return None


def match_audio(samples, audio_dir):
    """(sample, 음성 경로) 목록과 음성이 없는 id 목록을 반환한다."""
    items, missing = [], []
    for s in samples:
        path = find_audio(audio_dir, s["id"])
        if path is None:
            missing.append(s["id"])
        else:
            items.append((s, path))
    return items, missing


def score(sample, hyp):
    """샘플 1건의 편집 거리, 정답 글자 수, 놓친 개체를 계산한다."""
    ref_n, hyp_n = normalize(sample["text"]), normalize(hyp)
    entities = entity_texts(sample)
    return {
        "distance": edit_distance(ref_n, hyp_n),
        "chars": len(ref_n),
        "entities": len(entities),
        "missedEntities": [e for e in entities if normalize(e) not in hyp_n],
    }


def summarize(scores, elapsed):
    n = len(scores)
    chars = sum(s["chars"] for s in scores)
    entities = sum(s["entities"] for s in scores)
    missed = sum(len(s["missedEntities"]) for s in scores)
    return {
        "samples": n,
        "cer": round(sum(s["distance"] for s in scores) / max(1, chars), 4),
        "exactMatch": round(sum(s["distance"] == 0 for s in scores) / max(1, n), 4),
        "entityRecall": round((entities - missed) / max(1, entities), 4),
        "avgSec": round(elapsed / max(1, n), 3),
    }


def evaluate(transcriber, items, prompt):
    elapsed = 0.0
    scores, rows = [], []
    for sample, path in items:
        audio = load_audio(path)
        started = time.perf_counter()
        hyp = transcriber.transcribe(audio, initial_prompt=prompt)["text"]
        elapsed += time.perf_counter() - started
        s = score(sample, hyp)
        scores.append(s)
        rows.append({
            "id": sample["id"],
            "ref": sample["text"],
            "hyp": hyp,
            "cer": round(s["distance"] / max(1, s["chars"]), 4),
            "missedEntities": s["missedEntities"],
        })
    return summarize(scores, elapsed), rows


def main(argv=None):
    parser = argparse.ArgumentParser(description="STT 모델 정확도 비교")
    parser.add_argument("manifest")
    parser.add_argument("audio_dir")
    parser.add_argument("--models", nargs="+", default=[DEFAULT_MODEL])
    parser.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="고유 명칭 힌트 (빈 문자열이면 사용 안 함)")
    parser.add_argument("--allow-missing", action="store_true", help="음성이 없는 샘플을 건너뛰고 계속한다")
    parser.add_argument("-o", "--output", help="샘플별 결과 JSONL 저장 경로")
    args = parser.parse_args(argv)

    with open(args.manifest, encoding="utf-8") as f:
        samples = [json.loads(line) for line in f if line.strip()]
    items, missing = match_audio(samples, Path(args.audio_dir))
    if missing:
        print(f"음성 누락 {len(missing)}/{len(samples)}건: {', '.join(missing)}", file=sys.stderr)
        if not args.allow_missing:
            return 1
    if not items:
        print("평가할 음성이 없습니다.", file=sys.stderr)
        return 1

    out = open(args.output, "w", encoding="utf-8") if args.output else None
    print("model\tsamples\tCER\texactMatch\tentityRecall\tavgSec")
    for model in args.models:
        transcriber = Transcriber(model, args.device)
        summary, rows = evaluate(transcriber, items, args.prompt)
        print(f"{model}\t{summary['samples']}\t{summary['cer']:.2%}\t{summary['exactMatch']:.2%}\t{summary['entityRecall']:.2%}\t{summary['avgSec']}", flush=True)
        if out:
            for row in rows:
                out.write(json.dumps({"model": model, **row}, ensure_ascii=False) + "\n")
        del transcriber
    if out:
        out.close()
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(main())

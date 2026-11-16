"""현장 보고 이해 모델 학습.

학습 분할로 학습하고, 매 epoch 검증 분할의 (유형 macro F1 + 상태 macro F1 + 개체 exact span F1) / 3이 가장 높은
epoch를 출력 폴더에 저장한다. 끝나면 저장한 묶음을 다시 읽어 검증·평가 분할을 측정하고 training.json에 남긴다.
기본값은 기획 AI 06의 학습 설정이다.

사용법:
    uv run train.py                                          # 전체 데이터, klue/roberta-base
    uv run train.py --limit 160 20 20 --epochs 2             # 소규모 동작 확인
    uv run train.py --output ../checkpoints/report-v1        # 출력 폴더 지정 (이미 있으면 멈춤)
"""

import argparse
import hashlib
import json
import math
import platform
import random
import sys
import time
from datetime import datetime
from functools import partial
from pathlib import Path

import torch
import transformers
from torch.utils.data import DataLoader
from transformers import AutoTokenizer, get_linear_schedule_with_warmup

import data
import metrics
from model import LOSS_WEIGHTS, ReportModel, predict

HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT_ROOT = HERE.parent / "checkpoints"
BASE_MODEL = "klue/roberta-base"
DEFAULTS = {"epochs": 5, "batch_size": 16, "lr": 2e-5, "weight_decay": 0.01, "warmup": 0.1, "seed": 42,
            "max_length": data.MAX_LENGTH}
NO_DECAY = ("bias", "LayerNorm.weight")


def set_seed(seed):
    random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def evaluate(model, features, rows, pad_id, batch_size, device):
    """검증 지표. score는 epoch 선택 기준인 세 F1의 평균이다."""
    model.eval()
    pred = {"category": [], "state": [], "spans": []}
    loss_sum = 0.0
    for i in range(0, len(features), batch_size):
        chunk = features[i:i + batch_size]
        batch = {k: v.to(device) for k, v in data.collate(chunk, pad_id).items()}
        with torch.no_grad():
            loss_sum += model(**batch)["loss"].item() * len(chunk)
        out = predict(model, batch)
        pred["category"] += out["category"]
        pred["state"] += out["state"]
        pred["spans"] += [metrics.tags_to_spans(tags, f["offsets"]) for tags, f in zip(out["entity"], chunk)]
    category = metrics.macro_f1([f["category"] for f in features], pred["category"], range(len(data.CATEGORIES)))
    state = metrics.macro_f1([f["state"] for f in features], pred["state"], range(len(data.STATES)))
    entity = metrics.span_f1([metrics.gold_spans(r) for r in rows], pred["spans"])
    return {"loss": loss_sum / len(features), "categoryMacroF1": category, "stateMacroF1": state,
            "entitySpan": entity, "score": (category + state + entity["f1"]) / 3}


def train(model, tokenizer, splits, output, epochs, batch_size, lr, weight_decay, warmup, seed, max_length,
          device="cpu", log=print):
    """학습 후 기록(dict)을 돌려준다. 가장 좋은 epoch의 모델은 output에 저장된다."""
    set_seed(seed)
    features = {name: [data.encode(r, tokenizer, max_length) for r in rows] for name, rows in splits.items()}
    pad_id = tokenizer.pad_token_id
    loader = DataLoader(features["train"], batch_size=batch_size, shuffle=True,
                        collate_fn=partial(data.collate, pad_id=pad_id),
                        generator=torch.Generator().manual_seed(seed))
    model.to(device)
    params = [
        {"params": [p for n, p in model.named_parameters() if not n.endswith(NO_DECAY)], "weight_decay": weight_decay},
        {"params": [p for n, p in model.named_parameters() if n.endswith(NO_DECAY)], "weight_decay": 0.0},
    ]
    optimizer = torch.optim.AdamW(params, lr=lr)
    total_steps = len(loader) * epochs
    scheduler = get_linear_schedule_with_warmup(optimizer, math.ceil(total_steps * warmup), total_steps)

    history, best = [], None
    for epoch in range(1, epochs + 1):
        model.train()
        started, loss_sums = time.time(), {"loss": 0.0, "category": 0.0, "state": 0.0, "entity": 0.0}
        for batch in loader:
            out = model(**{k: v.to(device) for k, v in batch.items()})
            out["loss"].backward()
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad()
            loss_sums["loss"] += out["loss"].item()
            for k, v in out["losses"].items():
                loss_sums[k] += v.item()
        valid = evaluate(model, features["valid"], splits["valid"], pad_id, batch_size, device)
        record = {"epoch": epoch, "trainLoss": {k: v / len(loader) for k, v in loss_sums.items()},
                  "valid": valid, "seconds": round(time.time() - started, 1)}
        history.append(record)
        improved = best is None or valid["score"] > best["valid"]["score"]
        if improved:
            best = record
            model.save(output, tokenizer)
        log(f"epoch {epoch}/{epochs} train loss {record['trainLoss']['loss']:.4f} | valid 유형 "
            f"{valid['categoryMacroF1']:.3f} 상태 {valid['stateMacroF1']:.3f} 개체 {valid['entitySpan']['f1']:.3f} "
            f"→ {valid['score']:.3f}{' (저장)' if improved else ''} | {record['seconds']}s")

    # 저장한 묶음을 다시 읽어 측정한다. 검증 지표가 저장 시점과 같으면 저장·불러오기가 정상이다.
    reloaded = ReportModel.load(output, device)
    reloaded_tokenizer = AutoTokenizer.from_pretrained(output)
    reloaded_features = {name: [data.encode(r, reloaded_tokenizer, max_length) for r in rows]
                         for name, rows in splits.items()}
    result = {name: evaluate(reloaded, reloaded_features[name], splits[name], pad_id, batch_size, device)
              for name in ("valid", "test")}
    reload_matches = all(
        math.isclose(result["valid"][k], best["valid"][k], abs_tol=1e-6)
        for k in ("categoryMacroF1", "stateMacroF1", "score"))
    log(f"best epoch {best['epoch']} | 다시 불러온 모델 검증 지표 일치: {reload_matches}")
    log(f"test 유형 {result['test']['categoryMacroF1']:.3f} 상태 {result['test']['stateMacroF1']:.3f} "
        f"개체 {result['test']['entitySpan']['f1']:.3f}")
    misaligned = {name: sum(data.misaligned_entities(r, f["offsets"]) for r, f in zip(splits[name], features[name]))
                  for name in splits}
    return {"history": history, "bestEpoch": best["epoch"], "valid": result["valid"], "test": result["test"],
            "reloadMatchesBest": reload_matches,
            "entities": {name: sum(len(r["entities"]) for r in rows) for name, rows in splits.items()},
            "misalignedEntities": misaligned}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dataset", type=Path, default=data.DEFAULT_DATASET)
    parser.add_argument("--split", type=Path, help="분할 JSON (기본: 데이터 파일 옆 .split.json)")
    parser.add_argument("--model", default=BASE_MODEL, help="사전학습 인코더 이름 또는 경로")
    parser.add_argument("--output", type=Path, help="저장 폴더 (기본: ai/checkpoints/report-<시각>). 이미 있으면 멈춤")
    parser.add_argument("--limit", type=int, nargs=3, metavar=("TRAIN", "VALID", "TEST"),
                        help="분할별 건수를 seed로 뽑아 줄임 (소규모 동작 확인)")
    parser.add_argument("--device", default="auto", help="auto / cpu / cuda")
    for key, value in DEFAULTS.items():
        parser.add_argument(f"--{key.replace('_', '-')}", type=type(value), default=value)
    args = parser.parse_args()

    output = args.output or DEFAULT_OUTPUT_ROOT / f"report-{datetime.now():%Y%m%d-%H%M%S}"
    if output.exists():
        sys.exit(f"{output}가 이미 있습니다. 덮어쓰지 않으니 다른 폴더를 지정하세요.")
    device = ("cuda" if torch.cuda.is_available() else "cpu") if args.device == "auto" else args.device
    split_path = args.split or args.dataset.with_suffix(".split.json")
    limits = dict(zip(data.SPLITS, args.limit)) if args.limit else None
    splits = data.load_splits(args.dataset, split_path, limits, args.seed)
    print(f"데이터 {args.dataset.name}: " + ", ".join(f"{k} {len(v)}건" for k, v in splits.items())
          + f" | {args.model} | {device}")

    set_seed(args.seed)
    tokenizer = AutoTokenizer.from_pretrained(args.model)
    model = ReportModel.from_pretrained_encoder(args.model)
    hyper = {key: getattr(args, key) for key in DEFAULTS}
    started = datetime.now().astimezone()
    record = train(model, tokenizer, splits, output, device=device, **hyper)

    meta = {
        "modelVersion": output.name,
        "baseModel": args.model,
        "trainedAt": started.isoformat(timespec="seconds"),
        "dataset": {"file": args.dataset.name, "sha256": sha256(args.dataset),
                    "split": split_path.name, "splitSha256": sha256(split_path),
                    "rows": {k: len(v) for k, v in splits.items()}, "limit": limits},
        "hyperparameters": hyper,
        "lossWeights": LOSS_WEIGHTS,
        "selection": "valid (categoryMacroF1 + stateMacroF1 + entitySpan.f1) / 3",
        "environment": {"device": device, "python": platform.python_version(), "torch": torch.__version__,
                        "transformers": transformers.__version__,
                        "gpu": torch.cuda.get_device_name(0) if device == "cuda" else None},
        **record,
    }
    (output / "training.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"→ {output}")


if __name__ == "__main__":
    main()

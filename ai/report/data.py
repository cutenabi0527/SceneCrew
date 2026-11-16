"""학습 데이터 JSONL과 분할 파일을 읽어 토큰 단위 학습 입력으로 바꾼다.

라벨 목록은 ai/labeling/label_schema.json을 따른다. 개체는 BIO 태그(O + B/I × 4종 = 9개)로 바꾸고,
special 토큰과 padding은 -100으로 두어 손실·평가에서 뺀다.
"""

import json
import random
import sys
from pathlib import Path

import torch

LABELING = Path(__file__).resolve().parents[1] / "labeling"
sys.path.insert(0, str(LABELING))
sys.path.insert(0, str(LABELING / "generate"))
import split as splitter  # noqa: E402
import validate_labels as vl  # noqa: E402

DEFAULT_DATASET = LABELING / "dataset" / "report_dataset_v1.jsonl"
SPLITS = ("train", "valid", "test")
IGNORE = -100
MAX_LENGTH = 512

CATEGORIES = vl.CATEGORIES
STATES = vl.STATES
ENTITY_TYPES = vl.ENTITY_TYPES
ENTITY_TAGS = ["O"] + [f"{p}-{t}" for t in ENTITY_TYPES for p in "BI"]


def load_splits(dataset=DEFAULT_DATASET, split_path=None, limits=None, seed=42):
    """{분할: [행]}. limits={분할: 건수}를 주면 seed로 섞어 그만큼만 쓴다.

    아래 중 하나라도 있으면 멈춘다. 분할 검사는 check_split.py와 같은 함수(split.assignment_errors)다.
    - 라벨 검증 오류 (validate_labels.py)
    - 검수 승인(APPROVED)이 아닌 행
    - 분할 누락·두 번 이상 배정·데이터에 없는 id, 상황 단위가 둘 이상의 분할에 걸침
    """
    dataset = Path(dataset)
    errors, valid = vl.validate(vl.load(dataset))
    if errors:
        raise ValueError(f"{dataset.name} 검증 오류 {len(errors)}건: {errors[0]}")
    rows = [r for _, r in valid]
    pending = [r["id"] for r in rows if r["reviewStatus"] != "APPROVED"]
    if pending:
        raise ValueError(f"{dataset.name}에 검수 승인(APPROVED)이 아닌 행 {len(pending)}건: {', '.join(pending[:5])}")
    split_path = Path(split_path) if split_path else dataset.with_suffix(".split.json")
    split_data = json.loads(split_path.read_text(encoding="utf-8"))
    errors = splitter.assignment_errors(rows, split_data)
    if errors:
        raise ValueError(f"{split_path.name} 분할 오류 {len(errors)}건: {errors[0]}")
    by_id = {r["id"]: r for r in rows}
    result = {}
    for name in SPLITS:
        rows = [by_id[i] for i in split_data[name]]
        limit = (limits or {}).get(name)
        if limit is not None and limit < len(rows):
            rows = random.Random(seed).sample(rows, limit)
        result[name] = rows
    return result


def char_tags(row):
    """문장 글자(코드포인트)마다 BIO 태그."""
    tags = ["O"] * len(row["text"])
    for ent in row["entities"]:
        tags[ent["start"]] = f"B-{ent['type']}"
        for i in range(ent["start"] + 1, ent["end"]):
            tags[i] = f"I-{ent['type']}"
    return tags


def encode(row, tokenizer, max_length=MAX_LENGTH):
    """토큰 id·라벨 묶음. 토큰의 태그는 토큰이 시작하는 글자의 태그다.

    개체 경계가 토큰 중간에 걸리면(예: '프로젝터가'가 한 토큰) 그 개체는 토큰 단위로 정확히 표현되지 않는다.
    이런 개체 수는 misaligned_entities로 따로 센다.
    """
    enc = tokenizer(row["text"], truncation=True, max_length=max_length,
                    return_offsets_mapping=True, return_special_tokens_mask=True)
    tags = char_tags(row)
    entity_tags = []
    for (start, end), special in zip(enc["offset_mapping"], enc["special_tokens_mask"]):
        if special or start == end:
            entity_tags.append(IGNORE)
        else:
            entity_tags.append(ENTITY_TAGS.index(tags[start]))
    return {
        "id": row["id"],
        "input_ids": enc["input_ids"],
        "offsets": [tuple(o) for o in enc["offset_mapping"]],
        "category": CATEGORIES.index(row["category"]),
        "state": STATES.index(row["reportedState"]),
        "entity_tags": entity_tags,
    }


def misaligned_entities(row, offsets):
    """시작·끝이 토큰 경계와 맞지 않는 개체 수."""
    starts = {s for s, e in offsets if s != e}
    ends = {e for s, e in offsets if s != e}
    return sum(ent["start"] not in starts or ent["end"] not in ends for ent in row["entities"])


def collate(features, pad_id):
    """배치 텐서. 길이는 배치 안 최대 길이에 맞춘다."""
    width = max(len(f["input_ids"]) for f in features)

    def pad(key, value):
        return torch.tensor([f[key] + [value] * (width - len(f[key])) for f in features])

    input_ids = pad("input_ids", pad_id)
    return {
        "input_ids": input_ids,
        "attention_mask": (torch.arange(width)[None, :] < torch.tensor([len(f["input_ids"]) for f in features])[:, None]).long(),
        "category": torch.tensor([f["category"] for f in features]),
        "state": torch.tensor([f["state"] for f in features]),
        "entity_tags": pad("entity_tags", IGNORE),
    }

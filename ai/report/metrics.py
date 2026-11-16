"""유형·상태 macro F1과 개체 exact span F1.

개체 정답은 토큰 태그가 아니라 데이터의 글자 범위(start, end)다. 토큰 경계와 맞지 않는 개체는 예측이 맞아도
범위가 달라 틀린 것으로 센다.
"""

from data import ENTITY_TAGS


def macro_f1(gold, pred, labels):
    """정답이나 예측에 한 번이라도 나온 라벨의 F1 평균."""
    scores = []
    for label in labels:
        tp = sum(g == p == label for g, p in zip(gold, pred))
        fp = sum(p == label != g for g, p in zip(gold, pred))
        fn = sum(g == label != p for g, p in zip(gold, pred))
        if tp + fp + fn:
            scores.append(2 * tp / (2 * tp + fp + fn))
    return sum(scores) / len(scores) if scores else 0.0


def tags_to_spans(tags, offsets):
    """토큰 태그 id → {(개체 type, start, end)}. 앞 토큰과 type이 다른 I-는 새 개체로 시작한다."""
    spans, current = set(), None
    for tag_id, (start, end) in zip(tags, offsets):
        tag = ENTITY_TAGS[tag_id] if tag_id >= 0 else "O"
        if tag == "O" or start == end:
            if current:
                spans.add(tuple(current))
            current = None
            continue
        prefix, kind = tag.split("-", 1)
        if prefix == "I" and current and current[0] == kind:
            current[2] = end
        else:
            if current:
                spans.add(tuple(current))
            current = [kind, start, end]
    if current:
        spans.add(tuple(current))
    return spans


def gold_spans(row):
    return {(e["type"], e["start"], e["end"]) for e in row["entities"]}


def span_f1(gold, pred):
    """gold·pred는 문장별 개체 집합 목록. 전체를 합쳐(micro) 정밀도·재현율·F1."""
    tp = sum(len(g & p) for g, p in zip(gold, pred))
    n_pred = sum(len(p) for p in pred)
    n_gold = sum(len(g) for g in gold)
    precision = tp / n_pred if n_pred else 0.0
    recall = tp / n_gold if n_gold else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    return {"precision": precision, "recall": recall, "f1": f1}

"""정규화 좌표(0~1) 점을 구역 ROI 다각형으로 나눠 센다.

한 점은 먼저 나온 구역 하나에만 센다(대표 점 규칙, docs/ai/crowd-density-input-review.md 2절).
labeling/crowd/count_rois.py의 정답 계산과 같은 규칙이다.
"""

import numpy as np


def inside(points, polygon):
    """points (N,2)가 polygon [(x,y),...] 안에 있는지 (N,) bool. 짝수-홀수 규칙."""
    x, y = points[:, 0], points[:, 1]
    result = np.zeros(len(points), dtype=bool)
    n = len(polygon)
    for i in range(n):
        xa, ya = polygon[i]
        xb, yb = polygon[(i + 1) % n]
        if ya == yb:
            continue
        crosses = (ya > y) != (yb > y)
        result ^= crosses & (x < (xb - xa) * (y - ya) / (yb - ya) + xa)
    return result


def count_by_zone(points, weights, polygons):
    """polygons {zoneId: [(x,y),...]} 순서대로 남은 점의 무게 합."""
    points = np.asarray(points, dtype=np.float64).reshape(-1, 2)
    weights = np.asarray(weights, dtype=np.float64)
    free = np.ones(len(points), dtype=bool)
    out = {}
    for zone, polygon in polygons.items():
        mask = free & inside(points, polygon)
        out[zone] = float(weights[mask].sum())
        free &= ~mask
    return out

"""benchmark.py의 지표·대조·시간 요약 테스트 (모델 없이 실행된다).

실행: cd ai/report-api && uv run python -m unittest test_benchmark -v
"""

import unittest

import benchmark


def pred(row_id, category, c_score, state, s_score, spans=()):
    return {"id": row_id, "category": {"label": category, "score": c_score},
            "reportedState": {"label": state, "score": s_score}, "spans": [list(s) for s in spans]}


ROWS = [
    {"id": "A", "category": "EQUIPMENT", "reportedState": "OCCURRED",
     "entities": [{"type": "ZONE", "start": 0, "end": 3}]},
    {"id": "B", "category": "SUPPLY", "reportedState": "RESOLVED_MENTION", "entities": []},
]


class StatsTest(unittest.TestCase):
    def test_nearest_rank_percentiles_in_milliseconds(self):
        result = benchmark.stats([i / 1000 for i in range(1, 101)])  # 1~100ms
        self.assertEqual((result["n"], result["p50Ms"], result["p95Ms"], result["maxMs"]), (100, 50.0, 95.0, 100.0))
        self.assertEqual(result["meanMs"], 50.5)

    def test_single_value(self):
        self.assertEqual(benchmark.stats([0.2])["p95Ms"], 200.0)


class ScoresTest(unittest.TestCase):
    def test_raw_scores_ignore_threshold_and_include_entities(self):
        preds = [pred("A", "EQUIPMENT", 0.5, "OCCURRED", 0.9, [("ZONE", 0, 3)]),
                 pred("B", "SUPPLY", 0.9, "RESOLVED_MENTION", 0.9)]
        result = benchmark.scores(ROWS, preds)
        self.assertEqual(result["category"]["accuracy"], 1.0)
        self.assertEqual(result["category"]["nulls"], 0)
        self.assertEqual(result["entitySpan"]["f1"], 1.0)

    def test_threshold_turns_low_scores_into_wrong_nulls(self):
        preds = [pred("A", "EQUIPMENT", 0.64, "OCCURRED", 0.65), pred("B", "SUPPLY", 0.9, "RESOLVED_MENTION", 0.9)]
        result = benchmark.scores(ROWS, preds, threshold=0.65)
        self.assertEqual((result["category"]["nulls"], result["category"]["accuracy"]), (1, 0.5))
        self.assertEqual(result["reportedState"]["nulls"], 0)  # 0.65는 미분류가 아니다(contract와 같음)
        self.assertNotIn("entitySpan", result)


class CompareTest(unittest.TestCase):
    def test_label_threshold_and_span_differences(self):
        onnx = [pred("A", "EQUIPMENT", 0.70, "OCCURRED", 0.90, [("ZONE", 0, 3)]),
                pred("B", "SUPPLY", 0.60, "RECURRED", 0.60)]
        torch = [pred("A", "EQUIPMENT", 0.75, "OCCURRED", 0.95, [("ZONE", 0, 2)]),
                 pred("B", "SUPPLY", 0.80, "RESOLVED_MENTION", 0.90)]
        result = benchmark.compare(onnx, torch)
        # B 상태는 라벨과 0.65 판정이 함께 달라 두 목록에 모두 들어간다
        self.assertEqual(result["differentIds"], {"category": [], "reportedState": ["B"], "spans": ["A"],
                                                  "threshold": ["B:category", "B:reportedState"]})
        self.assertEqual((result["categoryAgreement"], result["stateAgreement"], result["spanSetAgreement"]),
                         (1.0, 0.5, 0.5))
        self.assertEqual(result["maxTopScoreDiffWhenSameLabel"], 0.2)  # B 유형 0.60↔0.80: 판정이 갈려도 같은 라벨


if __name__ == "__main__":
    unittest.main()

"""보고 이해 모델 학습 파이프라인 회귀 테스트 (사전학습 모델 없이 실행된다).

학습 데이터 글자로 만든 작은 WordPiece 토크나이저와 작은 RoBERTa로 데이터 로더·손실·학습·저장을 확인한다.
실행: cd ai/report && uv run python -m unittest -v
"""

import json
import tempfile
import unittest
from pathlib import Path

import torch
from tokenizers import Tokenizer, models, pre_tokenizers, processors
from transformers import PreTrainedTokenizerFast, RobertaConfig, RobertaModel

import data
import metrics
import train
from model import ReportModel

ROW = {"id": "RPT-0001", "text": "B 체험존 프로젝터가 꺼졌습니다.", "category": "EQUIPMENT", "reportedState": "OCCURRED",
       "entities": [{"type": "ZONE", "start": 0, "end": 5, "text": "B 체험존"},
                    {"type": "ITEM", "start": 6, "end": 10, "text": "프로젝터"}]}
SPECIALS = ["[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]"]


def tiny_tokenizer(texts):
    """글자마다 토큰 하나(이어지는 글자는 ##)인 WordPiece. '프로젝터가'의 '가'도 따로 잘린다."""
    chars = sorted({c for t in texts for c in t if not c.isspace()})
    vocab = {tok: i for i, tok in enumerate(SPECIALS + chars + [f"##{c}" for c in chars])}
    tok = Tokenizer(models.WordPiece(vocab, unk_token="[UNK]"))
    tok.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
    tok.post_processor = processors.TemplateProcessing(single="[CLS] $A [SEP]", special_tokens=[
        ("[CLS]", vocab["[CLS]"]), ("[SEP]", vocab["[SEP]"])])
    return PreTrainedTokenizerFast(tokenizer_object=tok, pad_token="[PAD]", unk_token="[UNK]", cls_token="[CLS]",
                                   sep_token="[SEP]", mask_token="[MASK]")


def tiny_model(tokenizer):
    config = RobertaConfig(vocab_size=len(tokenizer), hidden_size=32, num_hidden_layers=1, num_attention_heads=2,
                           intermediate_size=64, max_position_embeddings=130, pad_token_id=tokenizer.pad_token_id)
    return ReportModel(RobertaModel(config, add_pooling_layer=False))


class EncodeTest(unittest.TestCase):
    def setUp(self):
        self.tokenizer = tiny_tokenizer([ROW["text"]])

    def test_bio_tags_follow_entity_ranges(self):
        f = data.encode(ROW, self.tokenizer)
        tokens = self.tokenizer.convert_ids_to_tokens(f["input_ids"])
        tags = [data.ENTITY_TAGS[t] if t != data.IGNORE else None for t in f["entity_tags"]]
        self.assertEqual(list(zip(tokens, tags))[:7], [
            ("[CLS]", None), ("B", "B-ZONE"), ("체", "I-ZONE"), ("##험", "I-ZONE"), ("##존", "I-ZONE"),
            ("프", "B-ITEM"), ("##로", "I-ITEM")])
        self.assertEqual(tags[-1], None)  # [SEP]
        self.assertEqual(tags[9], "O")  # 가
        self.assertEqual(data.misaligned_entities(ROW, f["offsets"]), 0)

    def test_tags_round_trip_to_gold_spans(self):
        f = data.encode(ROW, self.tokenizer)
        self.assertEqual(metrics.tags_to_spans(f["entity_tags"], f["offsets"]), metrics.gold_spans(ROW))

    def test_misaligned_entity_when_token_crosses_boundary(self):
        whole = PreTrainedTokenizerFast(tokenizer_object=self._word_tokenizer(), pad_token="[PAD]", cls_token="[CLS]",
                                        sep_token="[SEP]", unk_token="[UNK]")
        f = data.encode(ROW, whole)
        self.assertEqual(data.misaligned_entities(ROW, f["offsets"]), 1)  # '프로젝터가'가 한 토큰

    def _word_tokenizer(self):
        vocab = {t: i for i, t in enumerate(SPECIALS + ["B", "체험존", "프로젝터가", "꺼졌습니다", "."])}
        tok = Tokenizer(models.WordPiece(vocab, unk_token="[UNK]"))
        tok.pre_tokenizer = pre_tokenizers.BertPreTokenizer()
        return tok

    def test_collate_pads_inputs_and_ignores_padding_labels(self):
        short = {**ROW, "id": "RPT-0002", "text": "B 체험존", "entities": ROW["entities"][:1]}
        batch = data.collate([data.encode(ROW, self.tokenizer), data.encode(short, self.tokenizer)],
                             self.tokenizer.pad_token_id)
        n = len(data.encode(short, self.tokenizer)["input_ids"])
        self.assertEqual(batch["attention_mask"][1].tolist(), [1] * n + [0] * (batch["input_ids"].shape[1] - n))
        self.assertTrue((batch["entity_tags"][1, n:] == data.IGNORE).all())
        self.assertTrue((batch["input_ids"][1, n:] == self.tokenizer.pad_token_id).all())


class LoadSplitsTest(unittest.TestCase):
    def test_dataset_splits_and_limit(self):
        splits = data.load_splits(limits={"train": 10, "valid": 3, "test": 3})
        self.assertEqual({k: len(v) for k, v in splits.items()}, {"train": 10, "valid": 3, "test": 3})
        again = data.load_splits(limits={"train": 10, "valid": 3, "test": 3})
        self.assertEqual([r["id"] for r in splits["train"]], [r["id"] for r in again["train"]])

    def load_modified(self, change_split=None, change_rows=None):
        """기본 데이터·분할을 고쳐 임시 폴더에 쓰고 load_splits로 읽는다."""
        lines = data.DEFAULT_DATASET.read_text(encoding="utf-8").splitlines()
        split_data = json.loads(data.DEFAULT_DATASET.with_suffix(".split.json").read_text(encoding="utf-8"))
        rows = [json.loads(line) for line in lines]
        (change_split or (lambda s: None))(split_data)
        (change_rows or (lambda r: None))(rows)
        with tempfile.TemporaryDirectory() as tmp:
            dataset = Path(tmp) / "d.jsonl"
            dataset.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
            dataset.with_suffix(".split.json").write_text(json.dumps(split_data), encoding="utf-8")
            return data.load_splits(dataset)

    def test_rejects_id_in_two_splits(self):  # 평가 id를 학습에도 넣으면 누수
        def leak(s):
            s["train"].append(s["test"][0])
        with self.assertRaisesRegex(ValueError, r"분할 오류 .*두 번 이상 배정됨"):
            self.load_modified(leak)

    def test_rejects_missing_id(self):
        with self.assertRaisesRegex(ValueError, r"어느 분할에도 없음"):
            self.load_modified(lambda s: s["valid"].pop())

    def test_rejects_situation_unit_across_splits(self):
        def move_one(s):  # 학습 분할의 샘플 계열(projector_01~) 한 건을 평가로 옮김
            s["train"].remove("RPT-0001")
            s["test"].append("RPT-0001")
        with self.assertRaisesRegex(ValueError, r"\[누수\] 상황 단위 .*train.*projector_01"):
            self.load_modified(move_one)

    def test_rejects_unapproved_rows(self):
        def pending(rows):
            rows[5]["reviewStatus"] = "PENDING"
        with self.assertRaisesRegex(ValueError, r"APPROVED.*1건: RPT-0006"):
            self.load_modified(change_rows=pending)

    def test_label_lists(self):
        self.assertEqual(len(data.CATEGORIES), 6)
        self.assertEqual(len(data.STATES), 4)
        self.assertEqual(len(data.ENTITY_TAGS), 9)


class MetricsTest(unittest.TestCase):
    def test_macro_f1_skips_labels_absent_from_both(self):
        self.assertAlmostEqual(metrics.macro_f1([0, 0, 1], [0, 1, 1], range(4)), (2 / 3 + 2 / 3) / 2)

    def test_span_f1_is_exact_match(self):
        gold = [{("ZONE", 0, 5), ("ITEM", 6, 10)}]
        pred = [{("ZONE", 0, 5), ("ITEM", 6, 11)}]
        self.assertEqual(metrics.span_f1(gold, pred), {"precision": 0.5, "recall": 0.5, "f1": 0.5})

    def test_i_tag_with_different_type_starts_new_span(self):
        tags = [data.ENTITY_TAGS.index(t) for t in ("B-ZONE", "I-ITEM", "O")]
        self.assertEqual(metrics.tags_to_spans(tags, [(0, 1), (1, 2), (2, 3)]), {("ZONE", 0, 1), ("ITEM", 1, 2)})


class ModelTest(unittest.TestCase):
    def test_loss_is_sum_of_three_cross_entropies(self):
        tokenizer = tiny_tokenizer([ROW["text"]])
        torch.manual_seed(0)
        model = tiny_model(tokenizer).eval()
        batch = data.collate([data.encode(ROW, tokenizer)], tokenizer.pad_token_id)
        out = model(**batch)
        ce = torch.nn.functional.cross_entropy
        expected = (ce(out["category"], batch["category"]) + ce(out["state"], batch["state"])
                    + ce(out["entity"][0], batch["entity_tags"][0], ignore_index=data.IGNORE))
        self.assertAlmostEqual(out["loss"].item(), expected.item(), places=5)


class TrainTest(unittest.TestCase):
    def test_small_training_saves_and_reloads(self):
        splits = data.load_splits(limits={"train": 32, "valid": 8, "test": 8})
        tokenizer = tiny_tokenizer([r["text"] for rows in splits.values() for r in rows])
        torch.manual_seed(0)
        model = tiny_model(tokenizer)
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "report-test"
            record = train.train(model, tokenizer, splits, out, epochs=2, batch_size=8, lr=1e-3, weight_decay=0.01,
                                 warmup=0.1, seed=42, max_length=64, log=lambda *_: None)
            self.assertEqual(len(record["history"]), 2)
            self.assertTrue(record["reloadMatchesBest"])
            self.assertLess(record["history"][-1]["trainLoss"]["loss"], record["history"][0]["trainLoss"]["loss"])
            for name in ("config.json", "model.safetensors", "labels.json", "tokenizer.json"):
                self.assertTrue((out / name).exists(), name)
            labels = json.loads((out / "labels.json").read_text(encoding="utf-8"))
            self.assertEqual(labels["entityTags"], data.ENTITY_TAGS)

            reloaded = ReportModel.load(out)
            batch = data.collate([data.encode(ROW, tokenizer)], tokenizer.pad_token_id)
            model.eval()
            torch.testing.assert_close(reloaded(**batch)["loss"], model.cpu()(**batch)["loss"])


if __name__ == "__main__":
    unittest.main()

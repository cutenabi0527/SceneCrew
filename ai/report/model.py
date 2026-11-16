"""KLUE RoBERTa 인코더 하나에 유형·상태 문장 분류 헤드 2개와 개체 토큰 분류 헤드 1개를 붙인 모델.

손실은 유형 CE + 상태 CE + 개체 CE이며 가중치는 각 1이다(기획 AI 06). 개체 CE는 special·padding 토큰(-100)을 뺀다.
저장 묶음: config.json(인코더) · model.safetensors · 토크나이저 · labels.json
"""

import json
from pathlib import Path

import torch
from safetensors.torch import load_file, save_file
from torch import nn
from transformers import AutoConfig, AutoModel

from data import CATEGORIES, ENTITY_TAGS, IGNORE, STATES

LOSS_WEIGHTS = {"category": 1.0, "state": 1.0, "entity": 1.0}
WEIGHTS_FILE = "model.safetensors"
LABELS_FILE = "labels.json"


class ReportModel(nn.Module):
    def __init__(self, encoder):
        super().__init__()
        self.encoder = encoder
        hidden = encoder.config.hidden_size
        self.dropout = nn.Dropout(encoder.config.hidden_dropout_prob)
        self.category_head = nn.Linear(hidden, len(CATEGORIES))
        self.state_head = nn.Linear(hidden, len(STATES))
        self.entity_head = nn.Linear(hidden, len(ENTITY_TAGS))

    @classmethod
    def from_pretrained_encoder(cls, name):
        """사전학습 인코더(예: klue/roberta-base)에 새 헤드를 붙인다."""
        return cls(AutoModel.from_pretrained(name, add_pooling_layer=False))

    def forward(self, input_ids, attention_mask, category=None, state=None, entity_tags=None):
        hidden = self.dropout(self.encoder(input_ids=input_ids, attention_mask=attention_mask).last_hidden_state)
        out = {
            "category": self.category_head(hidden[:, 0]),
            "state": self.state_head(hidden[:, 0]),
            "entity": self.entity_head(hidden),
        }
        if category is not None:
            losses = {
                "category": nn.functional.cross_entropy(out["category"], category),
                "state": nn.functional.cross_entropy(out["state"], state),
                "entity": nn.functional.cross_entropy(out["entity"].flatten(0, 1), entity_tags.flatten(),
                                                      ignore_index=IGNORE),
            }
            out["losses"] = losses
            out["loss"] = sum(LOSS_WEIGHTS[k] * v for k, v in losses.items())
        return out

    def save(self, directory, tokenizer):
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True)
        self.encoder.config.save_pretrained(directory)
        save_file({k: v.contiguous() for k, v in self.state_dict().items()}, directory / WEIGHTS_FILE)
        tokenizer.save_pretrained(directory)
        labels = {"category": CATEGORIES, "reportedState": STATES, "entityTags": ENTITY_TAGS}
        (directory / LABELS_FILE).write_text(json.dumps(labels, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")

    @classmethod
    def load(cls, directory, device="cpu"):
        """save()로 저장한 묶음을 읽는다. 라벨 목록이 지금 코드와 다르면 멈춘다."""
        directory = Path(directory)
        labels = json.loads((directory / LABELS_FILE).read_text(encoding="utf-8"))
        if labels != {"category": CATEGORIES, "reportedState": STATES, "entityTags": ENTITY_TAGS}:
            raise ValueError(f"{directory / LABELS_FILE}의 라벨 목록이 지금 코드와 다릅니다.")
        encoder = AutoModel.from_config(AutoConfig.from_pretrained(directory), add_pooling_layer=False)
        model = cls(encoder)
        model.load_state_dict(load_file(directory / WEIGHTS_FILE, device=str(device)))
        return model.to(device).eval()


@torch.no_grad()
def predict(model, batch):
    """배치의 유형·상태 id와 확률, 토큰별 개체 태그 id."""
    out = model(batch["input_ids"], batch["attention_mask"])
    category = out["category"].softmax(-1)
    state = out["state"].softmax(-1)
    return {
        "category": category.argmax(-1).tolist(),
        "categoryScore": category.max(-1).values.tolist(),
        "state": state.argmax(-1).tolist(),
        "stateScore": state.max(-1).values.tolist(),
        "entity": out["entity"].argmax(-1).tolist(),
    }

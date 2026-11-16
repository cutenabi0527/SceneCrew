"""밀집도 후보 모델의 PyTorch 정의와 가중치 불러오기 (비교·ONNX 변환용, 서빙에는 쓰지 않는다).

원래 저장소의 코드를 실행하지 않고, 같은 state_dict 키를 갖도록 구조만 다시 적었다.
- DM-Count(VGG19, 밀도 맵): cvlab-stonybrook/DM-Count 구조, 가중치는 LWCC(MIT) 배포본 DM-Count_{SHA,SHB,QNRF}.pth
- CSRNet(VGG16 dilated, 밀도 맵): LWCC 배포본 CSRNet_SHA.pth
- P2PNet(VGG16-bn, 점): TencentYoutuResearch/CrowdCounting-P2PNet weights/SHTechA.pth. 라이선스가 학술 연구 목적만 허용한다
- 사람 검출: torchvision Faster R-CNN ResNet50-FPN v2 COCO(BSD-3), person 클래스만

모든 후보는 정규화 좌표(0~1)의 점과 점마다의 무게를 돌려준다. 밀도 맵은 칸 중심점과 칸 값, 점·검출은 무게 1이다.
"""

import numpy as np
import torch
from torch import nn
from torch.nn import functional as F

MEAN = (0.485, 0.456, 0.406)
STD = (0.229, 0.224, 0.225)


def _vgg_layers(cfg, in_channels=3, dilation=1):
    layers = []
    for v in cfg:
        if v == "M":
            layers.append(nn.MaxPool2d(2, 2))
        else:
            layers += [nn.Conv2d(in_channels, v, 3, padding=dilation, dilation=dilation), nn.ReLU(inplace=True)]
            in_channels = v
    return nn.Sequential(*layers)


class DMCount(nn.Module):
    """출력: 1/8 크기 밀도 맵 (B,1,H/8,W/8)."""

    def __init__(self):
        super().__init__()
        self.features = _vgg_layers([64, 64, "M", 128, 128, "M", 256, 256, 256, 256, "M",
                                     512, 512, 512, 512, "M", 512, 512, 512, 512])
        self.reg_layer = nn.Sequential(nn.Conv2d(512, 256, 3, padding=1), nn.ReLU(inplace=True),
                                       nn.Conv2d(256, 128, 3, padding=1), nn.ReLU(inplace=True))
        self.density_layer = nn.Sequential(nn.Conv2d(128, 1, 1), nn.ReLU())

    def forward(self, x):
        x = self.features(x)
        x = F.interpolate(x, scale_factor=2, mode="bilinear", align_corners=True)
        return self.density_layer(self.reg_layer(x))


class CSRNet(nn.Module):
    """출력: 1/8 크기 밀도 맵."""

    def __init__(self):
        super().__init__()
        self.frontend = _vgg_layers([64, 64, "M", 128, 128, "M", 256, 256, 256, "M", 512, 512, 512])
        self.backend = _vgg_layers([512, 512, 512, 256, 128, 64], in_channels=512, dilation=2)
        self.output_layer = nn.Conv2d(64, 1, 1)

    def forward(self, x):
        return self.output_layer(self.backend(self.frontend(x)))


class _Head(nn.Module):
    def __init__(self, out_channels, feature_size=256):
        super().__init__()
        # 원 구현은 conv3·conv4를 만들고 쓰지 않는다. 키를 맞추려고 그대로 둔다.
        self.conv1 = nn.Conv2d(256, feature_size, 3, padding=1)
        self.conv2 = nn.Conv2d(feature_size, feature_size, 3, padding=1)
        self.conv3 = nn.Conv2d(feature_size, feature_size, 3, padding=1)
        self.conv4 = nn.Conv2d(feature_size, feature_size, 3, padding=1)
        self.output = nn.Conv2d(feature_size, out_channels, 3, padding=1)

    def forward(self, x):
        return self.output(F.relu(self.conv2(F.relu(self.conv1(x)))))


class _Decoder(nn.Module):
    def __init__(self, c3=256, c4=512, c5=512, size=256):
        super().__init__()
        self.P5_1 = nn.Conv2d(c5, size, 1)
        self.P5_2 = nn.Conv2d(size, size, 3, padding=1)
        self.P4_1 = nn.Conv2d(c4, size, 1)
        self.P4_2 = nn.Conv2d(size, size, 3, padding=1)
        self.P3_1 = nn.Conv2d(c3, size, 1)
        self.P3_2 = nn.Conv2d(size, size, 3, padding=1)

    def forward(self, c3, c4, c5):
        p5 = self.P5_1(c5)
        p4 = self.P4_1(c4) + F.interpolate(p5, scale_factor=2, mode="nearest")
        return self.P4_2(p4)  # P2PNet은 P4(1/8)만 쓴다


class _Backbone(nn.Module):
    def __init__(self):
        super().__init__()
        from torchvision.models import vgg16_bn
        features = list(vgg16_bn(weights=None).features.children())
        self.body1 = nn.Sequential(*features[:13])
        self.body2 = nn.Sequential(*features[13:23])
        self.body3 = nn.Sequential(*features[23:33])
        self.body4 = nn.Sequential(*features[33:43])


class P2PNet(nn.Module):
    """출력: (점 좌표 B,N,2 입력 픽셀 기준, 사람일 확률 B,N). 입력 크기는 128의 배수."""

    def __init__(self, row=2, line=2):
        super().__init__()
        self.backbone = _Backbone()
        self.regression = _Head(row * line * 2)
        self.classification = _Head(row * line * 2)
        self.fpn = _Decoder()
        self.row, self.line = row, line

    def anchors(self, h, w, stride=8):
        rs, ls = stride / self.row, stride / self.line
        ax = (np.arange(1, self.line + 1) - 0.5) * ls - stride / 2
        ay = (np.arange(1, self.row + 1) - 0.5) * rs - stride / 2
        ax, ay = np.meshgrid(ax, ay)
        base = np.stack([ax.ravel(), ay.ravel()], 1)
        gh, gw = (h + stride - 1) // stride, (w + stride - 1) // stride
        sx, sy = np.meshgrid((np.arange(gw) + 0.5) * stride, (np.arange(gh) + 0.5) * stride)
        shifts = np.stack([sx.ravel(), sy.ravel()], 1)
        return torch.from_numpy((shifts[:, None, :] + base[None]).reshape(-1, 2).astype(np.float32))

    def forward(self, x):
        b = self.backbone
        c1 = b.body1(x)
        c2 = b.body2(c1)
        c3 = b.body3(c2)
        c4 = b.body4(c3)
        p4 = self.fpn(c2, c3, c4)
        n = x.shape[0]
        reg = self.regression(p4).permute(0, 2, 3, 1).reshape(n, -1, 2) * 100
        cls = self.classification(p4).permute(0, 2, 3, 1).reshape(n, -1, 2)
        points = reg + self.anchors(x.shape[2], x.shape[3]).unsqueeze(0)
        return points, cls.softmax(-1)[..., 1]


def load(name, weights_dir):
    """name: DM-Count_SHA 같은 파일 이름(확장자 제외). torch.load는 weights_only로 텐서만 읽는다."""
    if name == "FasterRCNN_COCO":
        from torchvision.models.detection import (FasterRCNN_ResNet50_FPN_V2_Weights,
                                                  fasterrcnn_resnet50_fpn_v2)
        return fasterrcnn_resnet50_fpn_v2(weights=FasterRCNN_ResNet50_FPN_V2_Weights.COCO_V1,
                                          box_score_thresh=0.5).eval()
    arch = {"DM-Count": DMCount, "CSRNet": CSRNet, "P2PNet": P2PNet}[name.split("_")[0]]
    model = arch()
    state = torch.load(f"{weights_dir}/{name}.pth", map_location="cpu", weights_only=True)
    model.load_state_dict(state.get("model", state))
    return model.eval()


def to_tensor(image, size):
    """PIL RGB 이미지를 (W,H) 크기로 바꿔 ImageNet 정규화한 (1,3,H,W) 텐서."""
    arr = np.asarray(image.resize(size), dtype=np.float32) / 255.0
    arr = (arr - MEAN) / STD
    return torch.from_numpy(arr.transpose(2, 0, 1).astype(np.float32)).unsqueeze(0)


def density_points(density):
    """밀도 맵 (h,w)을 칸 중심의 정규화 점과 무게로 바꾼다."""
    h, w = density.shape
    ys, xs = np.mgrid[0:h, 0:w]
    pts = np.stack([(xs.ravel() + 0.5) / w, (ys.ravel() + 0.5) / h], 1)
    return pts, density.ravel().astype(np.float64)


@torch.inference_mode()
def predict(name, model, image, long_side):
    """정규화 점 (N,2)와 무게 (N,)."""
    w0, h0 = image.size
    scale = long_side / max(w0, h0)
    if name.startswith("FasterRCNN"):
        w, h = round(w0 * scale), round(h0 * scale)
        x = torch.from_numpy(np.asarray(image.resize((w, h)), dtype=np.float32).transpose(2, 0, 1) / 255.0)
        out = model([x])[0]
        boxes = out["boxes"][out["labels"] == 1].numpy()
        # 대표 점: 상자 위쪽 1/8 지점(머리 근처). 라벨의 머리 점과 맞춘다.
        pts = np.stack([(boxes[:, 0] + boxes[:, 2]) / 2 / w, (boxes[:, 1] + (boxes[:, 3] - boxes[:, 1]) / 8) / h], 1)
        return pts.reshape(-1, 2), np.ones(len(boxes))
    if name.startswith("P2PNet"):
        w, h = max(128, round(w0 * scale / 128) * 128), max(128, round(h0 * scale / 128) * 128)
        points, scores = model(to_tensor(image, (w, h)))
        keep = scores[0] > 0.5
        pts = points[0][keep].numpy() / np.array([w, h], dtype=np.float32)
        return pts, np.ones(len(pts))
    w, h = max(8, round(w0 * scale / 8) * 8), max(8, round(h0 * scale / 8) * 8)
    density = model(to_tensor(image, (w, h)))[0, 0].numpy()
    return density_points(density)

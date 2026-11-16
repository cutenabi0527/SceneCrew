"""현장 보고 음성을 전사문과 구간별 결과로 변환한다.

파일 1개는 JSON 객체, 여러 개는 JSON 배열로 출력한다.

사용법:
    uv run transcribe.py <음성 파일> [-o result.json] [--save-wav out.wav]
    uv run transcribe.py a.wav b.m4a -o results.json
    uv run transcribe.py <음성 파일> --model small --device cpu
"""

import argparse
import json
import os
import re
import sys
import time
import unicodedata
from dataclasses import asdict, dataclass
from pathlib import Path

from audio import TARGET_SAMPLE_RATE, load_audio, save_wav

# 76건 비교에서 large-v3-turbo보다 소음 환경 개체 인식률이 높았다 (README 참고).
DEFAULT_MODEL = "large-v3"
# 평가·명령줄 기본 힌트. 내부 API는 쓰지 않고 요청의 행사별 hints로 prompt를 만든다(STT 명세 §5.1).
DEFAULT_PROMPT = "현장 보고입니다. A구역, B구역, C구역, B 체험존, 입구, 안내 데스크, 메인 무대, 물품보관소, 개막식, 오후 세션, 체험 프로그램."
# 행사별 힌트를 고르는 순서(§5.1, AI 제안 순서). 키 이름은 요청4 payload.hints와 같다.
HINT_ORDER = ("zones", "equipment", "programs", "groups")
HINT_SEPARATOR = ", "
# faster-whisper는 initial_prompt를 마지막 223토큰(max_length 448 // 2 - 1)만 쓰고 앞을 조용히 버린다.
PROMPT_TOKEN_BUDGET = 223


@dataclass
class Segment:
    id: int
    start: float
    end: float
    text: str
    avg_logprob: float
    no_speech_prob: float
    prompt_echo: bool


def normalize(text):
    """공백·문장부호를 제거한다."""
    text = unicodedata.normalize("NFC", text)
    return re.sub(r"[\s\W_]+", "", text)


def select_hints(hints, count_tokens, budget=PROMPT_TOKEN_BUDGET):
    """행사별 힌트를 HINT_ORDER와 전달 순서대로 prompt 토큰 예산 안에서 고른다.

    예산을 넘는 이름을 만나면 거기서 멈춘다. 뒤의 짧은 이름으로 건너뛰지 않아 우선순위가 유지되고,
    같은 hints는 항상 같은 prompt가 된다(재시도 재현성). 종류가 달라도 같은 이름은 한 번만 넣는다.
    """
    selected = []
    for kind in HINT_ORDER:
        for name in hints.get(kind, ()):
            if name in selected:
                continue
            if count_tokens(HINT_SEPARATOR.join([*selected, name])) > budget:
                return selected
            selected.append(name)
    return selected


def prompt_items(prompt):
    """명령줄·평가용 문자열 prompt를 쉼표·마침표로 나눈 항목."""
    return [p.strip() for p in re.split(r"[,.]", prompt or "") if p.strip()]


def is_prompt_echo(text, items):
    """소음 구간에서 모델이 실제 발화 대신 힌트 목록을 그대로 출력한 경우를 판별한다.

    이런 구간은 신뢰도(avg_logprob, no_speech_prob)가 정상 발화와 비슷하게 나와 신뢰도로는 구분되지 않는다.
    힌트 항목 2개 이상을 전달 순서대로 이어 붙인 것과 같은 구간만 반복으로 본다. 힌트 하나와 같은 구간은
    사용자가 실제로 말한 명칭일 수 있어 남기고, 힌트가 1개면 반복 판정을 하지 않는다(§5.1).
    """
    seg = normalize(text)
    names = [n for n in map(normalize, items) if n]
    if not seg or len(names) < 2:
        return False
    for i in range(len(names) - 1):
        joined = names[i]
        for name in names[i + 1:]:
            joined += name
            if joined == seg:
                return True
            if len(joined) >= len(seg):
                break
    return False


def _register_cuda_dlls():
    """Windows에서 pip로 설치한 nvidia-cublas/cudnn DLL 경로를 등록한다.

    Linux는 프로세스 시작 전에 LD_LIBRARY_PATH가 설정돼 있어야 하므로 run_cuda.sh로 실행한다.
    """
    if os.name != "nt":
        return
    try:
        import nvidia
    except ImportError:
        return
    for base in nvidia.__path__:
        for lib in ("cublas", "cudnn"):
            bin_dir = Path(base) / lib / "bin"
            if bin_dir.is_dir():
                os.add_dll_directory(str(bin_dir))
                os.environ["PATH"] = str(bin_dir) + os.pathsep + os.environ["PATH"]


def _default_compute_type(device):
    return "float16" if device == "cuda" else "int8"


class Transcriber:
    def __init__(self, model=DEFAULT_MODEL, device="auto", compute_type=None, cpu_threads=0):
        _register_cuda_dlls()
        from faster_whisper import WhisperModel
        import ctranslate2

        if device == "auto":
            device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
        self.device = device
        self.compute_type = compute_type or _default_compute_type(device)
        self.model_name = model
        self.model = WhisperModel(model, device=device, compute_type=self.compute_type, cpu_threads=cpu_threads)

    def count_tokens(self, prompt):
        """faster-whisper가 initial_prompt를 토큰으로 바꾸는 방식(앞에 공백 하나)과 같게 센다."""
        return len(self.model.hf_tokenizer.encode(" " + prompt.strip(), add_special_tokens=False).ids)

    def transcribe_hints(self, audio, hints):
        """요청4의 행사별 hints로 prompt를 만들어 전사한다. 힌트가 없으면 prompt 없이 전사한다."""
        items = select_hints(hints, self.count_tokens)
        return self.transcribe(audio, initial_prompt=HINT_SEPARATOR.join(items), items=items)

    def transcribe(self, audio, language="ko", initial_prompt=None, vad=True, items=None):
        """16kHz 모노 float32 배열을 전사한다.

        items는 힌트 반복 판정에 쓸 힌트 항목이다. 주지 않으면 initial_prompt를 쉼표·마침표로 나눠 쓴다.
        힌트 반복으로 판단된 구간은 segments에 prompt_echo=true로 남기고 text에서만 뺀다.
        """
        items = prompt_items(initial_prompt) if items is None else items
        segments, info = self.model.transcribe(
            audio,
            language=language,
            beam_size=5,
            vad_filter=vad,
            initial_prompt=initial_prompt or None,
            condition_on_previous_text=False,
        )
        result = [
            Segment(
                id=s.id,
                start=round(s.start, 2),
                end=round(s.end, 2),
                text=s.text.strip(),
                avg_logprob=round(s.avg_logprob, 4),
                no_speech_prob=round(s.no_speech_prob, 4),
                prompt_echo=is_prompt_echo(s.text, items),
            )
            for s in segments
        ]
        return {
            "text": " ".join(s.text for s in result if s.text and not s.prompt_echo),
            "language": info.language,
            "duration": round(info.duration, 2),
            "segments": [asdict(s) for s in result],
        }


def transcribe_file(path, transcriber, save_wav_path=None, **kwargs):
    audio = load_audio(path, TARGET_SAMPLE_RATE)
    if save_wav_path:
        save_wav(audio, save_wav_path, TARGET_SAMPLE_RATE)
    started = time.perf_counter()
    result = transcriber.transcribe(audio, **kwargs)
    result["source"] = str(path)
    result["model"] = transcriber.model_name
    result["device"] = transcriber.device
    result["elapsedSec"] = round(time.perf_counter() - started, 2)
    return result


def main():
    parser = argparse.ArgumentParser(description="현장 보고 음성 전사")
    parser.add_argument("audio", nargs="+", help="음성 파일 경로")
    parser.add_argument("-o", "--output", help="결과 JSON 저장 경로")
    parser.add_argument("--save-wav", help="16kHz 모노 변환본 저장 경로 (파일 1개일 때만)")
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--device", default="auto", choices=["auto", "cuda", "cpu"])
    parser.add_argument("--compute-type")
    parser.add_argument("--language", default="ko")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="고유 명칭 힌트 (빈 문자열이면 사용 안 함)")
    parser.add_argument("--no-vad", action="store_true", help="무음 구간 제거(VAD) 끄기")
    args = parser.parse_args()

    if len(args.audio) > 1 and args.save_wav:
        parser.error("--save-wav는 파일 1개일 때만 사용할 수 있습니다.")

    transcriber = Transcriber(args.model, args.device, args.compute_type)
    results = [
        transcribe_file(
            path,
            transcriber,
            save_wav_path=args.save_wav,
            language=args.language,
            initial_prompt=args.prompt,
            vad=not args.no_vad,
        )
        for path in args.audio
    ]
    text = json.dumps(results[0] if len(results) == 1 else results, ensure_ascii=False, indent=2)
    if args.output:
        Path(args.output).parent.mkdir(parents=True, exist_ok=True)
        Path(args.output).write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main()

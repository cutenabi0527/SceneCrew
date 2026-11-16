"""입력 음성을 STT 입력 형식(16kHz 모노)으로 변환한다.

PyAV(FFmpeg 바인딩)로 디코딩하므로 시스템 ffmpeg 설치가 필요 없다.
m4a, mp3, webm, ogg, wav 등 FFmpeg가 읽을 수 있는 형식을 모두 받는다.
"""

import wave
from pathlib import Path

import av
import numpy as np

TARGET_SAMPLE_RATE = 16000


class AudioTooLongError(ValueError):
    """음성 길이가 max_seconds를 넘는다."""

    def __init__(self, max_seconds):
        super().__init__(f"음성 길이가 {max_seconds}초를 넘습니다.")
        self.max_seconds = max_seconds


def load_audio(path, sample_rate=TARGET_SAMPLE_RATE, max_seconds=None):
    """음성 파일을 sample_rate 모노 float32 배열(-1.0~1.0)로 읽는다.

    max_seconds를 주면 그 길이를 넘는 즉시 디코딩을 멈추고 AudioTooLongError를 낸다.
    용량은 작아도 길이가 긴 파일을 끝까지 디코딩하지 않기 위해서다.
    """
    resampler = av.AudioResampler(format="s16", layout="mono", rate=sample_rate)
    max_samples = None if max_seconds is None else int(max_seconds * sample_rate)
    chunks = []
    total = 0
    with av.open(str(path)) as container:
        if not container.streams.audio:
            raise ValueError(f"오디오 스트림이 없습니다: {path}")
        stream = container.streams.audio[0]
        for frame in container.decode(stream):
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray().reshape(-1))
                total += len(chunks[-1])
            if max_samples is not None and total > max_samples:
                raise AudioTooLongError(max_seconds)
        for out in resampler.resample(None):
            chunks.append(out.to_ndarray().reshape(-1))
            total += len(chunks[-1])
    if max_samples is not None and total > max_samples:
        raise AudioTooLongError(max_seconds)
    if not chunks:
        raise ValueError(f"디코딩된 오디오가 없습니다: {path}")
    pcm = np.concatenate(chunks)
    return pcm.astype(np.float32) / 32768.0


def save_wav(audio, path, sample_rate=TARGET_SAMPLE_RATE):
    """float32 모노 배열을 16-bit PCM WAV로 저장한다."""
    pcm = (np.clip(audio, -1.0, 1.0) * 32767).astype("<i2")
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sample_rate)
        f.writeframes(pcm.tobytes())


def convert_to_wav(src, dst, sample_rate=TARGET_SAMPLE_RATE):
    """src 음성을 sample_rate 모노 WAV로 변환해 dst에 저장하고 길이(초)를 반환한다."""
    audio = load_audio(src, sample_rate)
    save_wav(audio, dst, sample_rate)
    return len(audio) / sample_rate

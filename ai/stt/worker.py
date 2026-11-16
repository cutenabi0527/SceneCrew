"""음성 디코딩과 전사를 별도 프로세스에서 실행한다.

스레드는 밖에서 멈출 수 없으므로, 시간 제한을 넘긴 작업은 프로세스를 종료해 끊는다.
디코딩도 같은 프로세스에서 하므로 요청의 시간 제한이 디코딩까지 포함된다.
종료한 뒤에는 start()로 모델을 다시 불러온 새 프로세스를 띄운다.
"""

import multiprocessing as mp
import os
import threading
from functools import partial

import av

from audio import TARGET_SAMPLE_RATE, AudioTooLongError, load_audio


class WorkerTimeout(Exception):
    """작업이 시간 제한 안에 끝나지 않아 프로세스를 종료했다."""


class WorkerFailed(Exception):
    """프로세스가 준비되지 않았거나 비정상 종료됐다."""


class AudioRejected(Exception):
    """받을 수 없는 음성이다. args[0]은 사유(TOO_LONG, DECODE_FAILED)다."""

    @property
    def reason(self):
        return self.args[0]


class SttRunner:
    """작업 프로세스 안에서 음성 파일을 디코딩하고 전사한다."""

    def __init__(self, transcriber):
        self.transcriber = transcriber

    def decode(self, path, max_seconds):
        try:
            return load_audio(path, TARGET_SAMPLE_RATE, max_seconds=max_seconds)
        except AudioTooLongError:
            raise AudioRejected("TOO_LONG")
        except (av.error.FFmpegError, ValueError):
            raise AudioRejected("DECODE_FAILED")

    def run(self, path, max_seconds, hints):
        return self.transcriber.transcribe_hints(self.decode(path, max_seconds), hints)


def _make_runner(transcriber_factory):
    return SttRunner(transcriber_factory())


def runner_factory(transcriber_factory):
    """transcriber_factory()로 전사기를 만드는 SttRunner 생성 함수. pickle 가능하다."""
    return partial(_make_runner, transcriber_factory)


def _exit_with_parent():
    """부모(서버)가 강제 종료되면 자식도 끝낸다.

    부모가 정상 종료하면 stop()이나 daemon 정리로 자식이 끝나지만, kill -9 등으로 강제 종료되면
    추론 중이던 자식이 남는다. 부모 프로세스가 끝나기를 기다렸다가 바로 종료한다.
    """
    mp.parent_process().join()
    os._exit(1)


def _serve(conn, factory):
    threading.Thread(target=_exit_with_parent, daemon=True).start()
    try:
        runner = factory()
    except Exception as e:
        conn.send(("failed", repr(e)))
        return
    conn.send(("ready", None))
    while True:
        try:
            args = conn.recv()
        except EOFError:
            return
        try:
            conn.send(("ok", runner.run(*args)))
        except Exception as e:
            try:
                conn.send(("raised", e))
            except Exception:
                conn.send(("raised", WorkerFailed(repr(e))))


class TranscriberWorker:
    """factory()가 만든 runner를 자식 프로세스 하나에서 실행한다. factory는 pickle 가능해야 한다."""

    def __init__(self, factory):
        self.factory = factory
        self.proc = None
        self.conn = None

    @property
    def ready(self):
        return self.proc is not None

    def start(self):
        """모델을 불러온 프로세스를 띄우고 준비될 때까지 기다린다."""
        ctx = mp.get_context("spawn")
        parent, child = ctx.Pipe()
        proc = ctx.Process(target=_serve, args=(child, self.factory), daemon=True)
        proc.start()
        child.close()
        try:
            status, value = parent.recv()
        except EOFError:
            status, value = "failed", "프로세스가 종료됐습니다."
        if status != "ready":
            proc.kill()
            proc.join()
            parent.close()
            raise WorkerFailed(f"모델을 불러오지 못했습니다: {value}")
        self.proc, self.conn = proc, parent

    def stop(self):
        if self.proc is None:
            return
        self.proc.kill()
        self.proc.join()
        self.conn.close()
        self.proc = self.conn = None

    def run(self, *args, timeout):
        """runner.run(*args)를 실행한다. runner가 낸 예외는 그대로 다시 낸다.

        timeout초 안에 끝나지 않으면 프로세스를 종료하고 WorkerTimeout을 낸다.
        """
        if self.proc is None:
            raise WorkerFailed("작업 프로세스가 준비되지 않았습니다.")
        try:
            self.conn.send(args)
            if not self.conn.poll(max(timeout, 0)):
                self.stop()
                raise WorkerTimeout()
            status, value = self.conn.recv()
        except (EOFError, OSError) as e:
            self.stop()
            raise WorkerFailed(f"작업 프로세스가 비정상 종료됐습니다: {e!r}")
        if status == "raised":
            raise value
        return value

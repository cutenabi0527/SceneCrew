"""QLXD4 상태 수집 실행.

사용 (equipment/collector에서):
    uv run python collect.py --host <수신기 IP> --source-ref qlxd4-main --out captures/run.jsonl
    uv run python collect.py --host 127.0.0.1 --simulated --out captures/sim.jsonl   # 시뮬레이터 대상

수신기 IP는 --host 또는 환경변수 QLXD_HOST로 준다. IP는 기록 파일에 쓰지 않는다.
BE 전송은 --ingest-url과 환경변수 SCENECREW_COLLECTOR_TOKEN으로 켠다(계약 초안 단계).
Ctrl+C로 멈추면 계측을 끄고(METER_RATE 0) 연결을 닫는다.
"""

import argparse
import asyncio
import os
import signal
import socket
import sys

from client import CollectorConfig, QlxdCollector
from observation import RecordFactory
from sinks import ConsoleSink, HttpBatchSink, JsonlSink, MultiSink, RawCapture
from state import MemoryState, SqliteState, StateError


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="QLXD4 상태 수집")
    p.add_argument("--host", default=os.environ.get("QLXD_HOST"), help="수신기 IP (또는 QLXD_HOST)")
    p.add_argument("--port", type=int, default=2202)
    p.add_argument("--source-ip", default=None,
                   help="수신기가 연결된 노트북 어댑터의 IP. 직결 어댑터가 여럿일 때 지정한다.")
    p.add_argument("--source-ref", default="qlxd4-1",
                   help="장비를 가리키는 불투명 이름. IP·계정을 넣지 않는다.")
    p.add_argument("--collector-id", default=f"collector-{socket.gethostname()}")
    p.add_argument("--meter-rate", type=int, default=500,
                   help="계측 간격 ms (100~99999). 0이면 계측 스트림 없이 --poll-interval-ms마다 GET으로 묻는다")
    p.add_argument("--poll-interval-ms", type=int, default=1000)
    p.add_argument("--no-announce-wait", action="store_true",
                   help="접속 전 수신기 SLP 알림 대기를 끈다. 실기에서는 끄지 않는다")
    p.add_argument("--stale-after-ms", type=int, default=None)
    p.add_argument("--simulated", action="store_true", help="시뮬레이터 대상임을 기록(mode=SIMULATOR)")
    p.add_argument("--out", default=None, help="관측 기록 JSONL 경로")
    p.add_argument("--raw-log", default=None, help="송수신 원문 기록 경로(#302 증거)")
    p.add_argument("--ingest-url", default=os.environ.get("SCENECREW_INGEST_URL"))
    p.add_argument("--ingest-rejected", default=os.environ.get("SCENECREW_INGEST_REJECTED"),
                   help="서버가 거절한 묶음(다시 보내지 않음)을 남길 JSONL 경로. 상태 파일의 거절 표와 별도로 남긴다")
    p.add_argument("--state-db", default=os.environ.get("SCENECREW_COLLECTOR_STATE"),
                   help="runId·sequence·미전송 기록을 남기는 SQLite 상태 파일. 실제 전송(--ingest-url)에는 필수")
    p.add_argument("--run-id", default=os.environ.get("SCENECREW_COLLECTOR_RUN_ID"),
                   help="BE가 등록한 runId. 처음 한 번 지정하면 상태 파일에 고정된다")
    p.add_argument("--sample-every", type=int, default=10, help="콘솔에 SAMPLE을 n개마다 출력")
    p.add_argument("--duration", type=float, default=None, help="지정 초 뒤 자동 종료")
    p.add_argument("--keep-metering", action="store_true",
                   help="종료할 때 METER_RATE 0을 보내지 않는다(다른 제어 장치가 계측을 쓰는 경우)")
    args = p.parse_args(argv)
    if not args.host:
        p.error("--host 또는 QLXD_HOST가 필요하다")
    if args.ingest_url and not args.state_db and not args.simulated:
        p.error("실제 전송(--ingest-url)에는 --state-db(또는 SCENECREW_COLLECTOR_STATE)가 필요하다. "
                "재시작해도 run·sequence·미전송 기록을 이어 가기 위해서다")
    return args


def open_state(args):
    """전송 상태 저장소를 연다. 상태 파일이 있으면 그 run을 이어 쓰고, 없으면 등록된 runId로 새로 만든다.

    시뮬레이터에서만 runId를 지정하지 않아도 새로 만든다. 실제 전송 run은 BE가 등록한 값을 쓴다.
    """
    if args.state_db:
        return SqliteState(args.state_db, args.collector_id, args.run_id, allow_generated_run=args.simulated)
    return MemoryState(args.collector_id, args.run_id)


async def main(argv=None) -> None:
    args = parse_args(argv)
    config = CollectorConfig(
        host=args.host,
        port=args.port,
        source_ip=args.source_ip,
        meter_rate_ms=args.meter_rate,
        stale_after_s=None if args.stale_after_ms is None else args.stale_after_ms / 1000,
        meter_off_on_exit=not args.keep_metering,
        poll_interval_s=args.poll_interval_ms / 1000,
        # 시뮬레이터는 SLP 알림을 보내지 않는다.
        wait_for_announce=not (args.simulated or args.no_announce_wait),
    )
    mode = "SIMULATOR" if args.simulated else "LIVE"
    http = None
    state = None
    if args.ingest_url:
        try:
            state = open_state(args)
        except StateError as e:
            print(f"상태 파일 오류: {e}", file=sys.stderr)
            raise SystemExit(2) from e
        if not state.durable:
            print("경고: 상태 파일 없이 전송합니다(시뮬레이터). 재시작하면 미전송 기록과 sequence를 잃습니다.",
                  file=sys.stderr)
        factory = RecordFactory(collector_id=args.collector_id, source_ref=args.source_ref, mode=mode,
                                run_id=state.run_id, start_sequence=state.high_water)
        http = HttpBatchSink(args.ingest_url, args.collector_id, os.environ.get("SCENECREW_COLLECTOR_TOKEN"),
                             rejected_path=args.ingest_rejected, state=state)
        if state.pending_count():
            print(f"미전송 기록 {state.pending_count()}건을 그대로 다시 보냅니다(run={state.run_id}).")
        http.start()
    else:
        factory = RecordFactory(collector_id=args.collector_id, source_ref=args.source_ref, mode=mode,
                                **({"run_id": args.run_id} if args.run_id else {}))
    sink = MultiSink(ConsoleSink(args.sample_every), JsonlSink(args.out) if args.out else None, http)
    raw = RawCapture(args.raw_log) if args.raw_log else None

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    try:
        loop.add_signal_handler(signal.SIGINT, stop.set)
    except NotImplementedError:
        pass  # Windows: KeyboardInterrupt로 처리한다.
    if args.duration:
        loop.call_later(args.duration, stop.set)

    print(f"run={factory.run_id} source={args.source_ref} mode={factory.mode} "
          f"meter={config.meter_rate_ms}ms staleAfter={config.stale_after_s}s")
    collector = QlxdCollector(config, factory, sink, raw_capture=raw)
    task = asyncio.create_task(collector.run(stop))
    try:
        await asyncio.shield(task)
    except asyncio.CancelledError:
        stop.set()
        await task
    finally:
        await sink.close()
        if raw:
            raw.close()
        if http:
            print(f"ingest sent={http.sent} duplicates={http.duplicates} queued={http.queued} dropped={http.dropped_total} "
                  f"failures={http.failures} rejected={http.rejected} last_error={http.last_error}")
        if state:
            state.close()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass

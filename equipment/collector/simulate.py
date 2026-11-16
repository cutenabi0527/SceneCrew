"""가짜 QLXD4 수신기 실행. 수집기와 화면을 장비 없이 시험할 때 쓴다.

사용 (equipment/collector에서):
    uv run python simulate.py --port 2202 [--fragment]

실행 중 입력 명령:
    tx off | tx on      송신기 전원 변화
    speak on | speak off 발화/무음
    batt <0~5>          배터리 칸 변화
    drop                연결 끊기
    stall <초>          연결 유지한 채 송신 중단
    quit                종료
"""

import argparse
import asyncio
import threading

from simulator import QlxdSimulator


def _read_stdin(loop: asyncio.AbstractEventLoop, queue: asyncio.Queue) -> None:
    while True:
        try:
            line = input()
        except EOFError:
            line = "quit"
        loop.call_soon_threadsafe(queue.put_nowait, line.strip())
        if line.strip() == "quit":
            return


async def main() -> None:
    p = argparse.ArgumentParser(description="가짜 QLXD4 수신기")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=2202)
    p.add_argument("--fragment", action="store_true", help="메시지를 무작위로 쪼개 보낸다")
    p.add_argument("--seed", type=int, default=7)
    args = p.parse_args()

    sim = QlxdSimulator(host=args.host, port=args.port, fragment=args.fragment, seed=args.seed)
    port = await sim.start()
    print(f"simulated QLXD4 listening on {args.host}:{port} (실기 아님)")

    queue: asyncio.Queue = asyncio.Queue()
    threading.Thread(target=_read_stdin, args=(asyncio.get_running_loop(), queue), daemon=True).start()
    try:
        while True:
            cmd, *rest = ((await queue.get()).split() or [""])
            if cmd == "quit":
                break
            if cmd == "tx" and rest:
                await sim.set_tx(rest[0] == "on")
            elif cmd == "speak" and rest:
                await sim.set_speaking(rest[0] == "on")
            elif cmd == "batt" and rest and rest[0].isdigit():
                await sim.set_battery(int(rest[0]))
            elif cmd == "drop":
                await sim.drop_clients()
            elif cmd == "stall" and rest:
                sim.stall(float(rest[0]))
            elif cmd:
                print("명령: tx on|off, speak on|off, batt <0~5>, drop, stall <초>, quit")
                continue
            print(f"ok: {cmd} {' '.join(rest)} | received={len(sim.received)} refused={sim.refused_writes}")
    finally:
        await sim.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass

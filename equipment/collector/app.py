"""SceneCrew 장비 수집 프로그램(웹 연결, #399). 실행 파일로 묶어 더블클릭으로 쓴다.

처음 실행하면 WEB '내 연결'에서 받은 연결 코드를 묻는다. 코드를 넣으면 이 노트북을 수집기로 등록하고,
다음 실행부터는 코드 없이 다시 붙는다. 수신기를 찾아 서버에 알리고, WEB에서 장비 칸에 연결한 수신기만 수집한다.

개발 중 실행 (equipment/collector에서):
    uv run python app.py                                  # 실제 수신기(직결 LAN)
    uv run python app.py --sim 127.0.0.1:2202 --sim 127.0.0.1:2203   # 시뮬레이터 수신기 2대
    uv run python app.py --forget                         # 저장한 연결을 지우고 새 코드로 시작
"""

import argparse
import asyncio
import os
import sys

from state import StateError
from web_link import (DEFAULT_SERVER, PROGRAM_VERSION, RELINK_HINT, Credentials, Discovery, FixedReceivers,
                      LinkApi, ServerError, Supervisor, ends_link, host_label, link_local_ifaces, open_state)


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="SceneCrew 장비 수집 프로그램(웹 연결)")
    p.add_argument("--server", default=os.environ.get("SCENECREW_SERVER", DEFAULT_SERVER),
                   help="업무 서버 주소(처음 연결할 때만 쓴다. 연결한 뒤에는 저장한 주소를 쓴다)")
    p.add_argument("--sim", action="append", default=[], metavar="HOST:PORT",
                   help="시뮬레이터 수신기. 여러 번 줄 수 있다. 주면 실제 수신기를 찾지 않는다")
    p.add_argument("--iface", action="append", help="수신기 알림을 들을 노트북 어댑터 IP. 생략하면 169.254.x.x 전부")
    p.add_argument("--forget", action="store_true", help="저장한 연결을 지우고 새 연결 코드로 시작한다")
    return p.parse_args(argv)


def parse_sim(values: list[str]) -> list[tuple[str, int]]:
    out = []
    for v in values:
        host, _, port = v.rpartition(":")
        out.append((host or "127.0.0.1", int(port)))
    return out


def ask_code(api: LinkApi, read=input, out=print) -> Credentials | None:
    out("WEB의 '내 연결' → 장비 연결에서 받은 연결 코드(예: ABCD-EFGH)를 입력하세요. 빈 줄이면 끝냅니다.")
    while True:
        try:
            code = read("연결 코드: ").strip()
        except EOFError:
            return None
        if not code:
            return None
        try:
            return api.pair(code, host_label())
        except ServerError as e:
            if e.status == 401:
                out("코드가 맞지 않거나 만료됐거나 이미 쓴 코드입니다. WEB에서 새 코드를 받아 넣으세요.")
            elif e.status == 429:
                out("시도가 너무 많습니다. 1분 뒤 다시 넣으세요.")
            else:
                out(f"{e} 인터넷 연결을 확인하고 다시 넣으세요.")


async def collect(cred: Credentials, args, out=print) -> ServerError | None:
    sims = parse_sim(args.sim)
    source = FixedReceivers(sims) if sims else Discovery(args.iface, find_ifaces=link_local_ifaces)
    state = open_state(cred)
    supervisor = Supervisor(LinkApi(cred.server), cred, source, state,
                            mode="SIMULATOR" if sims else "LIVE", out=out)
    stop = asyncio.Event()
    try:
        return await supervisor.run(stop)
    finally:
        http = supervisor.http
        out(f"보낸 기록 {http.sent}건, 남은 기록 {http.queued}건, 연결 해제로 보내지 않은 기록 {http.unlinked_dropped}건")
        state.close()


def main(argv=None) -> int:
    args = parse_args(argv)
    print(f"SceneCrew 장비 수집 프로그램 {PROGRAM_VERSION}")
    if args.forget:
        Credentials.forget()
    cred = Credentials.load()
    if cred is None:
        cred = ask_code(LinkApi(args.server))
        if cred is None:
            return 1
        cred.save()
        print("이 노트북을 수집기로 등록했습니다. 다음부터는 코드 없이 바로 시작합니다.")
    else:
        print("저장한 연결로 다시 붙습니다.")
    print("수신기를 찾고 있습니다. WEB '내 연결'에서 찾은 수신기를 장비 칸에 연결하세요. 끝내려면 Ctrl+C.")
    print("공연 중에는 노트북 쪽 LAN 케이블을 고정하세요(빠지면 수신기 네트워크가 멈출 수 있습니다).")
    try:
        reason = asyncio.run(collect(cred, args))
    except StateError as e:
        print(f"상태 파일 오류: {e}")
        return 2
    except KeyboardInterrupt:
        print("멈췄습니다.")
        return 0
    if reason is not None and ends_link(reason):
        Credentials.forget()
        why = "연결이 해제됐거나 다른 사람이 넘겨받았습니다" if reason.status == 401 else "연결 자격이 없어졌습니다"
        print(f"{why}. 수집을 멈췄습니다. {RELINK_HINT}")
        return 3
    return 0


def pause_if_double_clicked() -> None:
    """실행 파일을 더블클릭하면 창이 바로 닫히므로 안내를 읽을 수 있게 기다린다."""
    if getattr(sys, "frozen", False):
        try:
            input("창을 닫으려면 Enter를 누르세요.")
        except (EOFError, KeyboardInterrupt):
            pass


if __name__ == "__main__":
    try:
        code = main()
    except KeyboardInterrupt:
        code = 0
    pause_if_double_clicked()
    raise SystemExit(code)

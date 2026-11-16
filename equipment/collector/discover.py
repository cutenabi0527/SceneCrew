"""직결한 Shure 수신기의 주소를 찾는다. 아무것도 보내지 않고 SLP 알림만 듣는다.

사용 (equipment/collector에서):
    uv run python discover.py                 # 169.254.x.x 어댑터 전부에서 20초
    uv run python discover.py --seconds 30 --iface 169.254.5.231

수신기는 SLP 멀티캐스트(239.255.254.253:8427)로 `acn-fctn=QLXD: Rx` 같은 알림을 주기적으로 보낸다.
네트워크 대역을 탐색하지 않으며, 결과의 장비 주소와 노트북 어댑터 주소를 collect.py의
--host와 --source-ip로 쓴다.
"""

import argparse
import re
import select
import subprocess
import time

from announce import SLP_GROUP, SLP_PORT, open_listener


def link_local_ifaces() -> list[str]:
    """노트북의 169.254.x.x 어댑터 주소. Windows ipconfig 출력에서 읽는다."""
    try:
        out = subprocess.run(["ipconfig"], capture_output=True, text=True, errors="replace").stdout
    except OSError:
        return []
    return sorted(set(re.findall(r"169\.254\.\d+\.\d+", out)))


def listen(ifaces: list[str], seconds: float) -> dict[str, dict]:
    sock = open_listener(ifaces)
    found: dict[str, dict] = {}
    end = time.time() + seconds
    while time.time() < end:
        ready, _, _ = select.select([sock], [], [], 1.0)
        if not ready:
            continue
        data, (addr, _) = sock.recvfrom(4096)
        text = data.decode("latin-1")
        fctn = re.search(r"acn-fctn=([^)]*)", text)
        cid = re.search(r"cid=([0-9A-F-]+)", text)
        entry = found.setdefault(addr, {"function": None, "cid": None, "announcements": 0})
        entry["announcements"] += 1
        entry["function"] = entry["function"] or (fctn.group(1) if fctn else None)
        entry["cid"] = entry["cid"] or (cid.group(1) if cid else None)
    sock.close()
    return found


def main() -> None:
    p = argparse.ArgumentParser(description="직결 Shure 장비 주소 찾기(수동 수신)")
    p.add_argument("--seconds", type=float, default=20)
    p.add_argument("--iface", action="append", help="듣을 노트북 어댑터 IP. 생략하면 169.254.x.x 전부")
    args = p.parse_args()
    ifaces = args.iface or link_local_ifaces()
    print(f"listening {args.seconds}s on {ifaces or ['(기본)']} for SLP {SLP_GROUP}:{SLP_PORT}")
    found = listen(ifaces, args.seconds)
    if not found:
        print("찾은 장비 없음. 케이블·수신기 전원·네트워크 설정을 확인한다.")
    for addr, info in sorted(found.items()):
        print(f"{addr}  {info['function']}  announcements={info['announcements']}  cid={info['cid']}")


if __name__ == "__main__":
    main()

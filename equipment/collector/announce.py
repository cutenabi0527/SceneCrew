"""Shure 수신기의 SLP 알림(239.255.254.253:8427)을 듣기만 한다. 아무것도 보내지 않는다.

수신기는 네트워크가 준비되면 `acn-fctn=QLXD: Rx` 알림을 약 4초마다 보낸다. 수집기는 처음 접속할 때
이 알림으로 수신기가 준비됐는지 확인하고, discover.py는 주소를 찾는 데 쓴다.
"""

import select
import socket
import struct
import time

SLP_GROUP = "239.255.254.253"
SLP_PORT = 8427


def open_listener(iface_ips: list[str]) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", SLP_PORT))
    for ip in iface_ips or ["0.0.0.0"]:
        try:
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                            struct.pack("4s4s", socket.inet_aton(SLP_GROUP), socket.inet_aton(ip)))
        except OSError:
            pass
    return sock


def wait_for_announcement(host: str, iface_ip: str | None, timeout_s: float, stop_check=lambda: False) -> bool:
    """host가 보낸 SLP 알림을 timeout_s 안에 들으면 True. 어댑터가 다시 인식될 수 있어 소켓을 주기적으로 새로 연다."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline and not stop_check():
        try:
            sock = open_listener([iface_ip] if iface_ip else [])
        except OSError:
            time.sleep(1.0)
            continue
        try:
            window_end = min(deadline, time.monotonic() + 5.0)
            while time.monotonic() < window_end and not stop_check():
                ready, _, _ = select.select([sock], [], [], 0.5)
                if not ready:
                    continue
                _, (addr, _) = sock.recvfrom(4096)
                if addr == host:
                    return True
        except OSError:
            time.sleep(0.5)
        finally:
            sock.close()
    return False

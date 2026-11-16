"""수집기 로컬 상태(#305·#330). 재시작해도 runId·sequence·미전송 기록을 잃지 않게 SQLite에 남긴다.

수집 내부 계약(docs/api-contract/drafts/v9-327/internal-collector-proposed.yaml의 x-run-persistence)을 따른다.
- runId는 BE가 등록해 준 값을 설정으로 받아 고정한다. 재시작해도 같은 run을 쓴다.
- 기록을 대기열에 넣을 때 sequence 상한을 같은 트랜잭션에서 올린다. 재시작하면 상한 다음 번호부터 이어 쓴다.
  서버의 lastSequence로 다음 번호를 추정하지 않는다.
- 미전송 기록은 바꾸지 않고 그대로 다시 보낸다. 서버 ACK를 확인한 뒤에만 지운다.
- 영구 거절 묶음(400·403·409·413·422 등)은 거절 표로 옮겨 남긴다(격리).
- 상태 파일이 손상되면 새 run을 만들지 않고 멈춘다. 새 run은 BE 절차로 받는다.

상태 파일이 없는 시험·시뮬레이터용으로 같은 규칙의 메모리 저장소(MemoryState)도 둔다.
"""

import json
import sqlite3
import uuid
from collections import deque
from pathlib import Path

SCHEMA_VERSION = "1"


class StateError(RuntimeError):
    """상태 파일을 믿을 수 없거나 설정과 맞지 않는다. 수집기를 시작하지 않는다."""


class MemoryState:
    """프로세스 메모리만 쓰는 저장소. 재시작하면 기록·번호를 잃는다(시험·시뮬레이터 전용)."""

    durable = False

    def __init__(self, collector_id: str, run_id: str | None = None, *, max_queue: int = 5000):
        self.collector_id = collector_id
        self.run_id = run_id or str(uuid.uuid4())
        self.max_queue = max_queue
        self.high_water = 0
        self.dropped = 0
        self._queue: deque[dict] = deque()
        self.rejections: list[dict] = []

    def enqueue(self, record: dict) -> None:
        self.high_water = max(self.high_water, record["sequence"])
        if len(self._queue) >= self.max_queue:
            self._queue.popleft()
            self.dropped += 1
        self._queue.append(record)

    def pending(self, limit: int) -> list[dict]:
        return [self._queue[i] for i in range(min(limit, len(self._queue)))]

    def pending_count(self) -> int:
        return len(self._queue)

    def acknowledge(self, sequences: list[int]) -> None:
        done = set(sequences)
        self._queue = deque(r for r in self._queue if r["sequence"] not in done)
        self.dropped = 0

    def discard(self, sequences: list[int]) -> None:
        """보내지 않을 기록(연결 해제된 수신기)을 지운다. 서버에 알릴 버린 수는 건드리지 않는다."""
        done = set(sequences)
        self._queue = deque(r for r in self._queue if r["sequence"] not in done)

    def quarantine(self, sequences: list[int], entry: dict) -> None:
        done = set(sequences)
        self._queue = deque(r for r in self._queue if r["sequence"] not in done)
        self.rejections.append(entry)

    def close(self) -> None:
        pass


class SqliteState:
    """SQLite 상태 파일. 한 파일은 수집기 하나·run 하나에 묶인다."""

    durable = True

    def __init__(self, path: str | Path, collector_id: str, run_id: str | None = None, *,
                 allow_generated_run: bool = False, max_queue: int = 5000):
        self.path = Path(path)
        self.collector_id = collector_id
        self.max_queue = max_queue
        fresh = not self.path.exists()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        try:
            self._db = sqlite3.connect(self.path, isolation_level=None)
            self._db.execute("PRAGMA journal_mode=WAL")
            self._db.execute("PRAGMA synchronous=FULL")
            check = self._db.execute("PRAGMA integrity_check").fetchone()[0]
            if check != "ok":
                raise StateError(f"상태 파일 무결성 검사 실패: {check}")
            if fresh:
                self._create()
            meta = self._meta()
        except sqlite3.DatabaseError as e:
            raise StateError(f"상태 파일을 열 수 없습니다({e}). 새 run을 임의로 만들지 않습니다. "
                             "BE 절차로 run을 다시 받은 뒤 새 상태 파일로 시작하세요.") from e
        if meta.get("schemaVersion") != SCHEMA_VERSION:
            raise StateError(f"상태 파일 형식이 다릅니다(schemaVersion={meta.get('schemaVersion')}).")
        if meta.get("collectorId") is not None and meta["collectorId"] != collector_id:
            raise StateError(f"상태 파일은 수집기 {meta['collectorId']}의 것입니다. "
                             f"지금 수집기({collector_id})와 다릅니다.")
        stored_run = meta.get("runId")
        if stored_run is None:
            if run_id is None:
                if not allow_generated_run:
                    raise StateError("등록된 runId(--run-id)가 필요합니다. 실제 전송 run은 BE가 등록합니다.")
                run_id = str(uuid.uuid4())
            self._set({"collectorId": collector_id, "runId": run_id})
            stored_run = run_id
        elif run_id is not None and run_id != stored_run:
            raise StateError(
                f"상태 파일의 run({stored_run})과 지정한 run({run_id})이 다릅니다. "
                f"미전송 기록 {self.pending_count()}건이 남아 있을 수 있습니다. run 전환은 BE 절차로 하고, "
                "새 run은 새 상태 파일로 시작하세요.")
        self.run_id = stored_run

    # 내부 ---------------------------------------------------------------

    def _create(self) -> None:
        with self._tx():
            self._db.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            self._db.execute("CREATE TABLE pending (sequence INTEGER PRIMARY KEY, record TEXT NOT NULL)")
            self._db.execute(
                "CREATE TABLE rejected (id INTEGER PRIMARY KEY AUTOINCREMENT, rejected_at TEXT NOT NULL, "
                "status INTEGER, code TEXT, request_id TEXT, first_sequence INTEGER, last_sequence INTEGER, "
                "batch TEXT NOT NULL)")
            self._db.executemany("INSERT INTO meta VALUES (?, ?)", [
                ("schemaVersion", SCHEMA_VERSION), ("highWater", "0"), ("dropped", "0")])

    def _tx(self):
        db = self._db

        class Tx:
            def __enter__(self):
                db.execute("BEGIN IMMEDIATE")

            def __exit__(self, kind, value, tb):
                db.execute("ROLLBACK" if kind else "COMMIT")
                return False

        return Tx()

    def _meta(self) -> dict:
        return dict(self._db.execute("SELECT key, value FROM meta").fetchall())

    def _set(self, values: dict) -> None:
        with self._tx():
            self._db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)",
                                 [(k, str(v)) for k, v in values.items()])

    def _get_int(self, key: str) -> int:
        row = self._db.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
        return int(row[0]) if row else 0

    # 공개 ---------------------------------------------------------------

    @property
    def high_water(self) -> int:
        return self._get_int("highWater")

    @property
    def dropped(self) -> int:
        return self._get_int("dropped")

    def enqueue(self, record: dict) -> None:
        """기록 추가와 sequence 상한 갱신을 한 트랜잭션으로 한다. 넘치면 가장 오래된 기록을 버리고 센다."""
        if record.get("runId") != self.run_id:
            raise StateError(f"다른 run의 기록입니다: {record.get('runId')}")
        sequence = int(record["sequence"])
        with self._tx():
            self._db.execute("INSERT INTO pending VALUES (?, ?)",
                             (sequence, json.dumps(record, ensure_ascii=False, separators=(",", ":"))))
            self._db.execute(
                "UPDATE meta SET value = CAST(MAX(CAST(value AS INTEGER), ?) AS TEXT) WHERE key = 'highWater'",
                (sequence,))
            over = self._db.execute("SELECT COUNT(*) FROM pending").fetchone()[0] - self.max_queue
            if over > 0:
                self._db.execute(
                    "DELETE FROM pending WHERE sequence IN "
                    "(SELECT sequence FROM pending ORDER BY sequence LIMIT ?)", (over,))
                self._db.execute(
                    "UPDATE meta SET value = CAST(CAST(value AS INTEGER) + ? AS TEXT) WHERE key = 'dropped'",
                    (over,))

    def pending(self, limit: int) -> list[dict]:
        rows = self._db.execute("SELECT record FROM pending ORDER BY sequence LIMIT ?", (limit,)).fetchall()
        return [json.loads(r[0]) for r in rows]

    def pending_count(self) -> int:
        return self._db.execute("SELECT COUNT(*) FROM pending").fetchone()[0]

    def acknowledge(self, sequences: list[int]) -> None:
        """서버가 받은 기록을 지우고 버린 수를 초기화한다(다음 묶음의 droppedSinceLast)."""
        with self._tx():
            self._db.executemany("DELETE FROM pending WHERE sequence = ?", [(s,) for s in sequences])
            self._db.execute("UPDATE meta SET value = '0' WHERE key = 'dropped'")

    def discard(self, sequences: list[int]) -> None:
        """보내지 않을 기록(연결 해제된 수신기)을 지운다. 서버에 알릴 버린 수는 건드리지 않는다."""
        with self._tx():
            self._db.executemany("DELETE FROM pending WHERE sequence = ?", [(s,) for s in sequences])

    def quarantine(self, sequences: list[int], entry: dict) -> None:
        """영구 거절 묶음을 거절 표로 옮긴다. 다시 보내지 않는다."""
        with self._tx():
            self._db.execute(
                "INSERT INTO rejected (rejected_at, status, code, request_id, first_sequence, last_sequence, batch) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (entry["rejectedAt"], entry.get("status"), entry.get("code"), entry.get("requestId"),
                 min(sequences) if sequences else None, max(sequences) if sequences else None,
                 json.dumps(entry["batch"], ensure_ascii=False)))
            self._db.executemany("DELETE FROM pending WHERE sequence = ?", [(s,) for s in sequences])

    def rejected_count(self) -> int:
        return self._db.execute("SELECT COUNT(*) FROM rejected").fetchone()[0]

    def close(self) -> None:
        self._db.close()

import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { useData, useEvent, useResponseQueues, useViewer } from '../../domain/hooks';
import { toMin } from '../../domain/selectors';
import type { Change, Issue, IssueReport } from '../../domain/types';
import { ChangeReview } from '../../features/change/ChangeReview';
import { FollowupRow, IssueRow } from '../../features/issue/IssueRow';
import { IssueWork } from '../../features/issue/IssueWork';
import { useShell } from '../../layouts/shell-actions';
import { Empty } from '../../shared/ui/atoms';
import styles from './web.module.css';

type Q = 'new' | 'approve' | 'resolve' | 'closed';
type Item = { type: 'issue'; o: Issue; q: Q } | { type: 'change'; o: Change; q: Q };
type Group = [key: Q, label: string, items: Item[]];
/** 보고 아래에 잇는 것: 그 보고의 대응안(들여쓰기) / 추가 보고(같은 줄기) */
type Child =
    | { type: 'change'; o: Change; at: string }
    | { type: 'followup'; r: IssueReport; at: string; key: string };
/** 목록 한 덩어리: 보고(+ 이어진 대응안·추가 보고) 또는 보고에 묶이지 않은 변경안 */
type Entry =
    { type: 'issue'; o: Issue; q: Q; children: Child[] } | Extract<Item, { type: 'change' }>;

const ALL: Q[] = ['new', 'approve', 'resolve', 'closed'];
/** 가장 늦은 HH:MM(비어 있으면 무시) */
const latest = (...ts: (string | null | undefined)[]) =>
    ts.reduce<number>((m, t) => (t ? Math.max(m, toMin(t)) : m), -1);
/** k번째 뒤에 추가 보고가 더 있는가(있으면 줄기를 아래로 잇는다) */
const followAfter = (xs: Child[], k: number) => xs.slice(k + 1).some((c) => c.type === 'followup');

/**
 * W04 보고·대응(WEB 배치): 왼쪽 목록 / 오른쪽 작업 영역을 한 화면에.
 * 큐 탭 없이 신규·대기·해결·종료를 한 목록에 모두 보이고, 카드 왼쪽 색 바로 구분한다.
 * 목록 위 체크 상자(색 바 범례 겸용)에서 원하는 상태만 골라 보며 기본은 모두 체크다.
 * 보고 아래에는 그 보고의 대응안(ㄴ 들여쓰기)과 추가 보고를 시간 순으로 선으로 이어 보인다.
 * 목록은 기본으로 최종 편집 시각이 늦은 것부터 보인다.
 * 총괄은 전체 이슈와 확정 대기 변경, 팀원은 본인 보고·관련 이슈만 본다.
 * 데이터(useResponseQueues)와 오른쪽 IssueWork는 APP 진행 보고 탭·보고 상세(M07)와 같다.
 */
export function WebResponse() {
    const ev = useEvent();
    const { issueId } = useParams();
    const navigate = useNavigate();
    const shell = useShell();
    const q = useResponseQueues();
    const d = useData();
    const v = useViewer();
    // 체크한 항목만 보인다. 기본은 전체(모두 체크)
    const [shown, setShown] = useState<ReadonlySet<Q>>(() => new Set(ALL));

    const issues = (xs: Issue[], k: Q): Item[] => xs.map((o) => ({ type: 'issue', o, q: k }));
    const groups: Group[] = v.isChief
        ? [
              ['new', '신규', issues(q.news, 'new')],
              [
                  'approve',
                  '대기',
                  q.approve.map((o): Item => ({ type: 'change', o, q: 'approve' })),
              ],
              ['resolve', '해결', issues(q.resolve, 'resolve')],
              ['closed', '종료', issues(q.closed, 'closed')],
          ]
        : [
              ['new', '진행', issues(q.news, 'new')],
              ['resolve', '해결', issues(q.resolve, 'resolve')],
              ['closed', '종료', issues(q.closed, 'closed')],
          ];
    const list = groups.filter(([k]) => shown.has(k)).flatMap(([, , xs]) => xs);
    // 주소로 연 대응안이 확정·폐기돼 목록(확정 대기)에서 빠져도 오른쪽에는 그대로 보여 결과를 확인하게 한다
    const opened = d.changes.find((c) => c.id === issueId);
    const sel: Item | undefined =
        list.find((x) => x.o.id === issueId) ??
        (opened ? { type: 'change', o: opened, q: 'approve' } : list[0]);
    // 보고와 그 대응안·추가 보고를 한 덩어리로 묶는다. 대응안은 '대기'를 체크했을 때만 잇는다
    const shownIssues = new Set(list.filter((x) => x.type === 'issue').map((x) => x.o.id));
    const entries: Entry[] = list.flatMap((x): Entry[] => {
        if (x.type === 'change') return x.o.issueId && shownIssues.has(x.o.issueId) ? [] : [x];
        const children: Child[] = [
            ...(shown.has('approve') ? q.approve : [])
                .filter((c) => c.issueId === x.o.id)
                .map((c): Child => ({ type: 'change', o: c, at: c.at })),
            ...x.o.reports
                .filter((r) => r.kind === 'FOLLOWUP')
                .map((r, k): Child => ({ type: 'followup', r, at: r.at, key: `${x.o.id}-f${k}` })),
        ].sort((a, b) => a.at.localeCompare(b.at));
        return [{ type: 'issue', o: x.o, q: x.q, children }];
    });
    // 기본 정렬: 최종 편집 시각이 늦은 덩어리가 위. 보고는 접수·추가/해결 보고·전달·종료와
    // 이어진 대응안의 시각 중 가장 늦은 것, 변경안은 작성·적용 시각을 본다(같으면 원래 순서)
    const editedAt = (x: Entry) =>
        x.type === 'change'
            ? latest(x.o.at, x.o.appliedAt)
            : latest(
                  x.o.at,
                  x.o.closedAt,
                  ...x.o.reports.map((r) => r.at),
                  ...(x.o.forwards ?? []).map((f) => f.at),
                  ...x.children.map((c) => c.at),
              );
    entries.sort((a, b) => editedAt(b) - editedAt(a));
    const pick = (id: string) => navigate(`/e/response/${id}`);

    return (
        <div className={`stack ${styles.response}`}>
            <div className={styles.split}>
                <div className={`stack s6 ${styles.splitList}`}>
                    {/* 보고 접수: 카드 목록 칸 맨 위 가운데 */}
                    <button
                        type="button"
                        className={`btn sm primary ${styles.reportBtn}`}
                        disabled={ev.state !== 'LIVE'}
                        title={
                            ev.state !== 'LIVE'
                                ? '운영 중인 행사에서만 보고를 접수합니다'
                                : undefined
                        }
                        onClick={() => shell.openReport()}
                    >
                        보고 접수
                    </button>
                    {/* 카드 왼쪽 색 바 = 처리 상태. 체크한 상태만 목록에 보인다 */}
                    <div className={styles.barLegend} role="group" aria-label="볼 처리 상태">
                        {groups.map(([k, label]) => (
                            <label key={k} data-tone={k}>
                                <input
                                    type="checkbox"
                                    checked={shown.has(k)}
                                    onChange={() => {
                                        const next = new Set(shown);
                                        if (next.has(k)) next.delete(k);
                                        else next.add(k);
                                        setShown(next);
                                    }}
                                />
                                <span>{label}</span>
                            </label>
                        ))}
                    </div>
                    {entries.length ? (
                        entries.map((x) => {
                            if (x.type === 'change')
                                return (
                                    <IssueRow
                                        key={x.o.id}
                                        tone={x.q}
                                        change={x.o}
                                        selected={sel?.o.id === x.o.id}
                                        onSelect={() => pick(x.o.id)}
                                    />
                                );
                            const head = (
                                <IssueRow
                                    key={x.o.id}
                                    tone={x.q}
                                    issue={x.o}
                                    selected={sel?.o.id === x.o.id}
                                    onSelect={() => pick(x.o.id)}
                                />
                            );
                            if (!x.children.length) return head;
                            // 보고 - 대응안(ㄴ 들여쓰기) - 추가 보고를 시간 순으로 선으로 잇는다
                            return (
                                <div
                                    key={x.o.id}
                                    className={styles.thread}
                                    data-flat={!followAfter(x.children, -1) || undefined}
                                >
                                    {/* 줄기는 보고와 추가 보고 사이에만 그린다(대응안만 있으면 ㄴ 선만) */}
                                    <div
                                        className={styles.threadNode}
                                        data-trunk={
                                            followAfter(x.children, -1) ? 'down' : undefined
                                        }
                                    >
                                        {head}
                                    </div>
                                    {x.children.map((c, k) =>
                                        c.type === 'change' ? (
                                            <div
                                                key={c.o.id}
                                                className={styles.threadBranch}
                                                data-trunk={
                                                    followAfter(x.children, k) ? 'both' : undefined
                                                }
                                            >
                                                <IssueRow
                                                    tone="approve"
                                                    change={c.o}
                                                    selected={sel?.o.id === c.o.id}
                                                    onSelect={() => pick(c.o.id)}
                                                />
                                            </div>
                                        ) : (
                                            <div
                                                key={c.key}
                                                className={styles.threadNode}
                                                data-trunk={
                                                    followAfter(x.children, k) ? 'both' : 'up'
                                                }
                                            >
                                                <FollowupRow
                                                    report={c.r}
                                                    tone={x.q}
                                                    onSelect={() => pick(x.o.id)}
                                                />
                                            </div>
                                        ),
                                    )}
                                </div>
                            );
                        })
                    ) : (
                        <Empty>처리할 항목이 없습니다</Empty>
                    )}
                </div>
                <div className={styles.splitPane}>
                    {sel?.type === 'issue' ? (
                        <IssueWork key={sel.o.id} issue={sel.o} />
                    ) : sel?.type === 'change' ? (
                        <ChangeReview
                            key={sel.o.id}
                            id={sel.o.id}
                            onDone={() => navigate('/e/response')}
                        />
                    ) : (
                        <Empty>왼쪽 목록에서 처리할 항목을 고르세요</Empty>
                    )}
                </div>
            </div>
        </div>
    );
}

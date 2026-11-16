import { useEffect, useRef, useState } from 'react';
import {
    useData,
    useEvent,
    useIssue,
    useMe,
    useMyTasks,
    useSession,
    useTask,
    useViewer,
} from '../../domain/hooks';
import { ISSUE_STATUS_LABEL, NOW_MIN, toMin } from '../../domain/selectors';
import { newId, useDemoState, useDispatch } from '../../domain/store-context';
import { useIdentity } from '../identity/context';
import { VoiceReport } from '../voice/VoiceReport';
import type { ReportVia } from '../../domain/types';
import type { TranscriptionResult } from '../transcription/contract';
import { demoTranscribe } from '../transcription/demo';
import {
    useLeaveGuard,
    useLeaveProtection,
    useModalBackHandler,
} from '../../shared/navigation/leave-guard-context';
import { Icon } from '../../shared/ui/Icon';
import { Note } from '../../shared/ui/atoms';
import styles from './appReport.module.css';

/** 빠른 보고 문구는 웹 ReportForm과 같다. 누르면 내용만 채우고 바로 보내지 않는다 */
const QUICK = [
    { label: '진행 지연', text: '업무 진행이 지연되고 있습니다.' },
    { label: '인원 지원', text: '현장에 인원 지원이 필요합니다.' },
    { label: '장비 문제', text: '장비가 정상 동작하지 않습니다.' },
    { label: '혼잡', text: '대기열이 길어져 혼잡합니다.' },
];
/** 녹음 최대 길이(초). 넘으면 말하기 끝과 같이 전사로 넘어간다 */
const MAX_SEC = 30;
const clock = (sec: number) => `0:${String(sec).padStart(2, '0')}`;

export type AppReportFormProps = {
    taskId?: string;
    issueId?: string;
    kind?: 'FOLLOWUP' | 'RESOLUTION';
    onDone: () => void;
};

/**
 * 상황 보고(APP): 음성 먼저. 열면 음성 입력 전체 화면 → '말하기 끝' → 말한 내용 확인,
 * '직접 쓰기'를 누르면 직접 쓰는 폼. 추가·해결 보고(issue)도 같은 화면에 이어 쓸 보고를 위에 둔다.
 * 상태·보내기·이탈 보호·Android Back·실서버 음성은 웹 ReportForm과 같은 규칙을 따른다.
 */
export function AppReportForm({ taskId, issueId, kind, onDone }: AppReportFormProps) {
    const ev = useEvent();
    const v = useViewer();
    const { group } = useMe();
    const d = useData();
    const { open } = useMyTasks();
    const issue = useIssue(issueId);
    const dispatch = useDispatch();
    // 관련 업무는 지금 시각에 진행 중인 내 업무(진행 중 상태이거나 예정 시간 안)만 고른다.
    // 업무 카드의 빠른 보고로 연 업무는 시간대가 달라도 목록에 남긴다.
    const current = open.filter(
        (t) =>
            t.id === taskId ||
            t.status === 'doing' ||
            (toMin(t.start) <= NOW_MIN && NOW_MIN < toMin(t.end)),
    );
    // 구역: 총괄은 전체, 팀원은 본인 업무·소속 그룹 업무가 있는 허용 구역만 고른다(M06)
    const allowed = v.isChief
        ? ev.zones
        : ev.zones.filter(
              (z) =>
                  open.some((t) => t.zone === z) ||
                  d.tasks.some((t) => t.groupId === group?.id && t.zone === z),
          );
    const zones = allowed.length ? allowed : ev.zones;
    // 관련 업무는 지금 하는 업무를 먼저 골라 둔다(바꾸기로 다른 업무·공통 보고를 고를 수 있다)
    const doing = current.find((t) => t.status === 'doing') ?? current[0];
    const [task, setTask] = useState(taskId ?? (issueId ? '' : (doing?.id ?? '')));
    const linked = useTask(task || undefined);
    const [zone, setZone] = useState(issue?.zone ?? linked?.zone ?? zones[0] ?? '');
    const [text, setText] = useState('');
    const [template, setTemplate] = useState<string | null>(null);
    const [via, setVia] = useState<ReportVia>('텍스트');
    // 음성: 녹음 → 전사(060) → 내용 칸에 바로 채움(M06-VOICE). 전사는 항상 원음으로 한다(V9-D41).
    // 채운 문장은 고칠 수 있고 "보고 보내기"를 눌러야 제출된다(자동 제출 없음).
    // 실서버(#373)는 데모 녹음 흉내 대신 실제 녹음·전사(060, VoiceReport)를 쓰고 음성 화면을 띄우지 않는다.
    const serverMode = !!useDemoState().live;
    const session = useIdentity();
    const { eventId } = useSession();
    const scope = serverMode && eventId ? session.scopes.participation(eventId) : null;
    const [audioId, setAudioId] = useState<string | null>(null);
    const [voice, setVoice] = useState<'idle' | 'rec' | 'stt'>(serverMode ? 'idle' : 'rec');
    const [stt, setStt] = useState<TranscriptionResult | null>(null);
    const [playing, setPlaying] = useState(false);
    // 음성 입력 화면(전체 화면). 녹음은 사용자가 "말하기 끝"을 누르거나 최대 시간이 될 때까지 이어진다
    const [overlay, setOverlay] = useState(!serverMode);
    const [elapsed, setElapsed] = useState(0);
    const [error, setError] = useState(false);

    // 데모: 전사 과정을 흉내 낸다. 실제 STT 연동 시 교체.
    useEffect(() => {
        if (voice !== 'stt') return;
        const h = window.setTimeout(() => {
            // 전사가 끝나면 확인 단계 없이 내용 칸에 바로 채운다
            const result = demoTranscribe();
            setStt(result);
            setText(result.text);
            setTemplate(null);
            setVia('음성');
            setError(false);
            setVoice('idle');
            setOverlay(false);
        }, 900);
        return () => window.clearTimeout(h);
    }, [voice]);

    // 녹음 시간 막대: 실제 경과 시간. 최대 시간이 되면 말하기 끝과 같이 전사로 넘어간다
    useEffect(() => {
        if (!overlay || voice !== 'rec') return;
        const start = Date.now();
        const h = window.setInterval(() => {
            const sec = Math.min(MAX_SEC, Math.floor((Date.now() - start) / 1000));
            setElapsed(sec);
            if (sec >= MAX_SEC) setVoice('stt');
        }, 250);
        return () => window.clearInterval(h);
    }, [overlay, voice]);

    const startVoice = () => {
        setElapsed(0);
        setPlaying(false);
        setOverlay(true);
        setVoice('rec');
    };
    /** 녹음·전사를 그만두고 직접 쓰기로 간다. 쓰던 내용은 그대로 둔다 */
    const stopVoice = () => {
        setVoice('idle');
        setOverlay(false);
        setPlaying(false);
    };

    // Android Back·이탈 보호(#260): 음성 입력 화면은 Back이 먼저 닫고(입력 유지),
    // 녹음·전사 중이거나 보내지 않은 내용이 있으면 화면을 떠나기 전에 확인한다.
    useModalBackHandler(overlay, stopVoice);
    useLeaveProtection(voice === 'rec' || voice === 'stt', 'recording');
    const guard = useLeaveGuard();
    const dirty = text.trim() !== '';
    const releaseUnsaved = useRef<(() => void) | null>(null);
    useEffect(() => {
        if (!dirty) return;
        const release = guard.registerProtection({ reason: 'unsaved' });
        releaseUnsaved.current = release;
        return () => {
            release();
            releaseUnsaved.current = null;
        };
    }, [guard, dirty]);

    useEffect(() => {
        if (!playing) return;
        const h = window.setTimeout(() => setPlaying(false), 1500);
        return () => window.clearTimeout(h);
    }, [playing]);

    const live = ev.state === 'LIVE';
    const submit = () => {
        // 운영 중인 행사에서만 새 보고를 받는다(종료 뒤에는 읽기만)
        if (!live) return;
        if (!text.trim()) {
            setError(true);
            return;
        }
        dispatch({
            type: 'report',
            input: {
                id: newId('I'),
                taskId: task || null,
                issueId: issueId ?? null,
                zone,
                text: text.trim(),
                via,
                // 음성 보고면 전사 접수(060)의 원음 id를 함께 보낸다. 내용은 본인이 고친 문장이다.
                audioId: via === '음성' ? audioId : null,
                kind: issueId ? (kind ?? 'FOLLOWUP') : 'INITIAL',
            },
        });
        // 보낸 내용은 더 지킬 입력이 아니다. 화면 이동 전에 보호를 푼다.
        releaseUnsaved.current?.();
        onDone();
    };

    if (overlay) {
        const rec = voice === 'rec';
        return (
            <div
                className={`${styles.voice} ${rec ? styles.listening : ''}`}
                role="dialog"
                aria-modal="true"
                aria-label="음성 입력"
            >
                <div className={styles.voiceMain}>
                    <span className={styles.micOuter} aria-hidden="true">
                        <span className={styles.micMid}>
                            <span className={styles.micCore}>
                                <Icon name="mic" />
                            </span>
                        </span>
                    </span>
                    <div className={styles.voiceText} aria-live="polite">
                        <h2 className={styles.voiceTitle}>
                            {rec ? '듣고 있어요' : '문장으로 바꾸는 중…'}
                        </h2>
                        <p className={styles.voiceDesc}>
                            {rec ? (
                                <>
                                    무슨 일인지 말씀하세요.
                                    <br />
                                    문장으로 바꿔 내용 칸에 넣어 드립니다.
                                </>
                            ) : (
                                '잠시만 기다려 주세요.'
                            )}
                        </p>
                    </div>
                    {rec && (
                        <div className={styles.timer}>
                            <div
                                className={styles.bar}
                                role="progressbar"
                                aria-label="녹음 시간"
                                aria-valuemin={0}
                                aria-valuemax={MAX_SEC}
                                aria-valuenow={elapsed}
                            >
                                <span style={{ width: `${(elapsed / MAX_SEC) * 100}%` }} />
                            </div>
                            <span>
                                {clock(elapsed)} / 최대 {clock(MAX_SEC)}
                            </span>
                        </div>
                    )}
                </div>
                <div className={styles.footer}>
                    <button
                        type="button"
                        className={styles.submit}
                        disabled={!rec}
                        autoFocus
                        onClick={() => setVoice('stt')}
                    >
                        말하기 끝
                    </button>
                    <button type="button" className={styles.textBtn} onClick={stopVoice}>
                        직접 쓰기
                    </button>
                </div>
            </div>
        );
    }

    // 말한 내용 확인: 데모 전사로 채운 직후(실서버는 VoiceReport가 원음·다시 녹음을 맡는다)
    const filled = !scope && !!stt && via === '음성';
    const sendLabel = !issue
        ? '보고 보내기'
        : kind === 'RESOLUTION'
          ? '해결 보고 보내기'
          : '추가 보고 보내기';
    const summary = issue
        ? ''
        : [template, linked?.title ?? '공통 보고', zone].filter(Boolean).join(' · ');

    const kinds = (
        <fieldset className={`${styles.kinds} ${filled || issue ? styles.compact : ''}`}>
            <legend className={styles.legend}>
                {issue ? '빠르게 채우기' : '무슨 일인가요?'}
                {(filled || issue) && <span className={styles.optional}>선택</span>}
            </legend>
            <div className={styles.kindGrid}>
                {QUICK.map((q) => (
                    <button
                        key={q.label}
                        type="button"
                        className={styles.kind}
                        aria-pressed={template === q.label}
                        onClick={() => {
                            setTemplate(q.label);
                            setText(q.text);
                            setVia('빠른 보고');
                            setAudioId(null);
                            setError(false);
                        }}
                    >
                        {q.label}
                    </button>
                ))}
            </div>
        </fieldset>
    );

    const target = !issue && (
        <section aria-label="보고 대상" className={styles.target}>
            <div className={styles.targetRow}>
                <span>
                    <span className={styles.small}>
                        {linked && doing?.id === linked.id
                            ? '관련 업무 · 지금 하는 업무'
                            : '관련 업무'}
                    </span>
                    <b>{linked?.title ?? '공통 보고 (업무 없음)'}</b>
                </span>
                <span className={styles.change} aria-hidden="true">
                    바꾸기
                </span>
                <select
                    id="rpTask"
                    aria-label="관련 업무"
                    className={styles.targetSelect}
                    value={task}
                    onChange={(e) => setTask(e.target.value)}
                >
                    <option value="">공통 보고 (업무 없음)</option>
                    {current.map((t) => (
                        <option key={t.id} value={t.id}>
                            {t.start}–{t.end} {t.title}
                        </option>
                    ))}
                </select>
            </div>
            <div className={styles.targetRow}>
                <span>
                    <span className={styles.small}>구역</span>
                    <b>{zone}</b>
                </span>
                <span className={styles.change} aria-hidden="true">
                    바꾸기
                </span>
                <select
                    id="rpZone"
                    aria-label="구역"
                    className={styles.targetSelect}
                    value={zone}
                    onChange={(e) => setZone(e.target.value)}
                >
                    {zones.map((z) => (
                        <option key={z}>{z}</option>
                    ))}
                </select>
            </div>
        </section>
    );

    const speak = scope ? (
        <VoiceReport
            session={session}
            scope={scope}
            onTranscript={(value) => {
                if (!value) return setAudioId(null);
                setText(value.text);
                setTemplate(null);
                setVia('음성');
                setAudioId(value.audioId);
                setError(false);
            }}
        />
    ) : (
        !filled && (
            <button type="button" className={styles.speak} onClick={startVoice}>
                <span className={styles.speakIcon}>
                    <Icon name="mic" />
                </span>
                <span>
                    <b>눌러서 말하기</b>
                    <span className={styles.small}>최대 30초 · 말한 내용이 아래 칸에 적힙니다</span>
                </span>
            </button>
        )
    );

    const body = (
        <div className={styles.field}>
            <label htmlFor="rpText" className={styles.label}>
                내용
            </label>
            <textarea
                id="rpText"
                className={styles.textarea}
                rows={4}
                maxLength={1000}
                value={text}
                placeholder={
                    issue
                        ? '예: 예비 마이크로 바꿨는데 아직 소리가 작습니다'
                        : '예: 메인 마이크 A 소리가 안 납니다'
                }
                aria-invalid={error || undefined}
                aria-describedby={error ? 'rpTextErr' : undefined}
                onChange={(e) => {
                    setText(e.target.value);
                    setError(false);
                }}
            />
            <span className={styles.small}>{Array.from(text).length}/1000자</span>
            {error && (
                <span id="rpTextErr" className={styles.error} role="alert">
                    내용을 1자 이상 입력하세요
                </span>
            )}
        </div>
    );

    const heard = filled && (
        <div className={styles.heard}>
            <div className={styles.heardHead}>
                <span className={styles.heardIcon}>
                    <Icon name="mic" />
                </span>
                <span>
                    <b>말한 내용을 넣었어요</b>
                    <span className={styles.small} aria-live="polite">
                        {playing ? '원음을 듣는 중…' : '틀린 곳만 고친 뒤 보내세요'}
                    </span>
                </span>
            </div>
            <div className={styles.heardActions}>
                <button
                    type="button"
                    className={styles.pill}
                    disabled={playing}
                    onClick={() => setPlaying(true)}
                >
                    원음 듣기
                </button>
                <button type="button" className={styles.pill} onClick={startVoice}>
                    다시 말하기
                </button>
            </div>
        </div>
    );

    return (
        <form
            className={`${styles.form} ${filled ? styles.filled : ''}`}
            onSubmit={(e) => {
                e.preventDefault();
                submit();
            }}
        >
            {!live && (
                <Note kind="warn">
                    운영 중인 행사가 아니라 보고를 보낼 수 없습니다. 입력한 내용은 지우지 않습니다.
                </Note>
            )}
            {issue && (
                <section aria-label="이어 쓰는 보고" className={styles.parent}>
                    <span className={styles.small}>이어서 알릴 보고</span>
                    <b>{issue.title}</b>
                    <span className={styles.meta}>
                        {issue.zone} · {issue.at} 보고 · {ISSUE_STATUS_LABEL[issue.status]}
                    </span>
                </section>
            )}
            {filled ? (
                <>
                    {body}
                    {heard}
                    {kinds}
                    {target}
                </>
            ) : issue ? (
                <>
                    {speak}
                    {body}
                    {kinds}
                </>
            ) : (
                <>
                    {kinds}
                    {target}
                    {speak}
                    {body}
                </>
            )}
            <div className={styles.footer}>
                <button type="submit" className={styles.submit} disabled={!live}>
                    {sendLabel}
                </button>
                {summary && <span className={styles.summary}>{summary}</span>}
            </div>
        </form>
    );
}

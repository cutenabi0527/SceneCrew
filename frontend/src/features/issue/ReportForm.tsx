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
import { NOW_MIN, toMin } from '../../domain/selectors';
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
import styles from './issue.module.css';

const QUICK = [
    { label: '진행 지연', text: '업무 진행이 지연되고 있습니다.', category: '지연' },
    { label: '인원 지원', text: '현장에 인원 지원이 필요합니다.', category: '인원 공백' },
    { label: '장비 문제', text: '장비가 정상 동작하지 않습니다.', category: '장비' },
    { label: '혼잡', text: '대기열이 길어져 혼잡합니다.', category: '혼잡' },
];

export type ReportFormProps = {
    taskId?: string;
    issueId?: string;
    kind?: 'FOLLOWUP' | 'RESOLUTION';
    onDone: () => void;
    onCancel: () => void;
    /** 열자마자 음성 입력 화면을 폼 위에 덮어 띄운다(WEB 상황 보고 팝업) */
    voiceFirst?: boolean;
};

/**
 * 상황 보고 입력. 같은 폼을 WEB은 모달 안에, APP은 전체 화면 페이지로 띄운다.
 * 빠른 보고 버튼은 내용만 채우고 바로 보내지 않는다(와이어프레임 규칙).
 */
export function ReportForm({
    taskId,
    issueId,
    kind,
    onDone,
    onCancel,
    voiceFirst = false,
}: ReportFormProps) {
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
    const [task, setTask] = useState(taskId ?? '');
    const linked = useTask(task || undefined);
    const [zone, setZone] = useState(issue?.zone ?? linked?.zone ?? zones[0] ?? '');
    const [text, setText] = useState('');
    const [template, setTemplate] = useState<string | null>(null);
    const [via, setVia] = useState<ReportVia>('텍스트');
    // 음성: 녹음 → 전사(060) → 내용 칸에 바로 채움(M06-VOICE). 전사는 항상 원음으로 한다(V9-D41).
    // 채운 뒤 내용 칸 아래에서 원음 듣기·재녹음을 한다. 전사 경로 표시와 재전사 버튼은 두지 않는다.
    // 채운 문장은 고칠 수 있고 "보고 보내기"를 눌러야 제출된다(자동 제출 없음).
    // 실서버(#373)는 데모 녹음 흉내 대신 실제 녹음·전사(060, VoiceReport)를 쓴다. 덮개 화면도 쓰지 않는다.
    const serverMode = !!useDemoState().live;
    const session = useIdentity();
    const { eventId } = useSession();
    const scope = serverMode && eventId ? session.scopes.participation(eventId) : null;
    const [audioId, setAudioId] = useState<string | null>(null);
    const demoVoiceFirst = voiceFirst && !serverMode;
    const [voice, setVoice] = useState<'idle' | 'rec' | 'stt'>(demoVoiceFirst ? 'rec' : 'idle');
    const [stt, setStt] = useState<TranscriptionResult | null>(null);
    const [playing, setPlaying] = useState(false);
    // 덮개 화면에서 시작한 녹음은 사용자가 "말하기 끝"을 누를 때까지 이어진다
    const [overlay, setOverlay] = useState(demoVoiceFirst);
    const [error, setError] = useState(false);

    useEffect(() => {
        if (voice === 'idle' || (overlay && voice === 'rec')) return;
        // 데모: 녹음 → 전사 과정을 흉내 낸다. 실제 STT 연동 시 교체.
        const h = window.setTimeout(
            () => {
                if (voice === 'rec') {
                    setVoice('stt');
                } else {
                    // 전사가 끝나면 확인 단계 없이 내용 칸에 바로 채운다
                    const result = demoTranscribe();
                    setStt(result);
                    setText(result.text);
                    setTemplate(null);
                    setVia('음성');
                    setError(false);
                    setVoice('idle');
                    setOverlay(false);
                }
            },
            voice === 'rec' ? 1500 : 800,
        );
        return () => window.clearTimeout(h);
    }, [voice, overlay]);

    /** 녹음·전사를 그만두고 직접 입력으로 돌아간다. 쓰던 내용은 그대로 둔다 */
    const stopVoice = () => {
        setVoice('idle');
        setOverlay(false);
        setPlaying(false);
    };

    // Android Back·이탈 보호(#260): 음성 덮개 화면은 Back이 먼저 닫고(입력 유지),
    // 녹음·전사 중이거나 보내지 않은 내용이 있으면 화면을 떠나기 전에 확인한다.
    useModalBackHandler(overlay, () => {
        setVoice('idle');
        setOverlay(false);
    });
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

    const title = issue
        ? `${issue.title} / ${kind === 'RESOLUTION' ? '해결 보고' : '추가 보고'}`
        : '상황 보고';

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

    return (
        <form
            className={`stack ${styles.form}`}
            onSubmit={(e) => {
                e.preventDefault();
                submit();
            }}
        >
            {overlay && (
                <div
                    className={styles.voiceCover}
                    role="dialog"
                    aria-modal="true"
                    aria-labelledby="voice-cover-title"
                >
                    <>
                        <span
                            className={`${styles.voiceMic} ${voice === 'rec' ? styles.listening : ''}`}
                        >
                            <Icon name="mic" />
                        </span>
                        <b id="voice-cover-title" className={styles.voiceTitle}>
                            음성 입력
                        </b>
                        <span className={styles.voiceState} aria-live="polite">
                            {voice === 'rec'
                                ? '말씀하세요. 문장으로 바꿔 드립니다 (최대 30초)'
                                : '문장으로 바꾸는 중…'}
                        </span>
                        <div className="row">
                            <button type="button" className={styles.voiceGhost} onClick={stopVoice}>
                                녹음 끄기
                            </button>
                            <button
                                type="button"
                                className={styles.voiceDone}
                                disabled={voice !== 'rec'}
                                autoFocus
                                onClick={() => setVoice('stt')}
                            >
                                말하기 끝
                            </button>
                        </div>
                    </>
                </div>
            )}
            <h2 className={`h3 ${styles.formTitle}`}>{title}</h2>
            {!live && (
                <Note kind="warn">
                    운영 중인 행사가 아니라 보고를 보낼 수 없습니다. 입력한 내용은 지우지 않습니다.
                </Note>
            )}
            {/* 빠른 보고: 제목 없이 버튼만. 누르면 내용만 채우고 바로 보내지 않는다 */}
            <div className={styles.quick} role="group" aria-label="빠른 보고">
                {QUICK.map((q) => (
                    <button
                        key={q.label}
                        type="button"
                        className={styles.quickBtn}
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
                        <small>{q.category}</small>
                    </button>
                ))}
            </div>
            {!issue && (
                <div className="row" style={{ alignItems: 'flex-start' }}>
                    <div className="field grow">
                        <label htmlFor="rpTask">관련 업무</label>
                        <select id="rpTask" value={task} onChange={(e) => setTask(e.target.value)}>
                            <option value="">공통 보고 (업무 없음)</option>
                            {current.map((t) => (
                                <option key={t.id} value={t.id}>
                                    {t.start}–{t.end} {t.title}
                                </option>
                            ))}
                        </select>
                    </div>
                    <div className="field grow">
                        <label htmlFor="rpZone">구역 (필수)</label>
                        <select id="rpZone" value={zone} onChange={(e) => setZone(e.target.value)}>
                            {zones.map((z) => (
                                <option key={z}>{z}</option>
                            ))}
                        </select>
                    </div>
                </div>
            )}
            <div className="field">
                <label htmlFor="rpText">내용</label>
                <textarea
                    id="rpText"
                    maxLength={1000}
                    value={text}
                    placeholder="예) 메인 마이크 소리가 나오지 않습니다"
                    onChange={(e) => {
                        setText(e.target.value);
                        setError(false);
                    }}
                />
                <span className="hint">
                    {Array.from(text).length}/1000자 / 버튼을 눌러도 바로 전송되지 않습니다
                </span>
                {error && <span className="ferr">내용을 1자 이상 입력하세요</span>}
            </div>
            {scope ? (
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
                <div className={styles.rec}>
                    {voice !== 'idle' ? (
                        <>
                            {voice === 'rec' && <span className={styles.dot} />}
                            <span className="grow">
                                {voice === 'rec' ? '녹음 중… (데모 시뮬레이션)' : '전사 중…'}
                            </span>
                            <button type="button" className="btn sm" onClick={stopVoice}>
                                녹음 끄기
                            </button>
                        </>
                    ) : stt && via === '음성' ? (
                        <>
                            <Icon name="mic" />
                            <span className="grow">
                                <b className="small">음성 전사</b>
                                <span className="xs muted" style={{ display: 'block' }}>
                                    {playing
                                        ? '원음을 듣는 중… (데모)'
                                        : '내용 칸에 채웠습니다. 고친 뒤 보내세요.'}
                                </span>
                            </span>
                            <button
                                type="button"
                                className="btn sm"
                                disabled={playing}
                                onClick={() => setPlaying(true)}
                            >
                                원음 듣기
                            </button>
                            <button
                                type="button"
                                className="btn sm"
                                onClick={() => {
                                    setOverlay(voiceFirst);
                                    setVoice('rec');
                                }}
                            >
                                다시 녹음
                            </button>
                        </>
                    ) : (
                        <>
                            <Icon name="mic" />
                            <span className="grow">
                                음성으로 말하면 문장으로 바꿔 드립니다 (최대 30초)
                            </span>
                            <button
                                type="button"
                                className="btn sm"
                                onClick={() => {
                                    // 덮개 화면으로 시작한 폼은 다시 누를 때도 같은 덮개 화면으로 연다
                                    setOverlay(voiceFirst);
                                    setVoice('rec');
                                }}
                            >
                                음성 입력
                            </button>
                        </>
                    )}
                </div>
            )}
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                <button type="button" className="btn ghost" onClick={() => guard.proceed(onCancel)}>
                    취소
                </button>
                <button type="submit" className="btn primary" disabled={!live}>
                    보고 보내기
                </button>
            </div>
        </form>
    );
}

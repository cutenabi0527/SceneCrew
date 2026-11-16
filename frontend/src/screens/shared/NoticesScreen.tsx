import { useEffect } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { useData } from '../../domain/hooks';
import { needsAck } from '../../domain/selectors';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Notice, NoticeKind } from '../../domain/types';
import { NoticeList } from '../../features/notice/NoticeList';
import { useShell } from '../../layouts/shell-actions';
import { useUrlState } from '../../shared/navigation/url-state';
import { Tabs } from '../../shared/ui/atoms';
import styles from './shared.module.css';

/** APP 알림(M04): 배정·변경·본인 보고 알림만 묶어서 보인다 */
type Filter = 'all' | 'assign' | 'change' | 'report';
const FILTERS: readonly Filter[] = ['all', 'assign', 'change', 'report'];
const APP_KINDS: Record<Filter, NoticeKind[]> = {
    all: ['assign', 'task', 'change', 'deploy', 'report'],
    assign: ['assign', 'task'],
    change: ['change', 'deploy'],
    report: ['report'],
};

/**
 * 알림함(공유 화면). 목록은 같고, 변경·투입 알림을 누를 때만 갈린다:
 * APP은 푸시와 같은 "변경 확인 카드" 화면으로, WEB은 그 자리에서 바로 확인 처리 후 업무 조정으로.
 */
export function NoticesScreen() {
    const dispatch = useDispatch();
    const shell = useShell();
    const navigate = useNavigate();
    // 고른 필터는 주소(?filter=)에 두어 새로고침해도 그대로 연다
    const [f, setF] = useUrlState<Filter>('filter', 'all', FILTERS);
    const app = shell.variant === 'app';
    const live = !!useDemoState().live;
    const open = (n: Notice) => {
        dispatch({ type: 'readNotice', id: n.id });
        // 실서버는 WEB도 변경 확인 화면에서 명시 확인한다. 알림을 여는 것만으로 확인하지 않는다(IA05, #373).
        if (live && n.ackId) return navigate(`/e/ack/${n.id}`);
        if (needsAck(n) && shell.variant === 'app') return navigate(`/e/ack/${n.id}`);
        if (needsAck(n)) dispatch({ type: 'ackNotice', id: n.id });
        if (n.issue) shell.openIssue(n.issue);
        else if (n.change) shell.openChange(n.change);
        else if (n.task) shell.openTask(n.task);
    };

    // 푸시를 눌러 들어왔다(?open=<알림 id>, #307). 서버에서 다시 읽은 목록에서 그 알림을 찾아 연다.
    // 뒤로 가기로 돌아와 다시 열리지 않게 이 주소를 바꿔치기한다. 찾지 못하면 알림함에 머문다.
    const [params] = useSearchParams();
    const target = live ? params.get('open') : null;
    const notices = useData().notices;
    useEffect(() => {
        if (!target) return;
        const n = notices.find((x) => x.id === target);
        // 공연을 연 뒤 들어오므로 목록은 이미 읽혀 있다. 없으면 주소에서 지워 나중에 갑자기 이동하지 않게 한다
        if (!n) return void navigate('/e/notices', { replace: true });
        dispatch({ type: 'readNotice', id: n.id });
        const path = n.ackId
            ? `/e/ack/${n.id}`
            : n.issue
              ? `/e/response/${n.issue}`
              : n.change
                ? `/e/changes/${n.change}`
                : n.task && app
                  ? `/e/tasks/${n.task}`
                  : '/e/notices';
        navigate(path, { replace: true });
    }, [target, notices, app, dispatch, navigate]);
    return (
        <div className={`stack ${shell.variant === 'web' ? styles.narrow : ''}`}>
            <div className="row between">
                <h1 className={shell.variant === 'web' ? 'h2' : 'h3'}>알림</h1>
                <button
                    type="button"
                    className="btn sm ghost"
                    onClick={() => dispatch({ type: 'readAllNotices' })}
                >
                    모두 읽음
                </button>
            </div>
            {app && (
                <Tabs<Filter>
                    label="알림 종류"
                    value={f}
                    onChange={setF}
                    items={[
                        ['all', '전체'],
                        ['assign', '배정'],
                        ['change', '변경'],
                        ['report', '보고'],
                    ]}
                />
            )}
            <div className="lgroup">
                <NoticeList onOpen={open} kinds={app ? APP_KINDS[f] : undefined} />
            </div>
        </div>
    );
}

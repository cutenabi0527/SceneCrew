import { Link } from 'react-router';
import { EntryFrame } from '../../screens/entry/EntryFrame';
import { inviteIntentUrl } from './open-in-app';

// Android 브라우저로 초대 링크(QR)를 열었을 때 먼저 보이는 앱 열기·설치 안내(#402).
// iPhone은 앱이 없어 이 안내 없이 휴대폰용 앱 화면의 웹으로 바로 참여한다.
// 초대 참여는 앱에서 하도록 이끈다. 웹으로 계속하는 길은 남겨 둔다.
export function InviteAppGate({
    token,
    group,
    onWeb,
}: {
    token: string;
    group: string | null;
    onWeb: () => void;
}) {
    return (
        <EntryFrame title="공연 초대" sub="초대 참여와 현장 업무는 SceneCrew 앱에서 진행합니다.">
            <div className="stack">
                <a
                    className="btn primary block"
                    href={inviteIntentUrl(window.location.origin, token, group)}
                >
                    SceneCrew 앱으로 열기
                </a>
                <p className="small muted">
                    앱이 설치돼 있으면 바로 이 초대가 앱에서 열립니다. 설치돼 있지 않으면 앱
                    내려받기 화면으로 이동합니다.
                </p>
                <Link className="btn block" to="/download">
                    Android 앱 내려받기
                </Link>
                <p className="small muted">
                    앱을 설치한 뒤에는 초대 QR을 다시 찍거나 받은 링크를 다시 열어 주세요.
                </p>
                <button type="button" className="btn ghost block" onClick={onWeb}>
                    웹에서 계속하기
                </button>
            </div>
        </EntryFrame>
    );
}

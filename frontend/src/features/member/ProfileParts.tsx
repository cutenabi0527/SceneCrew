import { useState } from 'react';
import { useData } from '../../domain/hooks';
import { groupName } from '../../domain/selectors';
import { Link } from 'react-router';
import { useDemoState, useDispatch } from '../../domain/store-context';
import type { Member } from '../../domain/types';

/** 내 정보 보기: 이름·역할·그룹·가용 시간·가능 업무·대기 장소. WEB 프로필 메뉴와 APP 설정 탭이 같이 쓴다. */
export function ProfileView({ member: m, onEdit }: { member: Member; onEdit: () => void }) {
    const d = useData();
    // 실서버(#373): 가용 시간·가능 업무·대기 장소와 이름 변경 승인은 서버 API가 없다(기획에 없음). 계정 이름은
    // 계정 화면에서 바꾸며 이미 참여한 공연의 이름은 바뀌지 않는다(BE02). 그래서 계정 화면으로만 안내한다.
    const live = !!useDemoState().live;
    return (
        <>
            <div className="row between nw">
                <b>내 정보</b>
                {live ? (
                    <Link className="btn sm" to="/account/profile">
                        계정/동의 관리
                    </Link>
                ) : (
                    <button type="button" className="btn sm" onClick={onEdit}>
                        수정
                    </button>
                )}
            </div>
            <dl className="kv">
                <dt>이름</dt>
                <dd>{m.name}</dd>
                <dt>역할</dt>
                <dd>{m.role === 'CHIEF' ? '총괄' : m.isReserve ? '예비 팀원' : '팀원'}</dd>
                {m.role !== 'CHIEF' && (
                    <>
                        <dt>그룹</dt>
                        <dd>{groupName(d, m.groupId)}</dd>
                    </>
                )}
                {live ? (
                    <>
                        <dt>가용</dt>
                        <dd>{m.available ? '투입 가능' : '지금 투입 불가'}</dd>
                    </>
                ) : (
                    <>
                        <dt>가용 시간</dt>
                        <dd>{m.avail}</dd>
                        <dt>가능 업무</dt>
                        <dd>{m.skills ?? '-'}</dd>
                        <dt>대기 장소</dt>
                        <dd>{m.wait ?? '-'}</dd>
                    </>
                )}
            </dl>
            {m.nameRequest && (
                <p className="note warn">
                    이름 변경 '{m.nameRequest}' / 총괄 확인을 기다리는 중입니다
                </p>
            )}
        </>
    );
}

/** 내 정보 수정: 팀원의 이름 변경은 총괄 확인 요청으로 보낸다. */
export function ProfileForm({ member: m, onDone }: { member: Member; onDone: () => void }) {
    const dispatch = useDispatch();
    const isChief = m.role === 'CHIEF';
    const [name, setName] = useState(m.nameRequest ?? m.name);
    const [avail, setAvail] = useState(m.avail);
    const [skills, setSkills] = useState(m.skills ?? '');
    const [wait, setWait] = useState(m.wait ?? '');
    const renamed = name.trim() !== '' && name.trim() !== m.name;
    return (
        <form
            className="stack"
            onSubmit={(e) => {
                e.preventDefault();
                dispatch({ type: 'updateProfile', profile: { name, avail, skills, wait } });
                onDone();
            }}
        >
            <b>내 정보 수정</b>
            <div className="field">
                <label htmlFor="pfName">이름</label>
                <input
                    id="pfName"
                    className="inp"
                    value={name}
                    required
                    onChange={(e) => setName(e.target.value)}
                />
                {!isChief && (
                    <span className="hint">
                        {renamed
                            ? '저장하면 총괄에게 확인을 요청합니다. 승인 전까지는 지금 이름이 보입니다.'
                            : '이름 변경은 총괄 확인 후 반영됩니다.'}
                    </span>
                )}
            </div>
            <div className="field">
                <label htmlFor="pfAvail">가용 시간</label>
                <input
                    id="pfAvail"
                    className="inp"
                    value={avail}
                    required
                    onChange={(e) => setAvail(e.target.value)}
                />
            </div>
            <div className="field">
                <label htmlFor="pfSkills">가능 업무</label>
                <input
                    id="pfSkills"
                    className="inp"
                    value={skills}
                    onChange={(e) => setSkills(e.target.value)}
                />
            </div>
            <div className="field">
                <label htmlFor="pfWait">대기 장소</label>
                <input
                    id="pfWait"
                    className="inp"
                    value={wait}
                    onChange={(e) => setWait(e.target.value)}
                />
            </div>
            <div className="row" style={{ justifyContent: 'flex-end' }}>
                <button type="button" className="btn sm ghost" onClick={onDone}>
                    취소
                </button>
                <button type="submit" className="btn sm primary">
                    {!isChief && renamed ? '저장 / 이름 확인 요청' : '저장'}
                </button>
            </div>
        </form>
    );
}

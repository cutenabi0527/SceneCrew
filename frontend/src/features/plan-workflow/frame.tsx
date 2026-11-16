import type { ComponentType, ReactNode } from 'react';
import report from './report.module.css';

// 계획·공연 관리 기능 화면의 바깥 틀. 기능 화면(/account/events/:id/…)은 EntryFrame으로,
// 디자인 화면의 W01-DRAFT(/e/plan/draft, #362)는 운영 화면 셸 안의 카드로 그린다.

export type FrameProps = {
    title: string;
    sub?: string;
    /** 제목 줄 오른쪽 끝의 작은 버튼(예: 편집 취소) */
    action?: ReactNode;
    /** 아래 고정 바에 둘 버튼·안내. ReportFrame만 따로 그리므로, 넘길 때는 그 틀인지 확인한다 */
    footer?: ReactNode;
    children: ReactNode;
};
export type Frame = ComponentType<FrameProps>;

/** 운영 화면 셸 안에 넣는 틀. 셸이 행사 이름·메뉴를 이미 보여 주므로 제목 카드만 둔다. */
export function ShellFrame({ title, sub, action, children }: FrameProps) {
    return (
        <section className="card stack" aria-label={title}>
            {action ? (
                <div className="row between nw">
                    <h2 className="h3">{title}</h2>
                    {action}
                </div>
            ) : (
                <h2 className="h3">{title}</h2>
            )}
            {sub && <p className="small muted">{sub}</p>}
            {children}
        </section>
    );
}

/**
 * 바깥 테두리 없이 제목만 두는 틀. 계획 입력·수정 화면처럼 안쪽 구획이 이미 카드로 나뉜 곳에 쓴다.
 * 상태(sub)는 제목 옆 둥근 표시로, action은 같은 줄 오른쪽 끝에 둔다.
 */
export function PlainFrame({ title, sub, action, children }: FrameProps) {
    return (
        <section className="stack" aria-label={title}>
            {/* 계획 수정 화면은 제목 줄을 보이지 않는다(탭 이름이 이미 화면을 알린다). 읽기 도구용으로만 남긴다 */}
            <h2 className="sr-only">{title}</h2>
            {(sub || action) && (
                <div className="row between nw">
                    {sub && <span className="chip">{sub}</span>}
                    {action}
                </div>
            )}
            {children}
        </section>
    );
}

/**
 * 보고서 형식 틀(W01-DRAFT의 공연 관리). 테두리 없이 두고(공연 이름·상태 줄은 보이지 않는다),
 * footer(버튼 바)는 맨 위에 고정하며 내용은 그 아래에서 위아래로 스크롤한다.
 * 부모 높이를 다 쓴다.
 * data-report·data-report-scroll은 바의 형광펜 도구(ReportTools)가 이 틀을 찾는 표시다.
 */
export function ReportFrame({ title, action, footer, children }: FrameProps) {
    return (
        <section className={report.report} aria-label={title} data-report="">
            {footer && <div className={report.topBar}>{footer}</div>}
            <div className={report.reportScroll} data-report-scroll="">
                {/* 공연 이름 제목·상태 줄은 화면에 두지 않는다(위 바의 제목과 행사 목록 토글이 이미 알린다).
                    읽기 도구용 제목만 남긴다 */}
                <h2 className="sr-only">{title}</h2>
                {action && <div className={report.reportSub}>{action}</div>}
                {children}
            </div>
        </section>
    );
}

import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { initialState } from '../../domain/seed';
import { StateContext } from '../../domain/store-context';
import { IssueHistory } from './IssueHistory';

describe('보고 원문 공개 범위', () => {
    it.each(['m1', 'm2', 'm5'])('%s 화면은 권한에 맞는 보고 원문만 렌더링한다', (me) => {
        const s = { ...initialState(), me, eventId: 'e1' };
        const issue = s.data.issues.find((i) => i.id === 'I6')!;
        issue.reports.push({
            kind: 'FOLLOWUP',
            by: 'm2',
            at: '18:26',
            via: '텍스트',
            text: '내 후속 보고',
        });
        s.data.log.push({
            date: '2026-11-14',
            at: '18:27',
            type: '변경',
            text: '총괄 처리 상세',
            ref: issue.id,
        });
        const html = renderToStaticMarkup(
            createElement(
                StateContext.Provider,
                { value: s },
                createElement(IssueHistory, { issue }),
            ),
        );
        if (me === 'm1' || me === 'm5') expect(html).toContain(issue.reports[0].text);
        else expect(html).not.toContain(issue.reports[0].text);
        if (me === 'm1' || me === 'm2') expect(html).toContain('내 후속 보고');
        else expect(html).not.toContain('내 후속 보고');
        if (me === 'm1') expect(html).toContain('총괄 처리 상세');
        else expect(html).not.toContain('총괄 처리 상세');
    });
});

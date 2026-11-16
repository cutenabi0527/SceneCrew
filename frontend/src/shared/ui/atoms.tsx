import type { ReactNode } from 'react';
import { ISSUE_STATUS_LABEL, TASK_STATUS_LABEL } from '../../domain/selectors';
import type { IssueStatus, Member, TaskStatus } from '../../domain/types';
import { Icon, type IconName } from './Icon';

export type ChipKind = '' | 'accent' | 'blue' | 'ok' | 'warn' | 'bad' | 'violet';

export function Chip({ kind = '', children }: { kind?: ChipKind; children: ReactNode }) {
    return <span className={`chip ${kind}`}>{children}</span>;
}

const TASK_CHIP: Record<TaskStatus, ChipKind> = {
    todo: 'blue',
    doing: 'accent',
    done: 'ok',
    canceled: '',
};
export function TaskStatusChip({ status }: { status: TaskStatus }) {
    return <Chip kind={TASK_CHIP[status]}>{TASK_STATUS_LABEL[status]}</Chip>;
}

const ISSUE_CHIP: Record<IssueStatus, ChipKind> = {
    RECEIVED: 'bad',
    RESPONDING: 'accent',
    RESOLUTION_REPORTED: 'blue',
    CLOSED: 'ok',
};
export function IssueStatusChip({ status }: { status: IssueStatus }) {
    return <Chip kind={ISSUE_CHIP[status]}>{ISSUE_STATUS_LABEL[status]}</Chip>;
}

export function Avatar({ member, me }: { member?: Member; me?: boolean }) {
    if (!member) return <span className="av">?</span>;
    const cls = me ? 'me' : member.isReserve ? 'standby' : '';
    return (
        <span className={`av ${cls}`} aria-hidden="true">
            {member.name.slice(0, 1)}
        </span>
    );
}

export function Note({
    kind = '',
    icon = 'info',
    children,
}: {
    kind?: '' | 'warn' | 'accent' | 'bad';
    icon?: IconName;
    children: ReactNode;
}) {
    return (
        <div className={`note ${kind}`}>
            <Icon name={icon} />
            <div className="grow">{children}</div>
        </div>
    );
}

export function Empty({ icon = 'info', children }: { icon?: IconName; children: ReactNode }) {
    return (
        <div className="empty">
            <Icon name={icon} />
            <div>{children}</div>
        </div>
    );
}

export function Segmented<T extends string>({
    value,
    items,
    onChange,
    label,
    disabled = false,
}: {
    value: T;
    items: [T, ReactNode][];
    onChange: (v: T) => void;
    label: string;
    disabled?: boolean;
}) {
    return (
        <div className="seg" role="group" aria-label={label}>
            {items.map(([k, l]) => (
                <button
                    key={k}
                    type="button"
                    aria-pressed={value === k}
                    disabled={disabled}
                    onClick={() => onChange(k)}
                >
                    {l}
                </button>
            ))}
        </div>
    );
}

export function Tabs<T extends string>({
    value,
    items,
    onChange,
    label,
}: {
    value: T;
    items: [T, ReactNode][];
    onChange: (v: T) => void;
    label: string;
}) {
    return (
        <div className="tabs" role="tablist" aria-label={label}>
            {items.map(([k, l]) => (
                <button
                    key={k}
                    type="button"
                    role="tab"
                    aria-selected={value === k}
                    onClick={() => onChange(k)}
                >
                    {l}
                </button>
            ))}
        </div>
    );
}

const PATHS = {
    org: (
        <>
            <rect x="9" y="3" width="6" height="5" rx="1" />
            <rect x="3" y="16" width="6" height="5" rx="1" />
            <rect x="15" y="16" width="6" height="5" rx="1" />
            <path d="M12 8v4M6 16v-4h12v4" />
        </>
    ),
    plan: (
        <>
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M3 9h18M3 14.5h18M9 4v16" />
        </>
    ),
    warn: (
        <>
            <path d="M12 3l9 16H3z" />
            <path d="M12 10v4M12 17h.01" />
        </>
    ),
    tasks: (
        <>
            <path d="M3 6h18M3 12h12M3 18h8" />
            <circle cx="18" cy="17" r="3" />
        </>
    ),
    task: (
        <>
            <rect x="4" y="3" width="16" height="18" rx="2" />
            <path d="M8 8h8M8 12h8M8 16h5" />
        </>
    ),
    people: (
        <>
            <circle cx="9" cy="8" r="3.5" />
            <path d="M2.5 20a6.5 6.5 0 0 1 13 0" />
            <path d="M19 8v6M16 11h6" />
        </>
    ),
    chat: <path d="M4 5h16v11H8l-4 4z" />,
    archive: (
        <>
            <rect x="3" y="4" width="18" height="5" rx="1" />
            <path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9M10 13h4" />
        </>
    ),
    bell: (
        <>
            <path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z" />
            <path d="M10 20a2 2 0 0 0 4 0" />
        </>
    ),
    lock: (
        <>
            <rect x="5" y="11" width="14" height="10" rx="2" />
            <path d="M8 11V7a4 4 0 0 1 8 0v4" />
        </>
    ),
    mic: (
        <>
            <rect x="9" y="3" width="6" height="11" rx="3" />
            <path d="M5 11a7 7 0 0 0 14 0M12 18v3" />
        </>
    ),
    download: (
        <>
            <path d="M12 3v12M7 10l5 5 5-5" />
            <path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2" />
        </>
    ),
    bolt: <path d="M13 2L4 14h7l-1 8 9-12h-7z" />,
    sparkle: (
        <>
            <path d="M10 3l1.9 5.1L17 10l-5.1 1.9L10 17l-1.9-5.1L3 10l5.1-1.9z" />
            <path d="M18 14l.9 2.1L21 17l-2.1.9L18 20l-.9-2.1L15 17l2.1-.9z" />
        </>
    ),
    back: <path d="M15 5l-7 7 7 7" />,
    chev: <path d="M9 5l7 7-7 7" />,
    check: <path d="M5 12l5 5 9-10" />,
    save: (
        <>
            <path d="M5 3h11l4 4v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z" />
            <path d="M8 3v5h7V3M7 21v-7h10v7" />
        </>
    ),
    plus: <path d="M12 5v14M5 12h14" />,
    tag: (
        <>
            <path d="M3 12V4h8l10 10-8 8z" />
            <circle cx="8" cy="9" r="1.5" />
        </>
    ),
    edit: (
        <>
            <path d="M4 20h4L19 9l-4-4L4 16z" />
            <path d="M13 7l4 4" />
        </>
    ),
    trash: (
        <>
            <path d="M4 7h16" />
            <path d="M9 7V4h6v3" />
            <path d="M6 7l1 13h10l1-13" />
        </>
    ),
    file: (
        <>
            <path d="M6 3h8l5 5v13H6z" />
            <path d="M14 3v5h5" />
        </>
    ),
    swap: (
        <>
            <path d="M4 8h14l-3-3M20 16H6l3 3" />
        </>
    ),
    info: (
        <>
            <circle cx="12" cy="12" r="9" />
            <path d="M12 11v5M12 8h.01" />
        </>
    ),
    send: <path d="M4 12l16-8-6 16-2-7z" />,
    close: <path d="M6 6l12 12M18 6L6 18" />,
    gear: (
        <>
            <circle cx="12" cy="12" r="3" />
            <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" />
        </>
    ),
    /** 설정: 톱니 모양 바퀴 + 가운데 원 */
    settings: (
        <>
            <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
            <circle cx="12" cy="12" r="3" />
        </>
    ),
    shield: <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />,
    pin: (
        <>
            <path d="M12 17v4" />
            <path d="M8 3h8l-1 6 3 4H6l3-4z" />
        </>
    ),
    calendar: (
        <>
            <rect x="3" y="5" width="18" height="16" rx="2" />
            <path d="M3 10h18M8 3v4M16 3v4" />
        </>
    ),
    /** 새로고침: 시계 방향으로 도는 화살표 */
    refresh: (
        <>
            <path d="M20 11a8 8 0 1 0-2.3 5.7" />
            <path d="M20 4v7h-7" />
        </>
    ),
    target: (
        <>
            <circle cx="12" cy="12" r="8" />
            <circle cx="12" cy="12" r="3" />
        </>
    ),
};

export type IconName = keyof typeof PATHS;

export function Icon({ name, className }: { name: IconName; className?: string }) {
    return (
        <svg
            className={className}
            viewBox="0 0 24 24"
            width="18"
            height="18"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
        >
            {PATHS[name]}
        </svg>
    );
}

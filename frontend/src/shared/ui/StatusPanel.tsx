import type { ReactNode } from 'react';
import styles from './StatusPanel.module.css';

type Props = {
    title: string;
    children?: ReactNode;
    action?: ReactNode;
    busy?: boolean;
    error?: boolean;
};

export function StatusPanel({ title, children, action, busy = false, error = false }: Props) {
    return (
        <section className={styles.panel} aria-busy={busy} role={error ? 'alert' : undefined}>
            <h1>{title}</h1>
            {children && <div className={styles.description}>{children}</div>}
            {action && <div className={styles.action}>{action}</div>}
        </section>
    );
}

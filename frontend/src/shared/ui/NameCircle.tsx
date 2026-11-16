import styles from './NameCircle.module.css';

/**
 * 이름(굵게)과 역할을 두 줄로 가운데에 담은 동그라미. 오른쪽 위 내 정보 버튼·내 정보 팝업·운영 구성도가 같이 쓴다.
 * 글은 화면에 따로 이름이 있으므로 보조기기에는 숨긴다.
 */
export function NameCircle({
    name,
    role,
    size = 'md',
    className = '',
}: {
    name: string;
    role: string;
    /** sm: 구성도 카드, md: 네비 바, lg: 내 정보 팝업 */
    size?: 'sm' | 'md' | 'lg';
    className?: string;
}) {
    return (
        <span className={`${styles.circle} ${styles[size]} ${className}`} aria-hidden="true">
            <b>{name}</b>
            <small>{role}</small>
        </span>
    );
}

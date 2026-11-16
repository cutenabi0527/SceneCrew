import { useMemo } from 'react';
import qrcode from 'qrcode-generator';

// 초대 링크 QR(#353). 링크에 초대 토큰이 들어 있어 외부 QR 서비스로 보내지 않고 브라우저에서 직접 그린다.
export function InviteQr({ url }: { url: string }) {
    const cells = useMemo(() => {
        const qr = qrcode(0, 'M');
        qr.addData(url, 'Byte');
        qr.make();
        const size = qr.getModuleCount();
        let path = '';
        for (let r = 0; r < size; r++)
            for (let c = 0; c < size; c++) if (qr.isDark(r, c)) path += `M${c} ${r}h1v1h-1z`;
        return { size, path };
    }, [url]);
    const box = cells.size + 8;
    return (
        <svg
            role="img"
            aria-label="초대 링크 QR 코드"
            viewBox={`-4 -4 ${box} ${box}`}
            width={200}
            height={200}
            shapeRendering="crispEdges"
            style={{ background: '#fff', maxWidth: '100%' }}
        >
            <path d={cells.path} fill="#000" />
        </svg>
    );
}

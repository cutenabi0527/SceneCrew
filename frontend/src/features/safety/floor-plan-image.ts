import type { FloorPlan } from '../../domain/types';
import { detectZones, type Rect } from './zone-detect';

const MAX_BYTES = 15 * 1024 * 1024;
/** 저장용 이미지 긴 변(px). 데모 저장소에 담기 위해 줄인다 */
const STORE_MAX = 1600;
/** 구역 인식용 이미지 긴 변(px). 작게 줄여 빠르게 찾는다 */
const DETECT_MAX = 240;

/**
 * 잘라낸 크기를 평면도 틀 비율로 넓힌 크기. 틀보다 가로로 길면 가로에 맞춰 위아래 여백을,
 * 틀보다 세로로 길면 세로에 맞춰 좌우 여백을 늘린다. 사진 자체는 늘리지 않는다.
 */
export function paddedSize(w: number, h: number, frameRatio: number) {
    return w / h > frameRatio ? { w, h: w / frameRatio } : { w: h * frameRatio, h };
}

function canvasOf(bitmap: ImageBitmap, max: number) {
    const scale = Math.min(1, max / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
    // 투명한 PNG는 흰 바탕 위에 그린다
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return { canvas, ctx };
}

/**
 * PNG·JPG 평면도를 읽어 저장용 이미지(줄인 JPEG)와 자동 인식한 구역 상자를 돌려준다.
 * 원본 파일은 보관하지 않는다. content(이미지 % 좌표)를 주면 그 안의 사진에서만 구역을 찾아
 * 자르기로 생긴 흰 여백이 구역으로 잡히지 않게 한다.
 */
export async function readFloorPlan(
    file: File,
    content: Rect = [0, 0, 100, 100],
): Promise<{ plan: FloorPlan; boxes: Rect[] }> {
    const okType = /^image\/(png|jpeg)$/.test(file.type) || /\.(png|jpe?g)$/i.test(file.name);
    if (!okType) throw new Error('PNG/JPG 이미지만 올릴 수 있습니다.');
    if (file.size > MAX_BYTES) throw new Error('이미지가 너무 큽니다(15MB 이내).');
    let bitmap: ImageBitmap;
    try {
        bitmap = await createImageBitmap(file);
    } catch {
        throw new Error('이미지를 읽지 못했습니다. 다른 파일을 올려주세요.');
    }
    try {
        const store = canvasOf(bitmap, STORE_MAX);
        const detect = canvasOf(bitmap, DETECT_MAX);
        const { width: dw, height: dh } = detect.canvas;
        const px = Math.round((content[0] / 100) * dw);
        const py = Math.round((content[1] / 100) * dh);
        const pw = Math.max(1, Math.round((content[2] / 100) * dw));
        const ph = Math.max(1, Math.round((content[3] / 100) * dh));
        // 찾은 상자는 사진 안 % 좌표이므로 전체 이미지 % 좌표로 되돌린다
        const r1 = (v: number) => Math.round(v * 10) / 10;
        const boxes = detectZones(detect.ctx.getImageData(px, py, pw, ph)).map(
            ([x, y, w, h]): Rect => [
                r1(((px + (x / 100) * pw) / dw) * 100),
                r1(((py + (y / 100) * ph) / dh) * 100),
                r1(((w / 100) * pw * 100) / dw),
                r1(((h / 100) * ph * 100) / dh),
            ],
        );
        return {
            plan: {
                src: store.canvas.toDataURL('image/jpeg', 0.85),
                width: store.canvas.width,
                height: store.canvas.height,
                name: file.name,
            },
            boxes,
        };
    } finally {
        bitmap.close();
    }
}

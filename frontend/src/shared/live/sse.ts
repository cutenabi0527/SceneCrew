// text/event-stream 본문을 이벤트 단위로 나눈다(WHATWG SSE 해석 규칙의 필요한 부분).
// event·data·id만 쓰고 retry와 주석(:)은 무시한다. 줄 끝은 \n·\r\n·\r 모두 받는다.

export interface SseEvent {
    event: string;
    data: string;
    id: string | null;
}

export class SseParser {
    private buffer = '';
    private event = '';
    private data: string[] = [];
    private id: string | null = null;

    /** 받은 텍스트 조각을 넣고, 완성된 이벤트를 돌려준다. */
    push(chunk: string): SseEvent[] {
        this.buffer += chunk;
        const out: SseEvent[] = [];
        for (;;) {
            const match = /\r\n|\r|\n/.exec(this.buffer);
            if (!match) break;
            // \r이 조각 끝에 오면 다음 조각의 \n과 합쳐질 수 있으니 기다린다
            if (match[0] === '\r' && match.index === this.buffer.length - 1) break;
            const line = this.buffer.slice(0, match.index);
            this.buffer = this.buffer.slice(match.index + match[0].length);
            const done = this.line(line);
            if (done) out.push(done);
        }
        return out;
    }

    private line(line: string): SseEvent | null {
        if (line === '') {
            if (!this.data.length) {
                this.event = '';
                return null;
            }
            const event = {
                event: this.event || 'message',
                data: this.data.join('\n'),
                id: this.id,
            };
            this.event = '';
            this.data = [];
            return event;
        }
        if (line.startsWith(':')) return null;
        const colon = line.indexOf(':');
        const field = colon < 0 ? line : line.slice(0, colon);
        let value = colon < 0 ? '' : line.slice(colon + 1);
        if (value.startsWith(' ')) value = value.slice(1);
        if (field === 'event') this.event = value;
        else if (field === 'data') this.data.push(value);
        else if (field === 'id' && !value.includes('\0')) this.id = value;
        return null;
    }
}

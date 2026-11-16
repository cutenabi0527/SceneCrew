import { parseCrop } from './layout';
import type { BackgroundFile } from './useBackground';
import { classifyFailure } from '../../shared/api/failure';
import { ScopeDisposedError, type ParticipationScope } from '../../shared/scope/scope';
import type { IdentitySession } from '../identity/session';
import { sameKeyResult } from '../response/client';
import {
    parseReceipt,
    parseUnconfirmedDraft,
    type EquipmentDraft,
    type UnconfirmedDraft,
    type VenueDraft,
} from './contract';

// Q3-A 공유 미확정 초안 조회·저장. PUT은 전체 교체 + 기대 revision이며 계약대로 Idempotency-Key를 보낸다(#355).
// 응답을 잃으면 같은 내용의 다음 저장에 같은 키를 다시 써서 두 번 저장되지 않게 한다. 충돌(409)이면 현재 초안을 다시 읽는다.

export class DraftDesk {
    /** 결과를 모르는 저장 의도(본문과 키). 같은 본문을 다시 저장하면 같은 키로 보낸다. */
    private pending: { id: string; body: string } | null = null;

    constructor(
        readonly session: IdentitySession,
        readonly scope: ParticipationScope,
    ) {}

    private path() {
        return `/events/${this.scope.eventId}/unconfirmed-draft`;
    }

    /** V9-D62: 입력 PDF 한 쪽을 그린 PNG(평면도 배경). 작성 중에만 받을 수 있고 만료·삭제 뒤에는 404다. */
    async pageImage(
        documentId: string,
        page: number,
        signal?: AbortSignal,
    ): Promise<BackgroundFile> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const result = await this.session.api.getBinary(
            this.scope,
            `/intake-documents/${documentId}/pages/${page}/image`,
            'image/png',
            { signal },
        );
        return { blob: result.blob, crop: parseCrop(result.headers.get('X-Floor-Plan-Crop')) };
    }

    /** 현재 초안. 아직 저장한 적이 없으면(404) null이다. */
    async read(signal?: AbortSignal): Promise<UnconfirmedDraft | null> {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        try {
            return await this.session.api.get(
                this.scope,
                this.path(),
                (d) => parseUnconfirmedDraft(d, this.scope.eventId),
                { signal },
            );
        } catch (error) {
            const f = classifyFailure(error);
            if (f.status === 404 && f.code === 'NOT_FOUND') return null;
            throw error;
        }
    }

    async save(input: {
        expectedRevision: string;
        equipmentDraft: EquipmentDraft;
        venueDraft: VenueDraft;
        sourceJobId: string | null;
    }) {
        if (!this.scope.isCurrent()) throw new ScopeDisposedError();
        const operation = 'unconfirmed-draft:save';
        const body = JSON.stringify(input);
        // 본문이 바뀌면 남은 의도는 버린다(새 내용을 옛 키로 보내지 않는다)
        if (this.pending && this.pending.body !== body) {
            this.session.scopes.intentKeys.release(this.scope, operation, this.pending.id);
            this.pending = null;
        }
        const intentId = this.pending?.id ?? crypto.randomUUID();
        const outcome = await this.session.api.command(
            this.scope,
            {
                operation,
                intentId,
                method: 'PUT',
                path: this.path(),
                body: input,
                recovery: 'same-key',
                response: 'data',
            },
            parseReceipt,
        );
        const { result, keep } = sameKeyResult(outcome);
        // 만료(24시간)·상태 확인 전환은 같은 키로 다시 보낼 수 없다. 현재 초안을 다시 읽어 확인한 뒤 새로 저장한다.
        if (keep && outcome.status !== 'expired' && outcome.status !== 'reconcile')
            this.pending = { id: intentId, body };
        else {
            this.pending = null;
            this.session.scopes.intentKeys.release(this.scope, operation, intentId);
        }
        return result;
    }
}

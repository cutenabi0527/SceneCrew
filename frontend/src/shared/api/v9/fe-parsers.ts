// FE가 각 v9 연산의 2xx 응답을 읽을 때 쓰는 검사기 목록. 계약 시험(contract.test.ts)과 손 검사기 비교 시험이 쓴다.
// 화면 코드는 각 기능의 client에서 같은 함수를 직접 부른다. 새 연산을 연결하면 여기에 함께 더한다.
import { parseSummary } from '../../../features/event-close/client';
import * as eqc from '../../../features/equipment/commands';
import * as eq from '../../../features/equipment/live-contract';
import * as identity from '../../../features/identity/contract';
import * as chat from '../../../features/live-chat/contract';
import * as members from '../../../features/members/contract';
import * as notices from '../../../features/notifications/contract';
import { parsePlanJob } from '../../../features/plan-job/contract';
import { parse as plan } from '../../../features/plan-workflow/contract';
import * as resp from '../../../features/response/contract';
import * as cameraLink from '../../../features/camera-link/contract';
import * as equipmentLink from '../../../features/equipment-link/contract';
import * as safety from '../../../features/safety-map/contract';
import { parseTranscribeJob } from '../../../features/voice/client';
import * as draft from '../../../features/unconfirmed-draft/contract';

type Value = Record<string, unknown> & { [key: string]: never | unknown };
const eventOf = (v: Value) =>
    String(
        v.eventId ??
            (v.issue as Value | undefined)?.eventId ??
            ((v.items as Value[] | undefined)?.[0]?.eventId as string | undefined) ??
            v.id ??
            '',
    );

/** 손으로 짠 검사기(스키마를 옮기지 않은 것). 손 검사기 비교 시험의 대상이다. */
export const HAND_WRITTEN = new Set([
    'GET /me',
    'PATCH /me',
    'PATCH /me/consents',
    'GET /events',
    'GET /invites/{token}/options',
    'POST /invites/{token}/join',
    'PATCH /members/{m}/availability',
    'POST /events/{e}/leave',
    'POST /auth/login',
    'POST /auth/refresh',
    'POST /events/{e}/summary',
    'GET /ai-jobs/{j}#GENERATE_PLAN',
    'GET /events/{e}/equipment',
    'GET /equipment/{a}',
    'GET /events/{e}/equipment-reservations',
]);

/** 연산별 FE 검사기. 키가 'GET /ai-jobs/{j}#종류'면 Job 종류별 검사기다. */
export const FE_PARSERS: Record<string, (v: Value) => unknown> = {
    'GET /me': identity.account,
    'PATCH /me': identity.account,
    'PATCH /me/consents': identity.consents,
    'GET /events': identity.eventPage,
    'GET /events/{e}': (v) => {
        identity.event(v, 'Event');
        return plan('Event', v);
    },
    'PATCH /events/{e}': (v) => plan('Event', v),
    'PATCH /events/{e}/input': (v) => plan('Event', v),
    'POST /events/{e}/start': (v) => plan('Event', v),
    'POST /events/{e}/end': (v) => plan('Event', v),
    'GET /invites/{token}/options': identity.inviteOptions,
    'POST /invites/{token}/join': identity.joinResult,
    'PATCH /members/{m}/availability': (v) =>
        identity.participationChanged(v, 'ParticipationChanged'),
    'POST /events/{e}/leave': (v) => identity.participationChanged(v, 'DepartureReceipt'),
    'POST /auth/login': (v) => {
        identity.account(v.account);
        return identity.credentials(v, v.channel === 'ANDROID' ? 'ANDROID' : 'WEB', 'LoginResult');
    },
    'POST /auth/refresh': (v) =>
        identity.credentials(v, v.channel === 'ANDROID' ? 'ANDROID' : 'WEB', 'AuthRefreshResult'),
    'GET /events/{e}/plan': (v) => plan('PlanView', v),
    'PUT /events/{e}/draft-plan': (v) => plan('DraftPlanSaved', v),
    'GET /events/{e}/tasks': (v) => plan('TaskPage', v),
    'GET /tasks/{t}': (v) => plan('V9Task', v),
    'POST /tasks/{t}/start': (v) => plan('Task', v.task),
    'POST /tasks/{t}/complete': (v) => plan('Task', v.task),
    'PATCH /tasks/{t}/checklist': (v) => plan('Task', v.task),
    'POST /events/{e}/documents': (v) => plan('UploadResult', v),
    'POST /events/{e}/summary': parseSummary,
    'GET /ai-jobs/{j}#EXTRACT_EVENT_INPUT': (v) => plan('PublicJobExtractEventInput', v),
    'GET /ai-jobs/{j}#GENERATE_PLAN': (v) => parsePlanJob(v, String(v.jobId)),
    'GET /ai-jobs/{j}#ANALYZE_REPORT': (v) => resp.parseAnalysisJob(v, String(v.jobId)),
    'GET /ai-jobs/{j}#GENERATE_CHANGE': (v) => resp.parseChangeJob(v, String(v.jobId)),
    'GET /ai-jobs/{j}#TRANSCRIBE': (v) => parseTranscribeJob(v, String(v.jobId)),
    'GET /ai-jobs/{j}#SIMULATE_RECOVERY': (v) => resp.parseRecoveryJob(v, String(v.jobId)),
    'GET /events/{e}/issues': (v) => resp.parseIssuePage(v, eventOf(v)),
    'POST /events/{e}/issues': (v) => resp.parseIssueCreated(v, eventOf(v)),
    'GET /issues/{i}': (v) => resp.parseIssue(v, eventOf(v)),
    'PATCH /issues/{i}': (v) => resp.parseIssue(v, eventOf(v)),
    'POST /issues/{i}/reports': (v) =>
        resp.parseReportCreated(v, String((v.report as Value).issueId)),
    'GET /events/{e}/change-sets': (v) => resp.parseChangeSetPage(v, eventOf(v)),
    'POST /events/{e}/change-sets': (v) => resp.parseChangeSet(v, eventOf(v)),
    'GET /change-sets/{c}': (v) => resp.parseChangeSet(v, eventOf(v)),
    'POST /change-sets/{c}/preview': (v) => resp.parseChangeSet(v, eventOf(v)),
    'POST /change-sets/{c}/apply': (v) => resp.parseChangeSet(v, eventOf(v)),
    'POST /change-sets/{c}/discard': (v) => resp.parseChangeSet(v, eventOf(v)),
    'PATCH /change-sets/{c}': (v) => resp.parseChangeSet(v, eventOf(v)),
    'GET /change-sets/{c}/evidence/{revision}': (v) =>
        resp.parseEvidenceSnapshot(v, String(v.changeSetId), String(v.changeSetRevision)),
    'GET /events/{e}/my-changes': resp.parseAckPage,
    'POST /change-acknowledgments/{a}/ack': (v) => resp.parseAck(v, String(v.id)),
    'GET /recovery-simulations/{s}': (v) =>
        resp.parseSimulation(v, String(v.eventId), String(v.id)),
    'GET /issues/{i}/recovery-simulations/latest': resp.parsePointer,
    'GET /events/{e}/equipment': eq.parseAssetPage,
    'GET /equipment/{a}': eq.parseDetail,
    'GET /events/{e}/equipment-reservations': eq.parseReservationPage,
    'GET /events/{e}/invite': (v) => members.parseInvite(v, String(v.eventId)),
    'GET /events/{e}/work-groups': members.parseGroupPage,
    'GET /events/{e}/members': members.parseMemberPage,
    'PATCH /members/{m}': (v) =>
        members.parseMemberChanged(v, String((v.member as Value).memberId)),
    'POST /events/{e}/chief-transfer': (v) => {
        const prev = v.previousChief as Value;
        return members.parseChiefTransfer(v, {
            previousId: String(prev.memberId),
            successorId: String((v.currentChief as Value).memberId),
            groupId: String(prev.workGroupId),
        });
    },
    'GET /events/{e}/unconfirmed-draft': (v) => draft.parseUnconfirmedDraft(v, String(v.eventId)),
    'PUT /events/{e}/unconfirmed-draft': draft.parseReceipt,
    'GET /me/notifications': notices.parseNotificationPage,
    'POST /notifications/{n}/read': (v) => notices.parseNotification(v, String(v.id)),
    'GET /events/{e}/channels': chat.parseChannelPage,
    'GET /events/{e}/messages': chat.parseMessagePage,
    'PATCH /events/{e}/safety-map': (v) => plan('FinalReceipt', v),
    'POST /events/{e}/safety-cameras': (v) => plan('FinalReceipt', v),
    'PATCH /safety-cameras/{c}': (v) => plan('FinalReceipt', v),
    'POST /events/{e}/equipment': eqc.parseReceipt,
    'POST /equipment/{a}/observations': eqc.parseReceipt,
    'GET /events/{e}/equipment-context': (v) => eqc.parseContext(v, eventOf(v)),
    'POST /events/{e}/equipment-connections': eqc.parseReceipt,
    'PATCH /events/{e}/equipment-connections/{r}': eqc.parseReceipt,
    'DELETE /events/{e}/equipment-connections/{r}': (v) => eqc.parseRelease(v, String(v.id)),
    'POST /events/{e}/equipment-requirements': eqc.parseReceipt,
    'PATCH /events/{e}/equipment-requirements/{r}': eqc.parseReceipt,
    'DELETE /events/{e}/equipment-requirements/{r}': (v) => eqc.parseRelease(v, String(v.id)),
    'POST /events/{e}/equipment-dependencies': eqc.parseReceipt,
    'PATCH /events/{e}/equipment-dependencies/{r}': eqc.parseReceipt,
    'DELETE /events/{e}/equipment-dependencies/{r}': (v) => eqc.parseRelease(v, String(v.id)),
    'PUT /me/devices/{deviceId}': (v) => plan('DeviceRegisteredDraft', v),
    'GET /events/{e}/safety-map': (v) => safety.parseSafetyMap(v, eventOf(v)),
    'GET /events/{e}/safety-observations': (v) => safety.parseObservationPage(v, eventOf(v)),
    'GET /safety-observations/{o}': (v) => safety.parseObservation(v, eventOf(v), String(v.id)),
    'GET /events/{e}/safety-support-candidates': safety.parseSupportCandidates,
    'GET /events/{e}/safety-cameras': (v) => safety.parseCameraPage(v, eventOf(v)),
    'GET /safety-cameras/{c}': (v) => safety.parseCamera(v, eventOf(v), String(v.id)),
    // 카메라 웹 연결(#393 W1~W4, #396)
    'GET /events/{e}/my-links': cameraLink.parseMyLinks,
    'POST /safety-cameras/{c}/browser-link': cameraLink.parseCameraLink,
    'DELETE /safety-cameras/{c}/browser-link': cameraLink.parseCameraUnlink,
    'POST /events/{e}/equipment-links/codes': equipmentLink.parseLinkCode,
    'PUT /equipment/{a}/link': equipmentLink.parseLinkReceipt,
    'DELETE /equipment/{a}/link': equipmentLink.parseLinkReceipt,
    'POST /safety-cameras/{c}/frames': (v) => cameraLink.parseFrameAck(v),
    'POST /tasks/{t}/equipment-checks': (v) => plan('FinalReceipt', v),
};

/**
 * 연결했지만 위 목록에 없는 연산과 이유. 202 접수·로그인 전 인증 응답은 공통 코드가 따로 검사한다.
 * 이 목록에도 없으면 계약 시험이 실패한다(새 연산의 검사기 누락 방지).
 */
export const HANDLED_ELSEWHERE: Record<string, string> = {
    'POST /auth/register': '로그인 전 202 접수(shared/api/auth-receipts.ts)',
    'POST /auth/verify-email':
        '이메일 확인 뒤 로그인 결과(identity/session.ts의 로그인 처리와 같다)',
    'POST /auth/resend-verification': '로그인 전 202 접수(shared/api/auth-receipts.ts)',
    'POST /auth/logout': '응답 본문 없음(204)',
    'POST /auth/password-reset/request': '로그인 전 202 접수(shared/api/auth-receipts.ts)',
    'POST /auth/password-reset/confirm': '응답 본문 없음(204)',
    'POST /events/{e}/plan-generations': 'Job 202 접수(shared/api/api.ts interpret)',
    'POST /events/{e}/extractions': 'Job 202 접수(shared/api/api.ts interpret)',
    'POST /reports/{r}/analyze': 'Job 202 접수(shared/api/api.ts interpret)',
    'POST /events/{e}/change-generations': 'Job 202 접수(shared/api/api.ts interpret)',
    'POST /events/{e}/recovery-simulations': 'Job 202 접수(shared/api/api.ts interpret)',
    'GET /ai-jobs/{j}': 'Job 종류별 검사기(GET /ai-jobs/{j}#종류)',
    'POST /events/{e}/transcriptions':
        'Job 202 접수와 TranscriptionAccepted(features/voice/client.ts transcribe)',
    'GET /events/{e}/stream':
        'SSE 무효화 알림(shared/live/invalidation.ts parseInvalidation, 스키마 SseInvalidation)',
    'GET /safety-cameras/{c}/snapshot':
        'JPEG 바이너리와 헤더(safety-map/client.ts snapshot, shared/api/client.ts sendBinary)',
};

/** 스키마 이름으로 FE 검사기를 찾는다(연산 없는 계약 fixture용). */
export const FE_PARSERS_BY_SCHEMA: Record<string, (v: Value) => unknown> = {
    V9ChangeSetView: (v) => resp.parseChangeSet(v, eventOf(v)),
    FinalEvidenceSnapshot: (v) =>
        resp.parseEvidenceSnapshot(v, String(v.changeSetId), String(v.changeSetRevision)),
    FinalRecoveryPointer: resp.parsePointer,
    V9EquipmentAssetPage: eq.parseAssetPage,
    V9EquipmentDetail: eq.parseDetail,
    V9EquipmentReservationPage: eq.parseReservationPage,
};

/** 응답 값의 Job 종류까지 고려해 검사기를 고른다. */
export function feParserFor(op: string, value: unknown) {
    if (op === 'GET /ai-jobs/{j}') {
        const kind = (value as { kind?: unknown } | null)?.kind;
        return FE_PARSERS[`${op}#${String(kind)}`];
    }
    return FE_PARSERS[op];
}

import { fromLocalInput, toLocalInput } from '../event-admin/time';
import type { EventData, EventInfo, PlanEdit } from '../../domain/types';
import type { Draft } from './contract';
import { refId } from './contract';

// 데모 계획 ↔ 계획 편집기 초안. 데모 계획은 시각이 'HH:MM'이고 구역이 이름 목록이라, 실서버 편집기
// (DraftEditor)를 그대로 쓰도록 공연 날짜를 붙인 시각과 구역 id로 바꿨다가 저장할 때 되돌린다.

const at = (date: string, time: string) => (time ? (fromLocalInput(`${date}T${time}`) ?? '') : '');

function split(iso: string, fallbackDate: string) {
    const local = toLocalInput(iso);
    return local
        ? { date: local.slice(0, 10), time: local.slice(11, 16) }
        : { date: fallbackDate, time: '' };
}

/** 데모 공연의 지금 계획을 편집기 초안으로 바꾼다. 복사 배정된 업무는 원 업무 하나로 본다. */
export function demoDraft(ev: EventInfo, d: EventData, eventDate: string): Draft {
    return {
        workGroups: d.groups.map((g) => ({ id: g.id, name: g.name, instructions: g.guide })),
        zones: ev.zones.map((z) => ({ id: z, name: z, description: null })),
        programs: ev.programs.map((p) => ({
            id: p.id,
            name: p.name,
            zoneId: null,
            startAt: at(eventDate, p.start),
            endAt: at(eventDate, p.end),
        })),
        tasks: d.tasks
            .filter((t) => !t.copyOf && t.status !== 'canceled')
            .map((t) => ({
                id: t.id,
                workGroupId: t.groupId,
                bundleKey: t.bundle,
                zoneId: t.zone,
                programId: t.program ?? null,
                title: t.title,
                startAt: at(t.date ?? eventDate, t.start),
                endAt: at(t.date ?? eventDate, t.end),
                steps: t.steps.length ? [...t.steps] : [''],
                checklist: t.checklist.map((c, i) => ({
                    key: `${t.id}-${i}`,
                    text: c.text,
                    required: c.required !== false,
                })),
            })),
        instructions: ev.notice ? [{ scope: 'EVENT', workGroupId: null, body: ev.notice }] : [],
    };
}

/** 편집한 초안을 데모 계획으로 되돌린다. 새 항목의 임시 id는 그대로 데모 id로 쓴다. */
export function planFromDraft(draft: Draft, eventDate: string): PlanEdit {
    const zoneName = new Map(draft.zones.map((z) => [refId(z), z.name]));
    return {
        groups: draft.workGroups.map((g) => ({
            id: refId(g),
            name: g.name,
            guide: g.instructions,
        })),
        zones: draft.zones.map((z) => z.name),
        programs: draft.programs.map((p) => ({
            id: refId(p),
            name: p.name,
            start: split(p.startAt, eventDate).time,
            end: split(p.endAt, eventDate).time,
        })),
        tasks: draft.tasks.map((t) => {
            const start = split(t.startAt, eventDate);
            return {
                id: refId(t),
                title: t.title,
                groupId: t.workGroupId,
                zone: zoneName.get(t.zoneId) ?? t.zoneId,
                program: t.programId ?? undefined,
                bundle: t.bundleKey,
                date: start.date !== eventDate ? start.date : undefined,
                start: start.time,
                end: split(t.endAt, eventDate).time,
                steps: t.steps,
                checklist: t.checklist.map((c) => ({ text: c.text, required: c.required })),
            };
        }),
        notice: draft.instructions
            .map((i) => i.body.trim())
            .filter(Boolean)
            .join('\n'),
    };
}

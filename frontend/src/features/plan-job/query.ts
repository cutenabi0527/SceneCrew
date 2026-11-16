import type { createApi } from '../../shared/api/api';
import { asUuid } from '../../shared/api/contract';
import { scopedKey, type ParticipationScope } from '../../shared/scope/scope';
import { parsePlanJob } from './contract';

export function planJobQuery(
    api: Pick<ReturnType<typeof createApi>, 'get'>,
    scope: ParticipationScope,
    jobId: string,
) {
    const id = asUuid(jobId, 'jobId');
    return {
        queryKey: scopedKey(scope, 'plan-job', id),
        queryFn: ({ signal }: { signal: AbortSignal }) =>
            api.get(scope, `/ai-jobs/${id}`, (data) => parsePlanJob(data, id), { signal }),
        retry: false as const,
        staleTime: 0,
    };
}

import { lazy, Suspense } from 'react';

const Workspace = lazy(() =>
    import('./Workspace').then((module) => ({ default: module.PlanWorkspace })),
);
const Tasks = lazy(() => import('./Tasks').then((module) => ({ default: module.WorkTasks })));

export function PlanWorkflowRoute() {
    return (
        <Suspense fallback={<p role="status">계획 화면을 열고 있습니다.</p>}>
            <Workspace />
        </Suspense>
    );
}
export function TasksWorkflowRoute() {
    return (
        <Suspense fallback={<p role="status">업무 화면을 열고 있습니다.</p>}>
            <Tasks />
        </Suspense>
    );
}

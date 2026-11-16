import { Component, type ReactNode } from 'react';
import { StatusPanel } from '../shared/ui/StatusPanel';

export class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
    state = { failed: false };

    static getDerivedStateFromError() {
        return { failed: true };
    }

    render() {
        if (this.state.failed) {
            return (
                <main className="error-root">
                    <StatusPanel
                        error
                        title="화면을 불러오지 못했어요"
                        action={
                            <button onClick={() => window.location.reload()}>다시 불러오기</button>
                        }
                    >
                        잠시 후 다시 시도해주세요.
                    </StatusPanel>
                </main>
            );
        }
        return this.props.children;
    }
}

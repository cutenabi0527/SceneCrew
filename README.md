# 씬크루 (SceneCrew)

씬크루는 콘서트 총괄과 운영진이 공연 준비, 업무 배정, 장비·안전 상황, 현장 보고를 함께 관리하는 서비스입니다. SSAFY 15기 자율 프로젝트에서 팀으로 개발했습니다. 이 스냅샷의 개발 기록 범위는 2026-10-13부터 2026-11-13까지입니다.

**SSAFY 반출 검수 준비 중**

## 구현 범위

- 공연 계획 초안, 프로그램과 업무 그룹, 초기 업무 배정
- 장비 상태 관측과 카메라 기반 안전 상황의 수집·표시를 위한 API 및 화면
- 문서 입력과 현장 보고 처리, 변경안 비교 및 승인, 알림·채팅 관련 코드
- AI의 초기 계획·변경안·현장 보고를 처리하는 Python 모듈과 Java 측 계약 검증

웹과 Android 공용 UI는 React·TypeScript·Vite로 구성했습니다. 웹 UI는 API를 사용하는 화면과 `DemoStoreProvider`의 시연 데이터를 사용하는 화면을 함께 포함합니다. 시연 데이터의 관측값은 합성 예시입니다. 운영 서비스와 Android 설치 패키지의 실행은 이 스냅샷의 검증 범위에서 제외했습니다.

| 영역 | 구성 |
|---|---|
| `frontend/` | React, TypeScript, Vite, Capacitor 공용 코드. 브라우저 화면·클라이언트 |
| `backend/` | Java 21, Spring Boot, PostgreSQL, Redis, Flyway. 업무 API·영속화 |
| `ai/` | Python 3.12. 계획, 배정, 보고, 변경안, 혼잡 추정 및 음성 기능의 모듈 |
| `equipment/collector/` | Python 장비 관측 수집기·프로토콜 처리 |

개별 기여는 팀 기록과 포트폴리오 작성자의 확인을 거쳐 기재할 예정입니다.

## 로컬 실행

웹은 Node.js 24.18 이상 25 미만과 npm 11을 사용합니다. 의존성을 설치할 수 있는 개발 환경에서 아래 명령을 실행합니다. 개발 서버는 루프백 주소에서 열리고, `/api`는 기본적으로 로컬 백엔드의 8080 포트로 연결됩니다.

```sh
cd frontend
npm ci
npm run dev
```

백엔드는 JDK 21과 Docker Compose를 사용할 수 있는 환경에서 로컬 PostgreSQL과 Redis를 띄운 뒤 `local` 프로필로 실행할 수 있습니다. Windows PowerShell에서는 `./gradlew` 대신 `./gradlew.bat`을 사용하고, 실행 전 `$env:SPRING_PROFILES_ACTIVE = 'local'`로 설정합니다. 예시 설정에는 로컬 개발용 값만 사용합니다.

```sh
cd backend
docker compose up -d postgres redis
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

## 스냅샷 범위와 제한

Compose 설정 구문과 장비 데이터 처리·AI 계획 계약의 일부 예시를 확인했습니다. Java 컴파일과 웹 빌드는 검증을 완료하지 못했고, STT 테스트는 의존성 부족으로 실행 단계에 도달하지 못했습니다. 전체 통합 시험과 애플리케이션 실행은 확인하지 않았습니다.

운영 배포 설정과 접속 정보, 내부 문서·산출물, Android 네이티브 디렉터리, 카메라 샘플, 학습 데이터·모델 가중치, 일부 테스트 자료를 제외했습니다. Android 빌드와 제외된 자료를 사용하는 테스트는 이 상태에서 실행할 수 없습니다. AI 모델 실행, 메일·OAuth·푸시, 장비·카메라 연동은 개별 서비스와 별도 설정이 필요합니다. 개발 서버에 내장된 고정 로그인 우회 기능은 제거했습니다. 의존성, 복사된 도구 및 글꼴의 출처는 [외부 구성요소 고지](THIRD-PARTY-NOTICES.md)에 기록했습니다.

# 외부 구성요소 고지

## 함께 제공되는 도구와 고지

- **Gradle Wrapper 8.14.3.** `backend/gradlew`, `backend/gradlew.bat`과 `backend/gradle/wrapper/`의 배포용 실행 파일입니다. 스크립트 헤더와 JAR 내부 `META-INF/LICENSE`의 Apache License 2.0 고지를 보존했습니다. [Gradle 배포 체크섬](https://gradle.org/release-checksums/)
- **Anton, Archivo, IBM Plex Sans KR, JetBrains Mono.** Fontsource 패키지를 통해 받는 글꼴입니다. SIL Open Font License 전문과 저작권 고지는 `frontend/public/licenses/fonts/`에 포함됩니다. [Fontsource](https://fontsource.org/)
- **Capacitor.** 공용 웹 코드가 사용하는 패키지와 버전은 `frontend/package.json` 및 `package-lock.json`에 있습니다. 생성 Android 앱과 그 리소스는 이번 스냅샷 범위에서 보류했습니다. [Capacitor](https://github.com/ionic-team/capacitor)

## 설치 시 내려받는 의존성

- npm의 React, Vite, TanStack Query, Fontsource, Capacitor 등은 `frontend/package.json`과 npm 잠금 파일의 버전과 패키지별 라이선스 항목을 확인할 수 있습니다.
- Spring Boot, PostgreSQL JDBC, Flyway, AWS SDK, Firebase, PDFBox 등의 Maven 좌표는 `backend/build.gradle`에 있습니다.
- AI·장비 모듈의 Python 의존성은 관련 `pyproject.toml` 및 잠금 파일에 있습니다. 코드가 참조하는 외부 모델의 가중치와 학습 데이터는 이 스냅샷에 들어 있지 않습니다. [Systran Whisper 모델](https://huggingface.co/Systran/faster-whisper-large-v3) · [KLUE RoBERTa 모델](https://huggingface.co/klue/roberta-base)

설치된 의존성이나 빌드 결과물을 별도로 배포하는 경우 해당 버전의 라이선스와 고지를 대조해야 합니다. 이 문서는 팀 자체 코드에 새로운 라이선스를 부여하지 않습니다.

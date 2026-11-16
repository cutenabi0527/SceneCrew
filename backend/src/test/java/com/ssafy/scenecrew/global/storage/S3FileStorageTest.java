package com.ssafy.scenecrew.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 실제 S3 API를 쓰는 MinIO를 띄워 저장·읽기·삭제를 확인한다. Docker가 있어야 실행된다.
 * 관리자 계정은 버킷과 사용자를 만들 때만 쓰고, 검증은 운영과 같은 권한만 가진 사용자로 한다.
 */
@EnabledIf("com.ssafy.scenecrew.support.DockerAvailability#isAvailable")
class S3FileStorageTest {
    // MinIO 공식 이미지가 Docker Hub에서 내려가 MinIO 호환 포크(Silo)를 쓴다. Testcontainers 공식 문서와 같은 이미지다.
    private static final String IMAGE = "pgsty/silo:RELEASE.2026-09-16T00-00-00Z";
    private static final String ADMIN_USER = "minio-user";
    private static final String ADMIN_PASSWORD = "minio-password";
    private static final String BUCKET = "scenecrew-test";
    private static final UUID EVENT = UUID.fromString("33333333-3333-4333-8333-333333333301");

    // backend/docs/file-storage.md "권한 (IAM)"과 같은 권한
    private static final String APP_POLICY = """
            {
              "Version": "2012-10-17",
              "Statement": [
                {"Effect": "Allow", "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
                 "Resource": "arn:aws:s3:::scenecrew-test/*"},
                {"Effect": "Allow", "Action": "s3:ListBucket", "Resource": "arn:aws:s3:::scenecrew-test"}
              ]
            }
            """;
    private static GenericContainer<?> minio;
    private static String endpoint;
    private static S3Client appS3;
    private static S3FileStorage storage;

    @BeforeAll
    static void startMinio() throws Exception {
        minio = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("MINIO_ROOT_USER", ADMIN_USER)
                .withEnv("MINIO_ROOT_PASSWORD", ADMIN_PASSWORD)
                .withCopyToContainer(Transferable.of(APP_POLICY), "/tmp/app-policy.json")
                .withCommand("server", "/data")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
        minio.start();
        endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);

        try (S3Client admin = client(ADMIN_USER, ADMIN_PASSWORD)) {
            admin.createBucket(request -> request.bucket(BUCKET));
        }
        mc("alias", "set", "local", "http://localhost:9000", ADMIN_USER, ADMIN_PASSWORD);
        createUser("scenecrew-app", "app-password-1234", "app-policy", "/tmp/app-policy.json");

        appS3 = client("scenecrew-app", "app-password-1234");
        storage = new S3FileStorage(appS3, BUCKET);
    }

    @AfterAll
    static void stopMinio() {
        if (appS3 != null) {
            appS3.close();
        }
        if (minio != null) {
            minio.stop();
        }
    }

    @Test
    void storedFileReadsBackWithSameBytesAndType() throws Exception {
        String key = StorageKeys.document(EVENT, UUID.randomUUID());
        byte[] content = "행사 운영 안내\n입장은 10시부터".getBytes(StandardCharsets.UTF_8);

        String sha256 = storage.put(key, content, "text/markdown");

        assertThat(sha256).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
        try (StoredFile file = storage.get(key)) {
            assertThat(file.contentType()).isEqualTo("text/markdown");
            assertThat(file.contentLength()).isEqualTo(content.length);
            assertThat(file.content().readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void missingFileIsNotFound() {
        String key = StorageKeys.audio(EVENT, UUID.randomUUID());

        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(StoredFileNotFoundException.class);
    }

    @Test
    void testUserHasOnlyAppPermissions() {
        // 관리자 권한으로 돌면 운영에서만 나는 권한 문제를 놓친다. 버킷 생성처럼 운영 권한에 없는 동작은 거부돼야 한다.
        assertThatThrownBy(() -> appS3.createBucket(request -> request.bucket("scenecrew-other")))
                .isInstanceOfSatisfying(S3Exception.class, e -> assertThat(e.statusCode()).isEqualTo(403));
    }

    @Test
    void deletedFileIsGoneAndDeletingAgainIsFine() {
        String key = StorageKeys.audio(EVENT, UUID.randomUUID());
        storage.put(key, new byte[] {1, 2, 3}, "audio/webm");

        storage.delete(key);

        assertThatThrownBy(() -> storage.get(key)).isInstanceOf(StoredFileNotFoundException.class);
        assertThatCode(() -> storage.delete(key)).doesNotThrowAnyException();
    }

    @Test
    void missingBucketSettingFailsClearly() {
        FileStorage noBucket = new S3FileStorage(appS3, "");

        assertThatThrownBy(() -> noBucket.get("audio/x/y"))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("S3_BUCKET");
    }

    @Test
    void bucketCheckSucceedsOnlyForReachableBucket() {
        // 기동 때 버킷 접근을 로그로 확인하는 S3BucketCheck(HeadBucket)가 운영 권한으로도 동작해야 한다.
        assertThat(new S3BucketCheck(appS3, properties(BUCKET)).check()).isTrue();
        assertThat(new S3BucketCheck(appS3, properties("scenecrew-missing")).check()).isFalse();
    }

    private static S3StorageProperties properties(String bucket) {
        return new S3StorageProperties(bucket, "ap-northeast-2", endpoint, "scenecrew-app", "app-password-1234");
    }

    private static S3Client client(String user, String password) {
        return S3StorageConfig.createClient(new S3StorageProperties(BUCKET, "ap-northeast-2", endpoint, user, password));
    }

    private static void createUser(String user, String password, String policy, String policyFile) throws Exception {
        mc("admin", "policy", "create", "local", policy, policyFile);
        mc("admin", "user", "add", "local", user, password);
        mc("admin", "policy", "attach", "local", policy, "--user", user);
    }

    /** 컨테이너 안의 MinIO 클라이언트(mcli)로 관리 명령을 실행한다. */
    private static void mc(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "mcli";
        System.arraycopy(args, 0, command, 1, args.length);
        ExecResult result = minio.execInContainer(command);
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("mcli 실패: " + String.join(" ", args) + "\n" + result.getStderr() + result.getStdout());
        }
    }
}

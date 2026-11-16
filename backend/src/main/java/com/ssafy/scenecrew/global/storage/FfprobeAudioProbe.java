package com.ssafy.scenecrew.global.storage;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ffprobe로 첫 음성 트랙의 패킷 시각을 읽어 실제 길이를 잰다.
 * 브라우저 녹음(MediaRecorder) WebM은 머리말에 전체 길이가 없는 경우가 많아, 머리말 값 대신 패킷의 끝 시각을 쓴다.
 * mp4(m4a)는 길이 정보가 파일 끝에 있을 수 있어 표준 입력 대신 임시 파일로 넘긴다.
 */
@Component
public class FfprobeAudioProbe implements AudioProbe {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final FileStorageProperties properties;

    public FfprobeAudioProbe(FileStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public AudioProbeResult probe(byte[] content) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("scenecrew-audio-", ".bin");
            output = Files.createTempFile("scenecrew-ffprobe-", ".json");
            Files.write(input, content);
            run(input, output);
            return parse(Files.readString(output, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AudioProbeException("음성 길이를 재지 못했습니다.", e);
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    /**
     * 출력은 파일로 받는다. 파이프로 받으면 읽는 스레드가 따로 있어야 하고, 그 스레드가 밀리면
     * ffprobe가 파이프가 찬 채 멈춰 시간 초과가 난다.
     */
    private void run(Path input, Path output) throws IOException {
        List<String> command = List.of(properties.ffprobePath(),
                "-v", "error",
                // 파일 안의 다른 주소(네트워크 등)를 따라가지 않는다.
                "-protocol_whitelist", "file",
                "-select_streams", "a:0",
                "-show_entries", "format=format_name:packet=pts_time,duration_time",
                "-of", "json",
                input.toString());
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            throw new AudioProbeException("ffprobe를 실행하지 못했습니다. 설치 여부를 확인해 주세요.", e);
        }
        try {
            if (!process.waitFor(properties.ffprobeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AudioProbeException("ffprobe가 제한 시간 안에 끝나지 않았습니다.");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new AudioProbeException("음성 길이를 재는 중 중단되었습니다.", e);
        }
        if (process.exitValue() != 0) {
            throw new InvalidAudioException("음성 파일을 열 수 없습니다.");
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // 임시 폴더 정리는 OS에 맡긴다.
        }
    }

    /**
     * ffprobe JSON에서 형식과 길이를 꺼낸다. 길이 = 가장 늦은 패킷의 끝(시작+길이) - 가장 이른 패킷의 시작.
     * 음성 패킷이 하나도 없으면 음성 파일이 아니다.
     */
    static AudioProbeResult parse(String json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidAudioException("음성 파일을 열 수 없습니다.");
        }
        JsonNode format = root.path("format").path("format_name");
        String formatName = format.isValueNode() ? format.asString() : "";
        BigDecimal first = null;
        BigDecimal last = null;
        JsonNode packets = root.path("packets");
        for (int i = 0; i < packets.size(); i++) {
            JsonNode packet = packets.get(i);
            BigDecimal start = decimal(packet.path("pts_time"));
            if (start == null) {
                continue;
            }
            BigDecimal length = decimal(packet.path("duration_time"));
            BigDecimal end = length == null ? start : start.add(length);
            first = first == null || start.compareTo(first) < 0 ? start : first;
            last = last == null || end.compareTo(last) > 0 ? end : last;
        }
        if (first == null || formatName.isEmpty()) {
            throw new InvalidAudioException("음성 트랙이 없습니다.");
        }
        return new AudioProbeResult(formatName, last.subtract(first).setScale(3, RoundingMode.HALF_UP));
    }

    private static BigDecimal decimal(JsonNode node) {
        if (!node.isValueNode()) {
            return null;
        }
        try {
            return new BigDecimal(node.asString());
        } catch (NumberFormatException e) {
            return null; // ffprobe는 모르는 값을 "N/A"로 준다.
        }
    }
}

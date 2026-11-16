package com.ssafy.scenecrew.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * ffprobe 출력(JSON) 해석. ffprobe 실행 자체는 운영 이미지에서만 되므로(CI는 건너뜀을 실패로 봄)
 * 여기서는 실제 ffprobe가 내는 모양의 출력으로 길이 계산과 오류 구분을 확인한다.
 */
class FfprobeAudioProbeTest {

    @Test
    void durationIsFromFirstPacketStartToLastPacketEnd() {
        AudioProbeResult result = FfprobeAudioProbe.parse("""
                {"packets": [
                   {"pts_time": "0.000000", "duration_time": "0.020000"},
                   {"pts_time": "0.020000", "duration_time": "0.020000"},
                   {"pts_time": "29.980000", "duration_time": "0.020000"}],
                 "format": {"format_name": "matroska,webm"}}
                """);

        assertThat(result.formatName()).isEqualTo("matroska,webm");
        assertThat(result.durationSec()).isEqualByComparingTo("30.000");
    }

    @Test
    void packetsWithoutDurationOrUnknownTimeAreHandled() {
        // 브라우저 녹음 WebM은 패킷 길이가 없거나(N/A) 시작이 0이 아닐 수 있다.
        AudioProbeResult result = FfprobeAudioProbe.parse("""
                {"packets": [
                   {"pts_time": "N/A"},
                   {"pts_time": "0.007000"},
                   {"pts_time": "12.507000"}],
                 "format": {"format_name": "matroska,webm"}}
                """);

        assertThat(result.durationSec()).isEqualByComparingTo("12.500");
    }

    @Test
    void fileWithoutAudioPacketsIsInvalid() {
        assertThatThrownBy(() -> FfprobeAudioProbe.parse("""
                {"packets": [], "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2"}}
                """)).isInstanceOf(InvalidAudioException.class);
        assertThatThrownBy(() -> FfprobeAudioProbe.parse("{}")).isInstanceOf(InvalidAudioException.class);
        assertThatThrownBy(() -> FfprobeAudioProbe.parse("not json")).isInstanceOf(InvalidAudioException.class);
    }

    @Test
    void missingFfprobeIsServerProblemNotFileProblem() {
        FfprobeAudioProbe probe = new FfprobeAudioProbe(new FileStorageProperties("scenecrew-no-such-ffprobe",
                Duration.ofSeconds(5), new FileStorageProperties.Cleanup(100)));

        assertThatThrownBy(() -> probe.probe(new byte[] {1, 2, 3})).isInstanceOf(AudioProbeException.class);
    }
}

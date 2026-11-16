package com.ssafy.scenecrew.support;

import java.math.BigDecimal;
import java.util.function.Function;

import com.ssafy.scenecrew.global.storage.AudioProbe;
import com.ssafy.scenecrew.global.storage.AudioProbeResult;

/** ffprobe 대신 정해 둔 결과를 돌려주는 시험용 측정기. ffprobe 결과 해석은 FfprobeAudioProbeTest가 확인한다. */
public class FakeAudioProbe implements AudioProbe {
    private volatile Function<byte[], AudioProbeResult> answer = content -> webm("10");

    @Override
    public AudioProbeResult probe(byte[] content) {
        return answer.apply(content);
    }

    public void answer(Function<byte[], AudioProbeResult> answer) {
        this.answer = answer;
    }

    public void reset() {
        answer = content -> webm("10");
    }

    public static AudioProbeResult webm(String seconds) {
        return new AudioProbeResult("matroska,webm", new BigDecimal(seconds));
    }

    public static AudioProbeResult m4a(String seconds) {
        return new AudioProbeResult("mov,mp4,m4a,3gp,3g2,mj2", new BigDecimal(seconds));
    }
}

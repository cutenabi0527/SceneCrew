package com.ssafy.scenecrew.global.storage;

import java.math.BigDecimal;

/**
 * @param formatName  ffprobe가 알아낸 컨테이너 형식(예: "mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm")
 * @param durationSec 첫 음성 트랙의 실제 길이(초, 소수 셋째 자리까지)
 */
public record AudioProbeResult(String formatName, BigDecimal durationSec) {
}

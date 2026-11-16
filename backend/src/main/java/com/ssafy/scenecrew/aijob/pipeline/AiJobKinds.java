package com.ssafy.scenecrew.aijob.pipeline;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ssafy.scenecrew.aijob.kind.AiJobKind;

/** 빈으로 등록된 AiJobKind를 kind 값으로 찾는다. 같은 kind가 둘이면 서버가 뜨지 않는다. */
@Component
public class AiJobKinds {
    private final Map<String, AiJobKind> kinds;

    public AiJobKinds(List<AiJobKind> kinds) {
        this.kinds = kinds.stream().collect(Collectors.toUnmodifiableMap(AiJobKind::kind, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("같은 AI 작업 종류가 둘 이상입니다: " + a.kind());
                }));
    }

    public Optional<AiJobKind> find(String kind) {
        return Optional.ofNullable(kinds.get(kind));
    }

    public Collection<AiJobKind> all() {
        return kinds.values();
    }
}

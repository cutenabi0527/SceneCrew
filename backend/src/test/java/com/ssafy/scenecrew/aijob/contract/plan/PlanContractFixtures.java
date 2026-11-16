package com.ssafy.scenecrew.aijob.contract.plan;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import com.ssafy.scenecrew.aijob.contract.AiContractJson;

import jakarta.validation.Validation;
import tools.jackson.databind.node.ObjectNode;

/** ai/plan 예시(test resources/ai-contract/plan)를 읽고 고쳐 쓰는 도우미 */
final class PlanContractFixtures {
    static final PlanResponseReader READER =
            new PlanResponseReader(Validation.buildDefaultValidatorFactory().getValidator());

    private PlanContractFixtures() {
    }

    static String json(String name) {
        try (InputStream in = PlanContractFixtures.class.getResourceAsStream("/ai-contract/plan/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("예시 파일 없음: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static PlanRequest request(String name) {
        return AiContractJson.mapper().readValue(json(name), PlanRequest.class);
    }

    /** 예시 응답을 읽어 일부를 바꾼 JSON 문자열을 만든다. */
    static String modified(String name, Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) AiContractJson.mapper().readTree(json(name));
        change.accept(root);
        return root.toString();
    }

    static ObjectNode task(ObjectNode root, int index) {
        return (ObjectNode) root.get("result").get("tasks").get(index);
    }
}

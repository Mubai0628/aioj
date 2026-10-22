package com.aioj.next.ai.domain.draftagent;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ProblemDraftFingerprintService {
    private final ObjectMapper objectMapper;

    public ProblemDraftFingerprintService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String contentFingerprint(ProblemDraftWorkingState state, Integer requestedCfRating) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("title", state.title());
        canonical.put("tags", state.tags());
        canonical.put("verification", verificationFields(state, requestedCfRating));
        return hash(canonical);
    }

    public String verificationFingerprint(ProblemDraftWorkingState state, Integer requestedCfRating) {
        return hash(verificationFields(state, requestedCfRating));
    }

    private Map<String, Object> verificationFields(ProblemDraftWorkingState state, Integer requestedCfRating) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("difficulty", state.difficulty());
        canonical.put("requestedCfRating", requestedCfRating);
        canonical.put("statement", state.statement());
        canonical.put("notes", state.notes());
        canonical.put("standardSolutionLanguage", state.standardSolutionLanguage());
        canonical.put("standardSolutionCode", state.standardSolutionCode());
        canonical.put("referenceSolutionLanguage", state.referenceSolutionLanguage());
        canonical.put("referenceSolutionCode", state.referenceSolutionCode());
        canonical.put("testcaseGeneratorPython", state.testcaseGeneratorPython());
        canonical.put("stressTestcaseGeneratorPython", state.stressTestcaseGeneratorPython());
        canonical.put("generationPlan", state.generationPlan());
        canonical.put("testCases", state.testCases());
        canonical.put("timeLimitMillis", state.timeLimitMillis());
        canonical.put("memoryLimitKb", state.memoryLimitKb());
        return canonical;
    }

    private String hash(Object value) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JacksonException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Cannot fingerprint problem draft state", ex);
        }
    }
}

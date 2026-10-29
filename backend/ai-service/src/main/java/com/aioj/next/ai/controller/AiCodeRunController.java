package com.aioj.next.ai.controller;

import com.aioj.next.ai.domain.coderun.CodeRunService;
import com.aioj.next.ai.domain.coderun.CodeRunSettingsService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiCodeRunRequest;
import com.aioj.next.contract.ai.AiCodeRunResponse;
import com.aioj.next.contract.ai.AiCodeRunSettings;
import com.aioj.next.contract.ai.AiCodeRunSettingsUpdateRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Plan 15.5: the self-test run of the problem page's editor, and its limits. */
@RestController
public class AiCodeRunController {
    private final CodeRunService runs;
    private final CodeRunSettingsService settings;

    public AiCodeRunController(CodeRunService runs, CodeRunSettingsService settings) {
        this.runs = runs;
        this.settings = settings;
    }

    @PostMapping("/ai/code-runs")
    public ApiResponse<AiCodeRunResponse> run(@RequestBody AiCodeRunRequest request) {
        return ApiResponse.ok(runs.run(SecuritySupport.currentUserId(), request));
    }

    /** The limits as the run panel shows them; who changed them last stays on the admin side. */
    @GetMapping("/ai/code-runs/settings")
    public ApiResponse<AiCodeRunSettings> limits() {
        AiCodeRunSettings current = settings.current();
        return ApiResponse.ok(new AiCodeRunSettings(current.enabled(), current.maxConcurrency(),
                current.perMinuteLimit(), current.perDayLimit(), current.minIntervalMillis(), null, null));
    }

    @GetMapping("/admin/ai/code-run-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AiCodeRunSettings> adminSettings() {
        return ApiResponse.ok(settings.current());
    }

    @PutMapping("/admin/ai/code-run-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<AiCodeRunSettings> updateSettings(@RequestBody AiCodeRunSettingsUpdateRequest request) {
        return ApiResponse.ok(settings.update(request, SecuritySupport.currentUserId()));
    }
}

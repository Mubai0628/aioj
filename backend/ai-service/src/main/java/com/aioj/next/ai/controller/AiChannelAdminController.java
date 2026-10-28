package com.aioj.next.ai.controller;

import com.aioj.next.ai.agent.channel.ChannelReplayService;
import com.aioj.next.common.api.ApiResponse;
import com.aioj.next.common.security.SecuritySupport;
import com.aioj.next.contract.ai.AiChannelReplayRequest;
import com.aioj.next.contract.ai.AiChannelReplayResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Operator tools for the unified data channel (plan T1.7); no page, admins only. */
@RestController
@PreAuthorize("hasRole('ADMIN')")
public class AiChannelAdminController {
    private final ChannelReplayService replayService;

    public AiChannelAdminController(ChannelReplayService replayService) {
        this.replayService = replayService;
    }

    @PostMapping("/ai/admin/channel/replay")
    public ApiResponse<AiChannelReplayResponse> replay(@RequestBody @Valid AiChannelReplayRequest request) {
        return ApiResponse.ok(replayService.replay(SecuritySupport.currentUserId(), request));
    }
}

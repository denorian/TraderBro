package com.traderbro.api.web;

import com.traderbro.execution.killswitch.KillSwitch;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual kill-switch control. Both endpoints require a confirmed body ({@code {"confirm":true}}).
 */
@RestController
@RequestMapping("/api/killswitch")
@RequiredArgsConstructor
public class KillSwitchController {

    private final KillSwitch killSwitch;

    public record ConfirmRequest(boolean confirm) {}

    public record KillSwitchResponse(boolean active, String reason, String message) {}

    @PostMapping("/activate")
    public KillSwitchResponse activate(@RequestBody ConfirmRequest body) {
        if (!body.confirm()) {
            return new KillSwitchResponse(killSwitch.isActive(), killSwitch.reason(),
                    "confirmation required (confirm=true)");
        }
        killSwitch.activate("manual REST activation");
        return new KillSwitchResponse(true, killSwitch.reason(), "kill-switch activated");
    }

    @PostMapping("/deactivate")
    public KillSwitchResponse deactivate(@RequestBody ConfirmRequest body) {
        boolean ok = killSwitch.deactivate(body.confirm());
        return new KillSwitchResponse(killSwitch.isActive(), killSwitch.reason(),
                ok ? "kill-switch deactivated" : "deactivation rejected (confirm=true required)");
    }
}
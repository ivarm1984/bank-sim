package io.github.ivarm1984.banksim.clock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clock")
public class ClockController {

    private final ClockService clockService;

    public ClockController(ClockService clockService) {
        this.clockService = clockService;
    }

    @GetMapping("/state")
    public ClockSnapshot state() {
        return clockService.state();
    }

    @PostMapping("/play")
    public ClockSnapshot play() {
        return clockService.play();
    }

    @PostMapping("/pause")
    public ClockSnapshot pause() {
        return clockService.pause();
    }

    @PostMapping("/reset")
    public ClockSnapshot reset() {
        return clockService.reset();
    }

    @PostMapping("/step-day")
    public ClockSnapshot stepDay() {
        return clockService.stepOneDay();
    }

    @PostMapping("/speed")
    public ClockSnapshot setSpeed(@Valid @RequestBody SpeedRequest request) {
        return clockService.setSpeed(request.minutesPerTick());
    }

    public record SpeedRequest(@Positive int minutesPerTick) {
    }
}

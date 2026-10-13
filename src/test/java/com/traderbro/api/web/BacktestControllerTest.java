package com.traderbro.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.traderbro.core.backtest.BacktestCosts;
import com.traderbro.core.backtest.BacktestOrchestrator;
import com.traderbro.core.backtest.BacktestRequest;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.strategies.StrategyRegistry;
import com.traderbro.core.domain.spi.StrategyConfigProvider;
import com.traderbro.storage.BacktestBatchRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** REST validation tests for the backtest API (standalone MockMvc). */
class BacktestControllerTest {

    private MockMvc mvc;
    private final BacktestOrchestrator orchestrator = mock(BacktestOrchestrator.class);
    private final BacktestBatchRepository repository = mock(BacktestBatchRepository.class);
    private final StrategyRegistry registry = mock(StrategyRegistry.class);
    private final StrategyConfigProvider configProvider = mock(StrategyConfigProvider.class);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BacktestController(orchestrator, repository,
                        registry, configProvider))
                .build();
    }

    private String validBody() {
        return """
                {"strategyIds":["SmaCrossStrategy"],"figis":["BBG004730RP0"],
                 "interval":"ONE_HOUR","from":"2026-01-01T00:00:00Z","to":"2026-10-01T00:00:00Z",
                 "costs":{"commissionPct":0.0005,"slippageBps":5},
                 "walkForward":false,"walkForwardTrainDays":180,"walkForwardTestDays":60}
                """;
    }

    @Test
    void createAcceptsValidRequest() throws Exception {
        when(orchestrator.submit(any(BacktestRequest.class))).thenReturn("run-1");
        mvc.perform(post("/api/backtests").contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void createRejectsEmptyStrategyList() throws Exception {
        String body = """
                {"strategyIds":[],"figis":["BBG004730RP0"],"interval":"ONE_HOUR",
                 "from":"2026-01-01T00:00:00Z","to":"2026-10-01T00:00:00Z",
                 "walkForward":false,"walkForwardTrainDays":180,"walkForwardTestDays":60}
                """;
        when(orchestrator.submit(any(BacktestRequest.class)))
                .thenThrow(new IllegalArgumentException("strategyIds must not be empty"));
        mvc.perform(post("/api/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createRejectsReversedPeriod() throws Exception {
        String body = """
                {"strategyIds":["SmaCrossStrategy"],"figis":["BBG004730RP0"],"interval":"ONE_HOUR",
                 "from":"2026-10-01T00:00:00Z","to":"2026-01-01T00:00:00Z",
                 "walkForward":false,"walkForwardTrainDays":180,"walkForwardTestDays":60}
                """;
        when(orchestrator.submit(any(BacktestRequest.class)))
                .thenThrow(new IllegalArgumentException("from must be before to"));
        mvc.perform(post("/api/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void compareRejectsMoreThanFiveIds() throws Exception {
        mvc.perform(get("/api/backtests/compare")
                        .param("ids", "a", "b", "c", "d", "e", "f"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void strategiesReturnsRegisteredIds() throws Exception {
        when(registry.ids()).thenReturn(java.util.Set.of("SmaCrossStrategy", "AlligatorAoStrategy"));
        when(configProvider.findById("SmaCrossStrategy"))
                .thenReturn(Optional.of(com.traderbro.core.domain.StrategyConfig.builder()
                        .id("SmaCrossStrategy").enabled(true)
                        .params(Map.of("fast", "20")).build()));
        mvc.perform(get("/api/backtests/strategies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
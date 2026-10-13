package com.traderbro.api.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.traderbro.api.config.StreamHealth;
import com.traderbro.core.domain.Bar;
import com.traderbro.core.domain.IndicatorSnapshot;
import com.traderbro.core.domain.Instrument;
import com.traderbro.core.domain.enums.CandleInterval;
import com.traderbro.core.domain.enums.InstrumentType;
import com.traderbro.core.domain.spi.BarStore;
import com.traderbro.core.indicators.IndicatorFactory;
import com.traderbro.storage.IndicatorSnapshotRepository;
import com.traderbro.storage.InstrumentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** MockMvc (standalone) tests for the read-only UI REST API — structure + bar limit. */
class UiControllerTest {

    private MockMvc mvc;
    private final InstrumentRepository instruments = mock(InstrumentRepository.class);
    private final BarStore barStore = mock(BarStore.class);
    private final IndicatorSnapshotRepository snapshotRepository = mock(IndicatorSnapshotRepository.class);
    private final StreamHealth streamHealth = mock(StreamHealth.class);

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new UiController(instruments, barStore,
                        snapshotRepository, IndicatorFactory.withDefaults(), streamHealth))
                .build();
    }

    @Test
    void instrumentsReturnsTypeAndStreamStatus() throws Exception {
        when(streamHealth.isConnected()).thenReturn(true);
        when(instruments.findAllTradable())
                .thenReturn(List.of(instrument("F1", "SBER", InstrumentType.SHARE)));

        mvc.perform(get("/api/instruments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].figi").value("F1"))
                .andExpect(jsonPath("$[0].type").value("SHARE"))
                .andExpect(jsonPath("$[0].streamUp").value(true));
    }

    @Test
    void chartReturnsBarsAndIndicatorLines() throws Exception {
        when(instruments.findByTicker("SBER"))
                .thenReturn(java.util.Optional.of(instrument("F1", "SBER", InstrumentType.SHARE)));
        when(barStore.findByFigiAndInterval(eq("F1"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(linearBars(60));

        mvc.perform(get("/api/chart/SBER").param("interval", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.figi").value("F1"))
                .andExpect(jsonPath("$.interval").value("ONE_HOUR"))
                .andExpect(jsonPath("$.bars.length()").value(60))
                .andExpect(jsonPath("$.alligator.length()").value(60))
                .andExpect(jsonPath("$.ao.length()").value(60));
    }

    @Test
    void chartLimitIsEnforced() throws Exception {
        when(instruments.findByTicker("SBER"))
                .thenReturn(java.util.Optional.of(instrument("F1", "SBER", InstrumentType.SHARE)));
        when(barStore.findByFigiAndInterval(eq("F1"), eq(CandleInterval.ONE_HOUR), any(), any()))
                .thenReturn(linearBars(60));

        mvc.perform(get("/api/chart/SBER").param("interval", "1h").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bars.length()").value(10))
                .andExpect(jsonPath("$.alligator.length()").value(10))
                .andExpect(jsonPath("$.ao.length()").value(10));
    }

    @Test
    void latestIndicatorsReturnsPerInstrumentSnapshots() throws Exception {
        when(snapshotRepository.latestForAll(CandleInterval.ONE_HOUR)).thenReturn(Map.of(
                "F1", List.of(
                        IndicatorSnapshot.builder().figi("F1").interval(CandleInterval.ONE_HOUR)
                                .ts(Instant.now())
                                .indicator(IndicatorSnapshot.IndicatorKind.ALLIGATOR)
                                .values(Map.of("state", "EATING_BULL")).build(),
                        IndicatorSnapshot.builder().figi("F1").interval(CandleInterval.ONE_HOUR)
                                .ts(Instant.now())
                                .indicator(IndicatorSnapshot.IndicatorKind.AO)
                                .values(Map.of("ao", 1.25)).build())));

        mvc.perform(get("/api/indicators/latest").param("interval", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].figi").value("F1"))
                .andExpect(jsonPath("$[0].alligator.state").value("EATING_BULL"))
                .andExpect(jsonPath("$[0].ao.ao").value(1.25));
    }

    private static Instrument instrument(String figi, String ticker, InstrumentType type) {
        return Instrument.builder().figi(figi).ticker(ticker).name(ticker)
                .currency("RUB").lot(10).minPriceIncrement(new BigDecimal("0.01"))
                .tradable(true).instrumentType(type).build();
    }

    private static List<Bar> linearBars(int n) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        java.util.ArrayList<Bar> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            double m = 100.0 + i;
            out.add(Bar.builder().figi("F1").interval(CandleInterval.ONE_HOUR)
                    .ts(start.plusSeconds(i * 3600L))
                    .open(BigDecimal.valueOf(m))
                    .high(BigDecimal.valueOf(m + 1))
                    .low(BigDecimal.valueOf(m - 1))
                    .close(BigDecimal.valueOf(m))
                    .volume(100)
                    .final_(true)
                    .build());
        }
        return out;
    }
}
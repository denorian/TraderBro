package com.traderbro.api.service;

import com.traderbro.core.domain.Portfolio;
import com.traderbro.core.domain.Position;
import com.traderbro.core.domain.spi.BrokerGateway;
import com.traderbro.core.domain.spi.PortfolioProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Portfolio valuation derived from broker positions (mark-to-market).
 * Cash is approximated as zero here; the exact cash leg is a TODO tied to the operations
 * snapshot which is broker-account-specific.
 */
@RequiredArgsConstructor
public class PortfolioService implements PortfolioProvider {

    private final BrokerGateway gateway;
    private final int moneyScale;

    @Override
    public Portfolio snapshot() {
        List<Position> positions = gateway.getPositions();
        BigDecimal securities = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (Position p : positions) {
            BigDecimal val = p.getCurrentPrice() != null
                    ? p.getCurrentPrice().multiply(BigDecimal.valueOf(p.getShares()))
                    : BigDecimal.ZERO;
            securities = securities.add(val);
            if (p.getCurrentPrice() != null && p.getAveragePrice() != null) {
                unrealized = unrealized.add(p.getCurrentPrice().subtract(p.getAveragePrice())
                        .multiply(BigDecimal.valueOf(p.getShares())));
            }
        }
        BigDecimal total = securities;
        BigDecimal dayPnLPct = total.signum() == 0
                ? BigDecimal.ZERO
                : unrealized.divide(total, 6, RoundingMode.HALF_UP);
        return Portfolio.builder()
                .totalValue(total.setScale(moneyScale, RoundingMode.HALF_UP))
                .cash(BigDecimal.ZERO.setScale(moneyScale))
                .securitiesValue(securities.setScale(moneyScale, RoundingMode.HALF_UP))
                .dayPnL(unrealized.setScale(moneyScale, RoundingMode.HALF_UP))
                .dayPnLPercent(dayPnLPct.setScale(moneyScale, RoundingMode.HALF_UP))
                .at(Instant.now())
                .build();
    }

    @Override
    public List<Position> positions() {
        return gateway.getPositions();
    }
}
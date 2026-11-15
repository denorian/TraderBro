package com.traderbro.signalanalysis;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.traderbro.TraderBroApplication;

/**
 * Isolation of the stage 4.0 signal-analysis section: it must never touch the trading contour,
 * the notification bot, the trading storage or TDLib types outside the dedicated adapter package.
 */
@AnalyzeClasses(packagesOf = TraderBroApplication.class)
class SignalAnalysisArchitectureTest {

    @ArchTest
    static final ArchRule signalAnalysisIsolatedFromTrading =
            noClasses().that().resideInAPackage("..signalanalysis..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.traderbro.execution..",
                            "com.traderbro.core..",
                            "com.traderbro.scheduler..",
                            "com.traderbro.data..",
                            "com.traderbro.notify..")
                    .as("signal-analysis must not depend on the trading contour, scheduler, data or notify-bot");

    @ArchTest
    static final ArchRule signalAnalysisDoesNotUseTradingStorage =
            noClasses().that().resideInAPackage("..signalanalysis..")
                    .should().dependOnClassesThat().resideInAPackage("com.traderbro.storage..")
                    .as("signal-analysis must not read/write the trading storage tables");

    @ArchTest
    static final ArchRule tdApiOnlyInsideAdapter =
            noClasses().that().resideInAPackage("..signalanalysis..")
                    .and().resideOutsideOfPackage("..signalanalysis.telegram.tdlib..")
                    .should().dependOnClassesThat().resideInAPackage("it.tdlight..")
                    .as("TdApi / TDLight types must only be used inside signalanalysis.telegram.tdlib");
}
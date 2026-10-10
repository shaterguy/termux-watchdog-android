package com.shaterguy.termuxwatchdog;
public class ModeFixtureRegressionTest {
    @org.junit.Test public void actualBackendResponses() throws Exception {
        ModeFixtureRegression.verify(getClass().getResourceAsStream("/mode-v2-fixtures.jsonl"));
    }
}

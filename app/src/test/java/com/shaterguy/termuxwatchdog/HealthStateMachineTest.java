package com.shaterguy.termuxwatchdog;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class HealthStateMachineTest {
    @Test
    public void downRequiresThreeConsecutiveFailures() {
        HealthStateMachine machine = new HealthStateMachine();

        assertEquals(HealthStateMachine.State.SUSPECT, machine.onHealthResult(false));
        assertEquals(1, machine.consecutiveFailures());
        assertEquals(HealthStateMachine.State.SUSPECT, machine.onHealthResult(false));
        assertEquals(2, machine.consecutiveFailures());
        assertEquals(HealthStateMachine.State.DOWN, machine.onHealthResult(false));
        assertEquals(3, machine.consecutiveFailures());
    }

    @Test
    public void successResetsFailuresAndDownState() {
        HealthStateMachine machine = new HealthStateMachine();
        machine.onHealthResult(false);
        machine.onHealthResult(false);
        machine.onHealthResult(false);

        assertEquals(HealthStateMachine.State.HEALTHY, machine.onHealthResult(true));
        assertEquals(0, machine.consecutiveFailures());
        assertEquals(HealthStateMachine.State.SUSPECT, machine.onHealthResult(false));
        assertEquals(1, machine.consecutiveFailures());
    }

    @Test
    public void persistedFailureCountRestoresDerivedState() {
        HealthStateMachine suspect = new HealthStateMachine(
                HealthStateMachine.State.HEALTHY, 2);
        HealthStateMachine down = new HealthStateMachine(
                HealthStateMachine.State.HEALTHY, 3);

        assertEquals(HealthStateMachine.State.SUSPECT, suspect.state());
        assertEquals(HealthStateMachine.State.DOWN, down.state());
    }
}

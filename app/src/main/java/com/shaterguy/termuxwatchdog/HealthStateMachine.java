package com.shaterguy.termuxwatchdog;

public final class HealthStateMachine {
    public enum State {
        HEALTHY,
        SUSPECT,
        DOWN
    }

    private State state;
    private int consecutiveFailures;

    public HealthStateMachine() {
        this(State.HEALTHY, 0);
    }

    public HealthStateMachine(State initialState, int initialFailures) {
        consecutiveFailures = Math.max(0, initialFailures);
        if (consecutiveFailures >= 3) {
            state = State.DOWN;
        } else if (consecutiveFailures > 0) {
            state = State.SUSPECT;
        } else {
            state = initialState == State.HEALTHY ? State.HEALTHY : State.HEALTHY;
        }
    }

    public State onHealthResult(boolean healthy) {
        if (healthy) {
            consecutiveFailures = 0;
            state = State.HEALTHY;
            return state;
        }

        consecutiveFailures++;
        state = consecutiveFailures >= 3 ? State.DOWN : State.SUSPECT;
        return state;
    }

    public State state() {
        return state;
    }

    public int consecutiveFailures() {
        return consecutiveFailures;
    }
}

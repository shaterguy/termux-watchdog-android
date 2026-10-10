package com.shaterguy.termuxwatchdog;

final class PowerModePolicy {
    private long generation;
    private boolean blocked;
    private String desired;
    private boolean pending;
    private long revision;
    private String commandId;

    PowerModePolicy() { this(0, false, "normal", false, -1, ""); }

    PowerModePolicy(long generation, boolean blocked, String desired, boolean pending, long revision, String commandId) {
        this.generation = Math.max(0, generation);
        this.blocked = blocked || pending;
        // Preserve a dev4 pending enable intent; never release the safety gate during upgrade.
        this.desired = "power_saving".equals(desired) || "auth_only".equals(desired) ? "power_saving" : "normal";
        this.pending = pending;
        this.revision = Math.max(-1, revision);
        this.commandId = commandId == null ? "" : commandId;
    }

    long begin(String command, String id) {
        if (id == null || !id.matches(PowerModeSnapshot.COMMAND_ID_PATTERN)) throw new IllegalArgumentException("MODE_COMMAND_ID_INVALID");
        if (pending) throw new IllegalStateException("MODE_REQUEST_PENDING");
        if (!"enable".equals(command) && !"disable".equals(command)) {
            throw new IllegalArgumentException("MODE_COMMAND_INVALID");
        }
        if (generation >= PowerModeSnapshot.MAX_COMMAND_SEQUENCE) throw new IllegalStateException("MODE_GENERATION_LIMIT");
        generation++;
        commandId = id;
        blocked = true;
        pending = true;
        desired = command.equals("enable") ? "power_saving" : "normal";
        return generation;
    }

    boolean observe(long callbackGeneration, String command, PowerModeSnapshot snapshot) {
        if (callbackGeneration != generation || snapshot.revision < revision) return false;
        if (pending && (commandId.isEmpty() || !snapshot.commandId.equals(commandId)
                || snapshot.commandSequence != generation)) return false;
        if (!command.equals("status") && !command.equals(desired.equals("power_saving") ? "enable" : "disable")) return false;
        if (pending && desired.equals("power_saving") && snapshot.isNormalVerified()) return false;
        revision = snapshot.revision;
        pending = false;
        generation = Math.max(generation, snapshot.commandSequence);
        desired = snapshot.requestedMode;
        blocked = !snapshot.isNormalVerified();
        return true;
    }

    void commandFinishedWithoutObservation(long callbackGeneration) {
        if (callbackGeneration == generation) pending = false;
    }

    String commandId() { return commandId; }

    boolean blocksRecovery() { return blocked; }
    boolean awaitingAcknowledgement() { return pending; }
    String desiredMode() { return desired; }
    long generation() { return generation; }
    long revision() { return revision; }
}

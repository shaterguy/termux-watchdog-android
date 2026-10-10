package com.shaterguy.termuxwatchdog;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
public final class ModeFixtureRegression {
    static void verify(InputStream input) throws Exception {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                int start = line.indexOf("\"snapshot\":");
                if (start < 0 || !line.endsWith("}")) throw new AssertionError("fixture wrapper");
                PowerModeSnapshot snapshot = PowerModeSnapshot.parse(line.substring(start + 11, line.length() - 1));
                if (snapshot.state.equals("power_saving") && !snapshot.isPowerSavingVerified()) throw new AssertionError("settled saver");
                count++;
            }
        }
        if (count != 8) throw new AssertionError("fixture count " + count);
        System.out.println("BACKEND_MODE_FIXTURES_PASS checks=" + count);
    }
    public static void main(String[] args) throws Exception { verify(Files.newInputStream(Path.of(args[0]))); }
}

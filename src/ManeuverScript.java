import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ManeuverScript {
    private static final String CSV_FILE = "default_maneuvers.csv";
    private static final String[] FIELD_NAMES = {"seconds", "roll", "pitch", "yaw"};
    private static final int[] MIN_VALUES = {0, -180, -90, -180};
    private static final int[] MAX_VALUES = {Integer.MAX_VALUE, 180, 90, 180};

    public static class Maneuver {
        public final int seconds;
        public final int roll;
        public final int pitch;
        public final int yaw;

        public Maneuver(int seconds, int roll, int pitch, int yaw) {
            this.seconds = seconds;
            this.roll = roll;
            this.pitch = pitch;
            this.yaw = yaw;
        }

        @Override
        public String toString() {
            return String.format("Maneuver[seconds=%d, roll=%d, pitch=%d, yaw=%d]",
                    seconds, roll, pitch, yaw);
        }
    }

    /**
     * Loads and validates maneuvers from the CSV file.
     * Reports any validation errors to the console.
     *
     * @return List of validated Maneuver objects, or empty list if validation errors occurred
     */
    public static List<Maneuver> loadManeuvers() {
        return loadManeuvers(CSV_FILE);
    }

    /**
     * Loads and validates maneuvers from the specified CSV file.
     * Reports any validation errors to the console.
     * Skips blank lines and lines beginning with #.
     *
     * @param csvFile Path to the CSV file to load
     * @return List of validated Maneuver objects, or empty list if validation errors occurred
     */
    public static List<Maneuver> loadManeuvers(String csvFile) {
        List<Maneuver> maneuvers = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int lineNumber = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                // Skip empty lines
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                // Skip comment lines (lines beginning with #)
                if (trimmed.startsWith("#")) {
                    continue;
                }

                // Skip header line
                if (lineNumber == 1 && trimmed.equalsIgnoreCase("seconds,roll,pitch,yaw")) {
                    continue;
                }

                // Parse the CSV line
                String[] values = trimmed.split(",");

                // Validate field count
                if (values.length != 4) {
                    errors.add(String.format("Script error on line %d: expected 4 fields but found %d",
                            lineNumber, values.length));
                    continue;
                }

                // Validate each field
                int[] parsedValues = new int[4];
                boolean valid = true;

                for (int i = 0; i < 4; i++) {
                    String fieldValue = values[i].trim();

                    // Try to parse as integer
                    try {
                        parsedValues[i] = Integer.parseInt(fieldValue);
                    } catch (NumberFormatException ex) {
                        errors.add(String.format("Script error on line %d field %d (\"%s\"): \"%s\" is not a number",
                                lineNumber, i + 1, FIELD_NAMES[i], fieldValue));
                        valid = false;
                        break;
                    }

                    // Validate range
                    if (parsedValues[i] < MIN_VALUES[i] || parsedValues[i] > MAX_VALUES[i]) {
                        errors.add(String.format("Script error on line %d field %d (\"%s\"): %d is out of range (expected %d to %d)",
                                lineNumber, i + 1, FIELD_NAMES[i], parsedValues[i], MIN_VALUES[i], MAX_VALUES[i]));
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    maneuvers.add(new Maneuver(parsedValues[0], parsedValues[1], parsedValues[2], parsedValues[3]));
                }
            }
        } catch (IOException ex) {
            System.err.println("Error: cannot open file '" + csvFile + "': " + ex.getMessage());
            System.exit(1);
        }

        // Print all errors to console
        for (String error : errors) {
            System.err.println(error);
        }

        return maneuvers;
    }

    /**
     * Validates the default maneuvers CSV file and reports results.
     * This is the entry point for command-line validation.
     */
    public static List<Maneuver> main() {
        System.out.println("Validating " + CSV_FILE + "...");
        List<Maneuver> maneuvers = loadManeuvers();

        if (maneuvers.isEmpty()) {
            System.out.println("No valid maneuvers found.");
        } else {
            System.out.println("Successfully loaded " + maneuvers.size() + " maneuver(s):");
            for (Maneuver maneuver : maneuvers) {
                System.out.println("  " + maneuver);
            }
        }
        return maneuvers;
    }
}

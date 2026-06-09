import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ManeuverScript {
    private static final String CSV_FILE = "default_maneuvers.csv";

    public static void main(String[] args) {
        int lineNumber = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(CSV_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();

                if (trimmed.isEmpty()) {
                    continue;
                }

                String[] values = trimmed.split(",");
                if (values.length != 4) {
                    errors.add("Line " + lineNumber + ": Malformed line - expected 4 values, found " + values.length);
                    continue;
                }

                if (lineNumber == 1 && values[0].trim().equalsIgnoreCase("seconds")
                        && values[1].trim().equalsIgnoreCase("roll")
                        && values[2].trim().equalsIgnoreCase("pitch")
                        && values[3].trim().equalsIgnoreCase("yaw")) {
                    continue;
                }

                try {
                    double seconds = Double.parseDouble(values[0].trim());
                    double roll = Double.parseDouble(values[1].trim());
                    double pitch = Double.parseDouble(values[2].trim());
                    double yaw = Double.parseDouble(values[3].trim());

                    if (seconds < 0) {
                        errors.add("Line " + lineNumber + ": Out-of-range value - seconds must be >= 0, found " + seconds);
                    }
                    if (roll < -180 || roll > 180) {
                        errors.add("Line " + lineNumber + ": Out-of-range value - roll must be between -180 and 180, found " + roll);
                    }
                    if (pitch < -90 || pitch > 90) {
                        errors.add("Line " + lineNumber + ": Out-of-range value - pitch must be between -90 and 90, found " + pitch);
                    }
                    if (yaw < -180 || yaw > 180) {
                        errors.add("Line " + lineNumber + ": Out-of-range value - yaw must be between -180 and 180, found " + yaw);
                    }
                } catch (NumberFormatException ex) {
                    errors.add("Line " + lineNumber + ": Non-numeric value - one or more fields are not valid numbers");
                }
            }
        } catch (IOException ex) {
            errors.add("Error reading " + CSV_FILE + ": " + ex.getMessage());
        }

        if (!errors.isEmpty()) {
            for (String error : errors) {
                System.out.println(error);
            }
        } else {
            System.out.println("No validation errors found in " + CSV_FILE + ".");
        }
    }
}

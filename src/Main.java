/*
 * Copyright (C) 2025 Shivaji Patil, College of the North Atlantic
 * All rights reserved.
 * 
 * Aircraft Simulation Project
 */

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    /** Writer for logging output to file */
    private static PrintWriter logWriter;

    /** Writer for CSV data logging */
    static PrintWriter csvLogWriter;

    /** Statistics object to track simulation performance */
    private static Map<String, Map<String, Double>> statisticsData = new HashMap<>();

    // Direction control instances
    private static DirectionControl rollControl;
    private static DirectionControl pitchControl;
    private static DirectionControl yawControl;

    /**
     * Helper method to parse command line arguments
     */
    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> argMap = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                String key = args[i].substring(2);
                String value = args[i + 1];
                argMap.put(key, value);
                i++;  // Skip the value in next iteration
            }
        }
        return argMap;
    }

    private static boolean hasFlag(String[] args, String... flags) {
        for (String arg : args) {
            for (String flag : flags) {
                if (arg.equals(flag)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Logs a message to the log file.
     */
    public static void logToFile(String message) {
        if (logWriter != null) {
            logWriter.println(message);
            logWriter.flush();
        }
    }

    /**
     * Logs data to the CSV file for later analysis.
     */
    public static void logToCSV(String axis, double expected, double actual, double velocity) {
        if (csvLogWriter != null) {
            double deviation = expected - actual;
            String timestamp = java.time.LocalDateTime.now().toString();
            csvLogWriter.println(String.format("%s,%s,%.2f,%.2f,%.2f,%.2f",
                    timestamp, axis, expected, actual, deviation, velocity));
            csvLogWriter.flush();
        }
    }

    /**
     * Displays the collected statistics for all direction controls
     */
    public static void displayStatistics() {
        System.out.println("\n===== SIMULATION STATISTICS =====");
        for (String axis : statisticsData.keySet()) {
            Map<String, Double> stats = statisticsData.get(axis);
            System.out.println(axis + " Statistics:");
            System.out.println("  Samples: " + stats.get("sampleCount").intValue());
            System.out.println("  Average Deviation: " + String.format("%.2f", stats.get("averageDeviation")));
            System.out.println("  Maximum Deviation: " + String.format("%.2f", stats.get("maxDeviation")));
        }
    }

    public static void main(String[] args) throws InterruptedException {
        // Parse command line arguments
        Map<String, String> params = parseArgs(args);
        boolean injectFailures = hasFlag(args, "-inject-failures", "--inject-failures");

        // Apply the native Swing look-and-feel and announce the host OS.
        PlatformSupport.applySystemLookAndFeel();
        System.out.println("Detected OS: " + PlatformSupport.osLabel()
                + " (ANSI on stdout: " + PlatformSupport.supportsAnsi() + ")");

        // Set up configuration
        String configDir = System.getProperty("user.home") + File.separator + ".aircraft_sim";
        new File(configDir).mkdirs(); // Create directory if it doesn't exist
        
        String configFile = configDir + File.separator + "config.properties";
        ConfigLoader config = new ConfigLoader(configFile);
        
        // Set up logging
        boolean enableLogging = config.getBoolean("logging.enabled", false);
        if (enableLogging) {
            try {
                String logFile = configDir + File.separator + "simulation_" +
                    LocalDateTime.now().toString().replace(':', '_') + ".log";
                logWriter = new PrintWriter(new FileWriter(logFile));

                String csvFile = configDir + File.separator + "data_" +
                    LocalDateTime.now().toString().replace(':', '_') + ".csv";
                csvLogWriter = new PrintWriter(new BufferedWriter(new FileWriter(csvFile)));
                csvLogWriter.println("timestamp,axis,expected,actual,deviation,velocity");

                logToFile("Simulation started");
            } catch (IOException e) {
                System.err.println("Error setting up logging: " + e.getMessage());
            }
        }

        // Create direction controls with DirectionControlStable for ultra stability
        rollControl = new DirectionControlStable("Roll", -180, 180, config);
        pitchControl = new DirectionControlStable("Pitch", -90, 90, config);
        yawControl = new DirectionControlStable("Yaw", -180, 180, config);

        // Clear screen and hide cursor for clean visualization (only when the
        // terminal supports ANSI escape sequences).
        if (PlatformSupport.supportsAnsi()) {
            System.out.print("\033[H\033[2J");  // Clear screen
            System.out.print("\033[?25l");       // Hide cursor
        }

        // Set up turbulence flag - enable by default for autonomous mode
        AtomicBoolean turbulenceEnabled = new AtomicBoolean(true);

        // Set up running flag
        AtomicBoolean running = new AtomicBoolean(true);

        // Display welcome message for autonomous mode
        System.out.println("\n\nAUTONOMOUS AIRCRAFT SIMULATION");
        System.out.println("-----------------------------");
        System.out.println("This simulation runs completely autonomously.");
        System.out.println("All flight maneuvers are performed automatically.");
        System.out.println("Press 'q' and Enter at any time to quit the simulation.");
        System.out.println("\nStarting simulation in 3 seconds...");
        Thread.sleep(3000);

        // Create and start threads
        Thread userInputThread = createInputThread(rollControl, pitchControl, yawControl, turbulenceEnabled, running);
        Thread turbulenceThread = new Thread(new SupervisedRunner(
                "turbulence",
                createTurbulenceWorker(rollControl, pitchControl, yawControl, turbulenceEnabled, running, System.currentTimeMillis(), injectFailures),
                running::get), "turbulence");
        Thread automatedDemoThread = new Thread(new SupervisedRunner(
                "automated-demo",
                createAutomatedDemoWorker(rollControl, pitchControl, yawControl, running),
                running::get), "automated-demo");

        userInputThread.start();
        turbulenceThread.setDaemon(true);
        turbulenceThread.start();
        automatedDemoThread.setDaemon(true);
        automatedDemoThread.start();

        // Create and start the Swing GUI. The GUI reads orientation directly
        // from the DirectionControl instances passed in - no JSON intermediary.
        System.out.println("Launching Swing GUI for flight parameters display...");
        AircraftGUI gui = new AircraftGUI(rollControl, pitchControl, yawControl);

        // Live OS resource monitor: samples CPU / memory once a second and
        // tells the GUI to throttle its frame rate when the host is under load.
        ResourceMonitor resourceMonitor = new ResourceMonitor(1000, gui::setPerformanceLevel);
        gui.setResourceMonitor(resourceMonitor);
        Thread resourceMonitorThread = new Thread(new SupervisedRunner(
                "resource-monitor",
                resourceMonitor,
                running::get), "ResourceMonitor");
        resourceMonitorThread.setDaemon(true);
        resourceMonitorThread.start();

        gui.show();
        
        // Create and start thread to update the GUI
        Thread guiUpdateThread = AircraftGUI.createGUIUpdateThread(gui, running);
        guiUpdateThread.start();
        
        // Set up quit action to stop the simulation
        try {
            gui.setQuitAction(() -> {
                running.set(false);
            });
            System.out.println("Quit action registered successfully.");
        } catch (Exception e) {
            System.err.println("Warning: Could not register quit action: " + e.getMessage());
            System.err.println("Simulation will continue, but you may need to use Ctrl+C to exit.");
        }
        
        System.out.println("Swing GUI is reading orientation directly from the DirectionControl simulation.");
        
        // Initial startup message that won't get cleared
        System.out.println("\nSTARTING AIRCRAFT SIMULATION WITH DEDICATED FLIGHT ANALYTICS DISPLAY\n");
        System.out.println("Loading configuration...");
        System.out.println("Initializing flight dynamics...");
        System.out.println("Starting analytics display thread...");
        System.out.println("\nPress 'q' to quit the simulation when you want to stop.\n");
        
        // Main simulation loop
        try {
            System.out.println("Simulation active - check analytics display...");
            
            // Wait for quit command
            while (running.get()) {
                // Update direction controls with physics
                rollControl.update();
                pitchControl.update();
                yawControl.update();
                
                // Small sleep to reduce CPU usage
                Thread.sleep(50);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            // Stop the resource monitor first - it's a daemon thread but we
            // ask it to exit cleanly before tearing down everything else.
            resourceMonitor.stop();
            resourceMonitorThread.interrupt();

            // Interrupt the GUI update thread
            guiUpdateThread.interrupt();

            // Show cursor again when exiting
            if (PlatformSupport.supportsAnsi()) {
                System.out.print("\033[?25h"); // Show cursor
            }

            // Close log writers if open
            if (logWriter != null) {
                logWriter.close();
            }
            if (csvLogWriter != null) {
                csvLogWriter.close();
            }

            // Interrupt other threads
            userInputThread.interrupt();
            turbulenceThread.interrupt();
            automatedDemoThread.interrupt();

            System.out.println("\nSimulation terminated. Thank you for flying with us!");
        }


        // Collect and display statistics
        statisticsData.put("Roll", rollControl.getStatistics());
        statisticsData.put("Pitch", pitchControl.getStatistics());
        statisticsData.put("Yaw", yawControl.getStatistics());
        displayStatistics();

        // Close log files
        closeLogFiles();
    }

    private static void closeLogFiles() {
        if (logWriter != null) {
            logWriter.close();
        }
        if (csvLogWriter != null) {
            csvLogWriter.close();
        }
    }

    /**
     * Creates a thread that only accepts input to stop the execution
     * Simulation runs completely autonomously
     */
    private static Thread createInputThread(DirectionControl roll, DirectionControl pitch, DirectionControl yaw,
                                     AtomicBoolean turbulenceEnabled, AtomicBoolean running) {
        return new Thread(() -> {
            try (Scanner scanner = new Scanner(System.in)) {
                System.out.println("\nAUTONOMOUS SIMULATION MODE");
                System.out.println("Press 'q' to quit at any time");
                
                while (running.get()) {
                    if (scanner.hasNextLine()) {
                        String input = scanner.nextLine().trim().toLowerCase();
                        if (input.equals("q")) {
                            System.out.println("Stopping simulation...");
                            running.set(false);
                            break;
                        } else {
                            System.out.println("Press 'q' to quit the simulation");
                        }
                    }
                    
                    // Small sleep to prevent tight loop
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }
    
    /**
     * Creates a supervised turbulence worker.
     */
    private static Runnable createTurbulenceWorker(DirectionControl roll, DirectionControl pitch, DirectionControl yaw,
                                                  AtomicBoolean turbulenceEnabled, AtomicBoolean running,
                                                  long simulationStartMs, boolean injectFailures) {
        return () -> {
            Random random = new Random();
            boolean injected3s = false;
            boolean injected6s = false;
            boolean injected9s = false;

            while (running.get()) {
                long elapsedSeconds = (System.currentTimeMillis() - simulationStartMs) / 1000;
                if (injectFailures) {
                    if (!injected3s && elapsedSeconds >= 3) {
                        injected3s = true;
                        throw new RuntimeException("Injected turbulence failure at 3 seconds");
                    }
                    if (!injected6s && elapsedSeconds >= 6) {
                        injected6s = true;
                        throw new RuntimeException("Injected turbulence failure at 6 seconds");
                    }
                    if (!injected9s && elapsedSeconds >= 9) {
                        injected9s = true;
                        throw new RuntimeException("Injected turbulence failure at 9 seconds");
                    }
                }

                try {
                    if (turbulenceEnabled.get()) {
                        double rollJitter = (random.nextDouble() - 0.5) * 2.0;
                        double pitchJitter = (random.nextDouble() - 0.5) * 1.5;
                        double yawJitter = (random.nextDouble() - 0.5) * 1.0;

                        roll.setCurrentValue(roll.getCurrentValue() + rollJitter);
                        pitch.setCurrentValue(pitch.getCurrentValue() + pitchJitter);
                        yaw.setCurrentValue(yaw.getCurrentValue() + yawJitter);
                    }
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        };
    }

    /**
     * Creates a supervised automated demonstration worker.
     */
    private static Runnable createAutomatedDemoWorker(DirectionControl roll, DirectionControl pitch,
                                                     DirectionControl yaw, AtomicBoolean running) {
        return () -> {
            try {
                Thread.sleep(3000); // Longer initial delay
                System.out.println("\nStarting automated flight demonstration with ultra-gentle maneuvers...");

                while (running.get()) {
                    List<ManeuverScript.Maneuver> maneuvers = ManeuverScript.main();
                    for (ManeuverScript.Maneuver maneuver : maneuvers) {
                        if (!running.get()) {
                            return;
                        }
                        System.out.println("\nDemonstrating: " + maneuver);
                        roll.setTargetValue(maneuver.roll);
                        pitch.setTargetValue(maneuver.pitch);
                        yaw.setTargetValue(maneuver.yaw);
                        Thread.sleep(maneuver.seconds * 1000L);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.out.println("Demo thread interrupted.");
            }
        };
    }
}
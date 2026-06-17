/*
 * Copyright (C) 2025 Shivaji Patil, College of the North Atlantic
 * All rights reserved.
 * 
 * Aircraft Simulation Project
 */

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.Timer;

/**
 * Professional aircraft simulation controller with multithreaded architecture.
 * 
 * This class manages the entire simulation lifecycle including:
 * 1. Realistic flight dynamics with energy management
 * 2. Coordinated turns with proper bank/roll relationship
 * 3. Professional turbulence modeling and response
 * 4. Stability systems with flight envelope protection
 * 5. Day/night cycle and weather simulation
 * 
 * Architecture Diagram:
 * 
 * +-------------------+      +----------------------+      +------------------+
 * | User Input Thread  | ---> | Flight Control Logic | ---> | Simulation Thread |
 * +-------------------+      +----------------------+      +------------------+
 *                                      |                            |
 *                                      v                            v
 *                             +----------------+           +------------------+
 *                             | Weather System | <-------- | Flight Dynamics  |
 *                             +----------------+           +------------------+
 *                                      |                            |
 *                                      v                            v
 *                             +----------------+           +------------------+
 *                             | Environment   | --------> | Aircraft State   |
 *                             | Effects       |           | Management       |
 *                             +----------------+           +------------------+
 *                                                                  |
 *                                                                  v
 *                                                         +------------------+
 *                                                         | Rendering Thread |
 *                                                         +------------------+
 * 
 * Flow of Control:
 * 1. Input processing and flight controls
 * 2. Physics simulation and aircraft dynamics
 * 3. Environmental effects and weather simulation
 * 4. State management and coordination
 * 5. Rendering and visualization
 * 
 * The simulation uses a Swing-based UI to render a realistic aircraft display with
 * professional flight parameters, navigation data, and system status information.
 */
public class AircraftGUI {
    // Constants
    private static final int PANEL_WIDTH = 800;
    private static final int PANEL_HEIGHT = 600;
    
    // Thread control and performance monitoring
    private final ThreadFactory namedThreadFactory = new ThreadFactory() {
        private int counter = 0;
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setName("AircraftSim-" + counter++);
            t.setPriority(Thread.NORM_PRIORITY);
            t.setUncaughtExceptionHandler((thread, ex) -> {
                System.err.println("Uncaught exception in thread " + thread.getName() + ": " + ex.getMessage());
                ex.printStackTrace();
            });
            return t;
        }
    };
    
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4, namedThreadFactory);
    private final ExecutorService executor = Executors.newCachedThreadPool(namedThreadFactory);
    
    // Thread synchronization and control
    private volatile boolean running = true;
    private final Object environmentLock = new Object();
    private final Object aircraftLock = new Object();
    private final Object obstacleLock = new Object();
    
    // Thread performance monitoring
    private long[] threadExecutionTimes = new long[5]; // [environment, aircraft, obstacles, rendering, overall]
    private long[] threadExecutionCounts = new long[5];
    private long monitoringStartTime;
    private final String[] threadNames = {"Environment", "Aircraft", "Obstacles", "Rendering", "Overall"};
    private boolean showPerformanceOverlay = true; // Start with overlay visible
    private long lastFrameTime = 0;
    private double avgFrameTime = 16.67; // Target 60 FPS (16.67ms)
    private final DecimalFormat df = new DecimalFormat("#0.00");
    
    // Direction controls that drive aircraft orientation.
    // The GUI reads from these every frame instead of generating its own roll/pitch/yaw,
    // so the displayed attitude matches the simulation in Main.
    private final DirectionControl rollControl;
    private final DirectionControl pitchControl;
    private final DirectionControl yawControl;
    private final DirectionControlListener attitudeListener;

    // Optional resource monitor - if set, GUI throttles its frame rate based on
    // the latest OS CPU / memory measurements published by the monitor thread.
    private ResourceMonitor resourceMonitor;

    // Aircraft state variables
    private double roll = 0.0;
    private double pitch = 0.0;
    private double yaw = 0.0;
    private double flightSpeed = 250.0;
    private double currentAltitude = 11000.0;
    private double targetAltitude = 11000.0;

    /*
     * Threading note:
     * DirectionControl notifications run on the simulation thread, while the Swing
     * timer runs on the Event Dispatch Thread. The listener only writes to volatile
     * cache fields here and does not touch Swing, so each update is safely published
     * to the EDT without violating Swing's single-thread rule.
     */

    // Enhanced flight dynamics variables
    private long simulationStartTime = System.currentTimeMillis();
    private double turbulenceFactor = 0.0;
    private boolean isClimbingDecision = false;
    private String currentDecision = "LEVEL FLIGHT"; // Current decision text
    private boolean thunderstormAhead = false;
    
    // Environment variables
    private double timeOfDay = 0.5; // 0=dawn, 0.5=noon, 1.0=dusk, 1.5=midnight
    private int dayNightCycleCounter = 0;
    private boolean isDayTime = true;
    
    // Animation timer
    private Timer timer;
    private Random random = new Random();
    
    // UI Components
    private JFrame frame;
    private AircraftPanel panel;
    
    // Fonts for text displays
    private Font mediumFont = new Font("Arial", Font.BOLD, 16);

    /**
     * Creates the GUI bound to the simulation's three orientation controls.
     * Roll/pitch/yaw shown on screen are read from these instances each frame.
     */
    public AircraftGUI(DirectionControl rollControl,
                       DirectionControl pitchControl,
                       DirectionControl yawControl) {
                        
        this.rollControl = rollControl;
        this.pitchControl = pitchControl;
        this.yawControl = yawControl;

        roll = rollControl.getCurrentValue();
        pitch = pitchControl.getCurrentValue();
        yaw = yawControl.getCurrentValue();

        attitudeListener = control -> {
            double latestValue = control.getCurrentValue();
            if (control == this.rollControl) {
                roll = latestValue;
            } else if (control == this.pitchControl) {
                pitch = latestValue;
            } else if (control == this.yawControl) {
                yaw = latestValue;
            }
        };

        this.rollControl.addListener(attitudeListener);
        this.pitchControl.addListener(attitudeListener);
        this.yawControl.addListener(attitudeListener);
    }

    public void setResourceMonitor(ResourceMonitor monitor) {
        this.resourceMonitor = monitor;
    }

    /**
     * Adjusts the render-timer cadence in response to the host's CPU pressure.
     * Called from the ResourceMonitor thread; we marshal the timer change onto
     * the EDT.
     */
    public void setPerformanceLevel(ResourceMonitor.PerformanceLevel level) {
        SwingUtilities.invokeLater(() -> {
            if (timer == null) return;
            int newDelay;
            switch (level) {
                case MINIMAL: newDelay = 66; break; // ~15 FPS
                case REDUCED: newDelay = 33; break; // ~30 FPS
                case NORMAL:
                default:      newDelay = 16; break; // ~60 FPS
            }
            if (timer.getDelay() != newDelay) {
                timer.setDelay(newDelay);
                System.out.println("Performance level: " + level + " -> timer delay " + newDelay + "ms");
            }
        });
    }

    // Method that Main.java calls to display the GUI
    public void show() {
        SwingUtilities.invokeLater(() -> {
            createAndShowGUI();
            startMultithreadedSimulation();
            System.out.println("Aircraft simulation GUI is now visible");
        });
    }
    
    // Start multithreaded simulation with performance monitoring
    private void startMultithreadedSimulation() {
        monitoringStartTime = System.currentTimeMillis();
        running = true;
        
        System.out.println("Starting multithreaded simulation with performance monitoring");
        System.out.println("Toggle performance overlay with 'P' key");
        
        // Thread for updating the environment (clouds, terrain, day/night cycle)
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            long startTime = System.nanoTime();
            try {
                synchronized (environmentLock) {
                    updateEnvironment();
                }
            } catch (Exception ex) {
                System.err.println("Error in environment thread: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                long duration = System.nanoTime() - startTime;
                threadExecutionTimes[0] += duration;
                threadExecutionCounts[0]++;
                long durationMs = duration / 1_000_000;
                if (durationMs > 20) {
                    System.out.println("Environment update took " + durationMs + "ms");
                }
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
        
        // Thread for updating aircraft physics
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            long startTime = System.nanoTime();
            try {
                synchronized (aircraftLock) {
                    updateAircraft();
                }
            } catch (Exception ex) {
                System.err.println("Error in aircraft physics thread: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                long duration = System.nanoTime() - startTime;
                threadExecutionTimes[1] += duration;
                threadExecutionCounts[1]++;
                long durationMs = duration / 1_000_000;
                if (durationMs > 16) {
                    System.out.println("Aircraft physics update took " + durationMs + "ms");
                }
            }
        }, 0, 33, TimeUnit.MILLISECONDS);
        
        // Thread for obstacle processing and turbulence
        scheduler.scheduleAtFixedRate(() -> {
            if (!running) return;
            long startTime = System.nanoTime();
            try {
                synchronized (obstacleLock) {
                    processObstacles();
                    updateTurbulence();
                }
            } catch (Exception ex) {
                System.err.println("Error in obstacle/turbulence thread: " + ex.getMessage());
                ex.printStackTrace();
            } finally {
                long duration = System.nanoTime() - startTime;
                threadExecutionTimes[2] += duration;
                threadExecutionCounts[2]++;
                long durationMs = duration / 1_000_000;
                if (durationMs > 30) {
                    System.out.println("Obstacle/turbulence update took " + durationMs + "ms");
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        
        // Timer for display updates - runs at 60fps for smooth rendering
        lastFrameTime = System.currentTimeMillis();
        
        // Use Swing Timer for GUI updates at 60 FPS
        timer = new Timer(16, e -> {
            // Measure frame time for performance monitoring
            long startTime = System.nanoTime();
            long now = System.currentTimeMillis();
            long frameTime = now - lastFrameTime;
            lastFrameTime = now;
            
            // Track moving average of frame time (exponential moving average)
            avgFrameTime = (avgFrameTime * 0.95) + (frameTime * 0.05);

            // Log live OS resource snapshot roughly once per second so the
            // throttling decisions made by ResourceMonitor are visible.
            if (resourceMonitor != null && dayNightCycleCounter % 60 == 0) {
                double cpu = resourceMonitor.getSystemCpuLoad();
                double proc = resourceMonitor.getProcessCpuLoad();
                double heap = resourceMonitor.getHeapUsedFraction();
                System.out.printf("[resources] sysCPU=%5.1f%%  procCPU=%5.1f%%  heap=%5.1f%%  level=%s  fps~%d%n",
                        cpu * 100, proc * 100, heap * 100,
                        resourceMonitor.getCurrentLevel(),
                        timer != null ? 1000 / Math.max(1, timer.getDelay()) : 0);
            }

            // Process day/night cycle
            dayNightCycleCounter++;
            if (dayNightCycleCounter > 300) {
                dayNightCycleCounter = 0;
                timeOfDay += 0.1;
                if (timeOfDay > 2.0) timeOfDay = 0;
                isDayTime = timeOfDay < 1.0 || timeOfDay > 1.8;
            }
            
            // Update panel with our current state
            if (panel != null) {
                // These calls update the AircraftPanel's state for rendering
                synchronized (aircraftLock) {
                    // Update individual aircraft parameters
                    panel.setRoll(roll);
                    panel.setPitch(pitch);
                    panel.setYaw(yaw);
                    panel.setFlightSpeed(flightSpeed);
                    panel.setAltitude(currentAltitude);
                    panel.setTurbulenceFactor(turbulenceFactor);
                }
                
                // Update day/night cycle information
                synchronized (environmentLock) {
                    panel.setTimeOfDay(timeOfDay);
                    panel.setIsDayTime(isDayTime);
                }
                
                // Update turbulence and weather information
                synchronized (obstacleLock) {
                    panel.setThunderstormAhead(thunderstormAhead);
                    panel.setTurbulenceFactor(turbulenceFactor);
                    panel.setCurrentDecision(currentDecision);
                }
                
                // Set performance information for overlay
                panel.setThreadExecutionTimes(threadExecutionTimes);
                panel.setThreadExecutionCounts(threadExecutionCounts);
                panel.setThreadNames(threadNames);
                panel.setShowPerformanceOverlay(showPerformanceOverlay);
                panel.setAvgFrameTime(avgFrameTime);
                panel.setMonitoringStartTime(monitoringStartTime);
                
                // Repaint the panel
                panel.repaint();
            }
            
            // Update thread execution timing
            long duration = System.nanoTime() - startTime;
            threadExecutionTimes[3] += duration;
            threadExecutionCounts[3]++;
        });
        
        // Start the timer
        timer.start();
        
        // Schedule status update after 5 seconds
        scheduler.schedule(() -> {
            System.out.println("Multithreaded simulation running with " + threadNames.length + " threads");
            for (int i = 0; i < threadNames.length; i++) {
                if (threadExecutionCounts[i] > 0) {
                    double avgExecTimeMs = (threadExecutionTimes[i] / (double)threadExecutionCounts[i]) / 1_000_000.0;
                    System.out.println(threadNames[i] + " thread: " + df.format(avgExecTimeMs) + "ms avg");
                }
            }
        }, 5, TimeUnit.SECONDS);
    }
    
    // Update environment
    private void updateEnvironment() {
        // This now primarily updates time of day, which is passed to AircraftPanel
        // The panel handles the actual rendering
    }
    
    /**
     * Updates GUI-specific dynamics (altitude tracking and airspeed variation).
     *
     * The axis values are maintained by the listener cache.
     */
    private void updateAircraft() {
        long currentTime = System.currentTimeMillis();
        double timeSeconds = (currentTime - simulationStartTime) / 1000.0;

        // Altitude tracking: move toward targetAltitude at up to 500 ft/min.
        double altitudeDifference = targetAltitude - currentAltitude;
        double climbRate = Math.min(Math.max(altitudeDifference / 10.0, -500), 500);
        currentAltitude += climbRate / 60.0;
        currentAltitude += (random.nextDouble() - 0.5) * 5.0; // air-pocket jitter

        // Airspeed variation around cruise.
        double baseSpeed = 250.0;
        double speedVariation = Math.sin(timeSeconds * 0.1) * 15.0;
        flightSpeed = baseSpeed + speedVariation + (random.nextDouble() - 0.5) * 5.0;
        flightSpeed = Math.max(180, Math.min(320, flightSpeed));
    }
    
    // Process obstacles to determine if we need to climb
    private void processObstacles() {
        // Simplified obstacle processing - now most is in AircraftPanel
        thunderstormAhead = random.nextInt(100) < 5; // 5% chance of thunderstorm
        
        // Make decisions based on obstacles
        if (thunderstormAhead) {
            targetAltitude = 30000; // Climb to avoid thunderstorm
            
            // Update decision text
            int direction = targetAltitude > currentAltitude ? 1 : -1;
            if (Math.abs(targetAltitude - currentAltitude) > 200) {
                isClimbingDecision = true;
                
                // Update decision text
                if (direction > 0) {
                    currentDecision = "CLIMBING";
                } else {
                    currentDecision = "DESCENDING";
                }
            } else {
                currentDecision = "LEVEL FLIGHT";
                isClimbingDecision = false;
            }
        } else {
            // No obstacles, return to cruising altitude
            targetAltitude = 11000;
            currentDecision = "LEVEL FLIGHT";
            isClimbingDecision = false;
        }
    }
    
    // Update turbulence based on proximity to clouds and thunderstorms
    private void updateTurbulence() {
        // Generate realistic turbulence that varies over time
        if (thunderstormAhead) {
            // Severe turbulence near thunderstorms
            turbulenceFactor = 10.0 + random.nextDouble() * 15.0;
            
            // Update decision text for severe turbulence
            synchronized (aircraftLock) {
                if (thunderstormAhead && Math.abs(turbulenceFactor) > 10) {
                    currentDecision = "TURBULENCE!";
                }
            }
        } else {
            // Light to moderate turbulence normally
            turbulenceFactor = Math.max(0, turbulenceFactor * 0.95 - 0.5);
            if (random.nextInt(100) < 5) { // Occasional random turbulence
                turbulenceFactor = random.nextDouble() * 7.0;
            }
        }
    }
    
    // Performance overlay is now handled by AircraftPanel.drawEnhancedPerformanceOverlay()
    // This method was removed to prevent duplicate overlays
    
    // Method to create and display the GUI
    private void createAndShowGUI() {
        frame = new JFrame("Aircraft Simulation with Performance Monitoring");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        
        // Add key listener to toggle performance overlay with 'P' key
        KeyListener keyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_P) {
                    showPerformanceOverlay = !showPerformanceOverlay;
                    System.out.println("Performance overlay: " + 
                                      (showPerformanceOverlay ? "ON" : "OFF"));
                }
            }
        };
        
        // Initialize our AircraftPanel
        panel = new AircraftPanel();
        panel.setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));
        
        // Make sure panel can receive key events
        panel.setFocusable(true);
        panel.addKeyListener(keyListener);
        
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null); // Center on screen
        frame.setVisible(true);
        
        // Request focus for key events
        panel.requestFocusInWindow();
        
        // Also add key listener to frame for redundancy
        frame.addKeyListener(keyListener);
    }
    
    // Shutdown method to clean up threads and log performance statistics
    public void shutdown() {
        running = false;
        System.out.println("Shutting down aircraft simulation...");
        
        if (timer != null) {
            timer.stop();
        }

        rollControl.removeListener(attitudeListener);
        pitchControl.removeListener(attitudeListener);
        yawControl.removeListener(attitudeListener);
        
        // Shutdown thread pools
        scheduler.shutdown();
        executor.shutdown();
        
        try {
            // Wait for tasks to complete
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Print performance statistics
        System.out.println("\n==== THREAD PERFORMANCE STATISTICS ====");
        System.out.println("Total simulation time: " + 
                          ((System.currentTimeMillis() - monitoringStartTime) / 1000.0) + " seconds");
        
        for (int i = 0; i < threadNames.length; i++) {
            if (threadExecutionCounts[i] > 0) {
                double avgExecTimeMs = (threadExecutionTimes[i] / (double)threadExecutionCounts[i]) / 1_000_000.0;
                System.out.println(threadNames[i] + " Thread:" +
                                  "\n  Total calls: " + threadExecutionCounts[i] +
                                  "\n  Average execution time: " + df.format(avgExecTimeMs) + "ms" +
                                  "\n  Total time: " + df.format(threadExecutionTimes[i] / 1_000_000_000.0) + "s" +
                                  "\n  CPU %: " + df.format((threadExecutionTimes[i] / 1_000_000.0) / 
                                             (System.currentTimeMillis() - monitoringStartTime) * 100) + "%");
            }
        }
        System.out.println("====================================\n");
    }
    
    // Static method to create and return a GUI update thread
    public static Thread createGUIUpdateThread(AircraftGUI gui, AtomicBoolean running) {
        return new Thread(() -> {
            try {
                // Keep running until signaled to stop
                while (running.get()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                gui.shutdown();
            }
        }, "GUI-Update");
    }
    
    // Quit action to be called when user wants to exit
    private Runnable quitAction;
    
    /**
     * Set action to execute when user quits the simulation
     * @param action The action to run when quit is requested
     */
    public void setQuitAction(Runnable action) {
        this.quitAction = action;
    }
    
    /**
     * Execute the quit action if defined
     */
    public void executeQuitAction() {
        if (quitAction != null) {
            quitAction.run();
        }
    }
}

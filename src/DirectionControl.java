/*
 * Copyright (C) 2025 Shivaji Patil, College of the North Atlantic
 * All rights reserved.
 *
 * Aircraft Simulation Project
 */

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Direction control system for an aircraft axis. Manages a current value and a
 * target value, and adjusts the current value over time toward the target
 * using a physics-based movement (inertia, dampening, tolerance, max step).
 */
public class DirectionControl {
    private String name;
    private double currentValue;
    private double targetValue;
    private double velocity;
    private double min;
    private double max;
    private double inertia;
    private double dampening;
    private double tolerance;
    private double maxStep;
    private final CopyOnWriteArrayList<DirectionControlListener> listeners = new CopyOnWriteArrayList<>();

    // Statistics tracking
    private double totalDeviation = 0;
    private double maxDeviation = 0;
    private int sampleCount = 0;
    private boolean trackStatistics = true;

    // Getters for correction mechanism display
    public String getName() { return name; }
    public double getInertia() { return inertia; }
    public double getDampening() { return dampening; }
    public double getTolerance() { return tolerance; }
    public double getVelocity() { return velocity; }

    // Protected setters so subclasses can override physics parameters
    protected void setInertia(double inertia) { this.inertia = inertia; }
    protected void setDampening(double dampening) { this.dampening = dampening; }
    protected void setTolerance(double tolerance) { this.tolerance = tolerance; }

    public DirectionControl(String name, double min, double max, ConfigLoader config) {
        this.name = name;
        this.min = min;
        this.max = max;

        // Read configuration values with defaults if not specified
        this.inertia = config.getDouble(name.toLowerCase() + ".inertia", 1.0);
        this.dampening = config.getDouble(name.toLowerCase() + ".dampening", 0.95);
        this.tolerance = config.getDouble(name.toLowerCase() + ".tolerance", 2.0);
        this.maxStep = config.getDouble(name.toLowerCase() + ".maxStep", 3.0);

        this.currentValue = 0;
        this.targetValue = 0;
        this.velocity = 0;
    }

    /**
     * Update the current value based on the physics model and target.
     */
    public synchronized void update() {
        double previousValue = currentValue;
        double deviation = targetValue - currentValue;

        if (trackStatistics) {
            totalDeviation += Math.abs(deviation);
            maxDeviation = Math.max(maxDeviation, Math.abs(deviation));
            sampleCount++;
        }

        Main.logToCSV(name, targetValue, currentValue, velocity);

        // Skip adjustment if we're already close enough.
        if (Math.abs(deviation) < tolerance && Math.abs(velocity) < 0.1) {
            velocity = 0;
            return;
        }

        velocity += deviation / inertia;
        velocity *= dampening;

        if (velocity > maxStep) velocity = maxStep;
        if (velocity < -maxStep) velocity = -maxStep;

        currentValue += velocity;

        if (currentValue < min) {
            currentValue = min;
            velocity = 0;
        } else if (currentValue > max) {
            currentValue = max;
            velocity = 0;
        }

        if (Double.compare(previousValue, currentValue) != 0) {
            notifyListeners();
        }
    }

    /**
     * Registers a listener that will be notified when the control value changes.
     */
    public void addListener(DirectionControlListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    /**
     * Removes a previously registered listener.
     */
    public void removeListener(DirectionControlListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        for (DirectionControlListener listener : listeners) {
            listener.onDirectionChanged(this);
        }
    }

    public Map<String, Double> getStatistics() {
        Map<String, Double> stats = new HashMap<>();
        stats.put("sampleCount", (double) sampleCount);
        stats.put("averageDeviation", sampleCount > 0 ? totalDeviation / sampleCount : 0);
        stats.put("maxDeviation", maxDeviation);
        return stats;
    }

    public synchronized double getCurrentValue() { return currentValue; }
    public synchronized void setCurrentValue(double value) { this.currentValue = value; }

    public synchronized double getTargetValue() { return targetValue; }
    public synchronized void setTargetValue(double value) {
        if (value < min) value = min;
        if (value > max) value = max;
        this.targetValue = value;
    }
}

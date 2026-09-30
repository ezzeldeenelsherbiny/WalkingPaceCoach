package com.openai.walkingpacecoach.logic

import org.junit.Assert.*
import org.junit.Test

class AlertStateMachineTest {
    private val config = TrackingConfig(targetSpeedKmh = 2.0, firstWarningDelaySeconds = 4,
        warningIntervalSeconds = 5, stationaryPauseSeconds = 10)
    @Test fun slowdownRepeatsAndRecoveryClearsAlerts() {
        val machine = AlertStateMachine()
        assertFalse(machine.update(0, 1.4, config, false).shouldVibrate)
        assertFalse(machine.update(3999, 1.4, config, false).shouldVibrate)
        assertTrue(machine.update(4000, 1.4, config, false).shouldVibrate)
        assertFalse(machine.update(8999, 1.4, config, false).shouldVibrate)
        assertTrue(machine.update(9000, 1.4, config, false).shouldVibrate)
        assertEquals(AlertState.ON_TARGET, machine.update(10000, 2.3, config, false).state)
        assertFalse(machine.update(11000, 1.4, config, false).shouldVibrate)
    }
    @Test fun missingGpsAndManualPauseSuppressWarnings() {
        val machine = AlertStateMachine()
        machine.update(0, 1.4, config, false)
        assertTrue(machine.update(4000, 1.4, config, false).shouldVibrate)
        assertEquals(AlertState.WAITING_FOR_GPS, machine.update(5000, null, config, false).state)
        assertFalse(machine.update(6000, 1.4, config, false).shouldVibrate)
        assertEquals(AlertState.PAUSED, machine.update(10000, 1.4, config, true).state)
        assertFalse(machine.update(11000, 1.4, config, false).shouldVibrate)
    }
    @Test fun stoppingSuppressesAlertsUnlessExplicitlyEnabled() {
        val machine = AlertStateMachine()
        machine.update(0, 0.0, config, false)
        val stopped = machine.update(10000, 0.0, config, false)
        assertTrue(stopped.autoSuppressedForStop)
        assertFalse(stopped.shouldVibrate)
        assertFalse(machine.update(11000, 0.0, config.copy(alertWhileStopped = true), false).shouldVibrate)
        assertTrue(machine.update(15000, 0.0, config.copy(alertWhileStopped = true), false).shouldVibrate)
    }
}

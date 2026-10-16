/*
 *  Ring Alarm Contact Sensor G2 - Reliable (Hubitat driver)
 *  Version 1.2.0  (2026-10-16)
 *
 *  Derived from "Ring Alarm Contact Sensor G2 - Advanced Driver" v0.1.4 by Mike Dam (MikeNYC88)
 *    https://github.com/mikenyc88/Hubitat   |   https://community.hubitat.com/t/149189
 *  Licensed under the GNU Affero General Public License v3.0, the same license as the original.
 *  Original copyright and license notices are preserved; this file is a modified version.
 *
 *  1.2.0 (validated on hardware: firmware 1.40, Z-Wave 7.17, classic S2 inclusion)
 *   - Confirmed: a 0 ms confirmation delay freezes the sensor under rapid open/close; 1000 ms is
 *     stable. Setting text now says so. Confirmed: Long Range pairing left the sensor unable to accept
 *     confirmations (endless re-sends), so classic inclusion is recommended.
 *   - Messages corrected: the sensor's two CR2032 cells are in parallel, so BOTH must be removed to
 *     restart it and deliver settings immediately.
 *
 *  1.1.1
 *   - The front button does NOT wake this sensor (confirmed from hub logs); it only sends a comm-test
 *     notification. Inserting a battery ("power applied") is now treated as a wake-up so queued
 *     settings can be delivered immediately. User-facing messages updated accordingly.
 *   - A second wake-up within 10 s of the last command burst is ignored (power-on may send both).
 *
 *  1.1.0 (after adversarial review)
 *   - Supervision Report is always sent, even if an event handler throws.
 *   - Re-sync replies are matched by counting expected answers (60 s fallback), not by a timing guess.
 *     Unsupervised reports can never force an event. Bare "idle" replies are mapped to the query
 *     they answer.
 *   - Same-state duplicate window is derived from the retry settings (30-180 s). Behaviour on a
 *     repeated same-state report is a preference. Duplicate cache is cleared when the sensor resets.
 *   - A supervised wake-up defers its command burst until after the delayed Supervision Report.
 *   - The sensor is put back to sleep once every expected reply has arrived (with a timeout fallback),
 *     instead of a fixed 1.5 s after the last command.
 *   - A setting the sensor refuses is retried at most 3 times, then accepted and reported.
 *   - Switching from another driver self-initialises (counters, health timer, logging, full re-sync).
 *     Initialize capability re-arms the health timer at hub start-up.
 *   - lastCheckin moved to a device data value; health timer is re-armed at most once a minute.
 *
 *  1.0.0
 *   - S2-encrypted, delayed Supervision Report, capped below the sensor's actual timeout.
 *   - Duplicate filtering by supervision session; no forced events from wake-up polling.
 *   - Settings queued until the next wake-up; configStatus attribute.
 *   - Wake Up No More Information after each wake-up; no fabricated values on install.
 *   - healthStatus, suspectedMissedEvents, magnetic interference and system fault reporting.
 *   - Debug logging auto-off after 30 minutes.
 */

import groovy.transform.Field
import java.util.concurrent.ConcurrentHashMap

metadata {
    definition(name: "Ring Alarm Contact Sensor G2 - Reliable", namespace: "local", author: "Mike Dam (original), modified 2026", singleThreaded: true) {
        capability "Sensor"
        capability "Actuator"
        capability "ContactSensor"
        capability "TamperAlert"
        capability "Battery"
        capability "PushableButton"
        capability "Configuration"
        capability "Refresh"
        capability "Initialize"

        attribute "healthStatus", "ENUM", ["online", "offline"]
        attribute "magneticInterference", "ENUM", ["clear", "detected"]
        attribute "configStatus", "STRING"
        attribute "suspectedMissedEvents", "NUMBER"
        attribute "lastSystemEvent", "STRING"

        // configure: re-sends every setting at the next wake-up. refresh: re-reads state at the next wake-up.
        command "clearTamper"
        command "resetCounters"

        fingerprint mfr: "0346", prod: "0201", deviceId: "0301", inClusters: "0x5E,0x6C,0x55,0x9F,0x59,0x85,0x80,0x70,0x5A,0x7A,0x87,0x72,0x8E,0x71,0x73,0x86,0x84", outClusters: "0x80,0x70,0x72,0x71,0x6C,0x86,0x84", deviceJoinName: "Ring Alarm Contact Sensor G2"
        fingerprint mfr: "0346", prod: "0201", deviceId: "0401", inClusters: "0x5E,0x6C,0x55,0x9F,0x59,0x85,0x80,0x70,0x5A,0x7A,0x87,0x72,0x8E,0x71,0x73,0x86,0x84", outClusters: "0x80,0x70,0x72,0x71,0x6C,0x86,0x84", deviceJoinName: "Ring Alarm Contact Sensor G2"
        fingerprint mfr: "0346", prod: "0201", deviceId: "0601", inClusters: "0x5E,0x6C,0x55,0x9F,0x59,0x85,0x80,0x70,0x5A,0x7A,0x87,0x72,0x8E,0x71,0x73,0x86,0x84", outClusters: "0x80,0x70,0x72,0x71,0x6C,0x86,0x84", deviceJoinName: "Ring Alarm Contact Sensor G2"
    }

    preferences {
        input name: "wakeUpHours", type: "enum", title: "Wake-up interval",
            description: "How often the sensor checks in to receive settings and re-sync its state. Shorter means missed events are corrected sooner, at a small battery cost.",
            options: [["1": "1 hour"], ["2": "2 hours"], ["4": "4 hours"], ["6": "6 hours"], ["12": "12 hours"], ["24": "24 hours"]], defaultValue: "4"
        input name: "heartbeatMinutes", type: "number", title: "Heartbeat interval (minutes, 1-70)",
            description: "The sensor sends a battery report this long after its last message. Also drives offline detection (3 missed heartbeats).",
            range: "1..70", defaultValue: 70
        input name: "retryCount", type: "enum", title: "Message retries",
            description: "How many times the sensor re-sends a message the hub didn't confirm. Duplicates are filtered by this driver. (Sensor default is 1; 2 costs a little more airtime only when a confirmation is lost.)",
            options: [["0": "0"], ["1": "1 (sensor default)"], ["2": "2"], ["3": "3"], ["4": "4"], ["5": "5"]], defaultValue: "2"
        input name: "retryWaitSecs", type: "number", title: "Base wait between retries (seconds, 1-60)", range: "1..60", defaultValue: 5
        input name: "ledMode", type: "enum", title: "LED indicator",
            description: "'On open and close' is useful while testing: a green flash about 1 s after each change means the hub's confirmation was accepted.",
            options: [["0": "Off"], ["1": "On open (sensor default)"], ["2": "On open and close"]], defaultValue: "1"
        input name: "supervisionTimeoutMs", type: "number", title: "Sensor's supervision timeout (ms, 500-5000)",
            description: "How long the sensor waits for the hub's confirmation before retrying. Kept high so the delayed confirmation below always arrives in time.",
            range: "500..5000", defaultValue: 5000
        input name: "supervisionDelayMs", type: "number", title: "Confirmation delay (ms, 0-4000)",
            description: "Delay before the hub confirms each event. Required: at 0 ms the sensor freezes under rapid open/close (firmware 1.40 / Z-Wave 7.17); 1000 ms is tested and stable. Events reach the hub immediately; only the LED feedback and the sensor's pacing (about 1 message per second) follow this delay. Capped below the sensor's current timeout (900 ms until the 5000 ms setting is confirmed).",
            range: "0..4000", defaultValue: 1000
        input name: "repeatedState", type: "enum", title: "When the sensor reports the same state twice",
            description: "This usually means the opposite event was missed (for example, a close never arrived and the door was opened again). Only confirmed, non-duplicate reports qualify.",
            options: [["event": "Record it as a new event (best for automations)"], ["ignore": "Ignore it (history strictly alternates open/closed)"]], defaultValue: "event"
        input name: "resyncOnWake", type: "bool", title: "Re-sync contact and tamper state at each wake-up", defaultValue: true
        input name: "buttonEvents", type: "bool", title: "Report the front (comm test) button as Button 1 pushed", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        input name: "logEnable", type: "bool", title: "Enable debug logging (turns off after 30 minutes)", defaultValue: true
    }
}

// ---------------------------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------------------------

@Field static final String DRIVER_VERSION = "1.2.0"

// Configuration parameters from the Ring technical manual / Z-Wave JS device database.
@Field static final Map CONFIG_PARAMS = [
    1: [setting: "heartbeatMinutes",     size: 1, label: "heartbeat",           defaultValue: 70],
    2: [setting: "retryCount",           size: 1, label: "retries",             defaultValue: 2],
    3: [setting: "retryWaitSecs",        size: 1, label: "retry wait",          defaultValue: 5],
    4: [setting: "ledMode",              size: 1, label: "LED",                 defaultValue: 1],
    6: [setting: "supervisionTimeoutMs", size: 2, label: "supervision timeout", defaultValue: 5000]
]

@Field static final Map CMD_CLASS_VERS = [
    0x20: 1, // Basic
    0x5E: 2, // Z-Wave Plus Info
    0x80: 2, // Battery
    0x70: 4, // Configuration
    0x5A: 1, // Device Reset Locally
    0x7A: 5, // Firmware Update Meta Data
    0x87: 3, // Indicator
    0x72: 2, // Manufacturer Specific
    0x8E: 3, // Multi Channel Association
    0x71: 8, // Notification
    0x73: 1, // Powerlevel
    0x9F: 1, // Security 2
    0x6C: 1, // Supervision
    0x55: 2, // Transport Service
    0x86: 3, // Version
    0x84: 2, // Wake Up
    0x59: 3, // Association Group Info
    0x85: 2  // Association
]

@Field static final Map SYSTEM_FAULTS = [
    0x55: "System software failure",
    0xAA: "Software fault (Ring)",
    0xA9: "Software fault (SDK)",
    0xAB: "Pin reset (soft reset)",
    0xAC: "Software reset (not triggered by a failure)",
    0xAD: "Dropped frame"
]

@Field static final long SUPERVISION_DEDUP_WINDOW_MS = 60000L  // same session + same bytes inside this = retransmission
@Field static final long RESYNC_FALLBACK_MS = 60000L           // stop expecting re-sync replies after this
@Field static final int  DEVICE_DEFAULT_SUP_TIMEOUT_MS = 1500  // sensor default before our setting lands
@Field static final int  SUP_DELAY_MARGIN_MS = 600             // headroom kept below the sensor's timeout
@Field static final int  MAX_CONFIG_TRIES = 3                  // give up re-sending a refused setting after this

// Per-device cache of recently seen supervision sessions (in memory; cleared on hub reboot, which is fine).
@Field static final ConcurrentHashMap SUP_CACHE = new ConcurrentHashMap()

// ---------------------------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------------------------

void installed() {
    logInfo("Installed")
    state.clear()
    ensureInitialized()
    // The sensor stays awake briefly after inclusion, so try to deliver settings right away.
    // Anything that doesn't land is re-sent at the next wake-up.
    runIn(2, "sendInstallBurst")
}

void sendInstallBurst() {
    wakeBurst(false)
}

void initialize() {
    // Called at hub start-up (Initialize capability).
    ensureInitialized()
    scheduleHealthCheck()
}

void updated() {
    logInfo("Preferences saved")
    ensureInitialized()
    unschedule("logsOff")
    if (logEnable) runIn(1800, "logsOff")

    Integer delay = settingInt("supervisionDelayMs", 1000)
    Integer timeout = desiredParam(6)
    if (delay > 0 && delay > timeout - SUP_DELAY_MARGIN_MS) {
        Integer fixed = Math.max(0, timeout - SUP_DELAY_MARGIN_MS)
        logWarn("Confirmation delay ${delay} ms is too close to the ${timeout} ms timeout; using ${fixed} ms")
        device.updateSetting("supervisionDelayMs", [value: fixed, type: "number"])
    }

    state.cfgTries = [:]       // new desired values deserve fresh attempts
    state.wakeTries = 0
    scheduleHealthCheck()
    updateConfigStatus()
    if (hasPendingConfig()) {
        logInfo("New settings will be applied at the sensor's next wake-up. To apply now, remove both batteries for 15 s and reinsert them.")
    }
}

void configure() {
    logInfo("Configure: every setting will be re-sent at the next wake-up. To apply now, remove both batteries for 15 s and reinsert them.")
    ensureInitialized()
    state.cfg = [:]
    state.cfgTries = [:]
    state.wakeIntervalReported = null
    state.wakeTries = 0
    state.refreshPending = true
    scheduleHealthCheck()
    updateConfigStatus()
}

void refresh() {
    state.refreshPending = true
    logInfo("Refresh queued for the next wake-up. To run it now, remove both batteries for 15 s and reinsert them.")
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging disabled"
}

// Idempotent set-up. Also covers switching an existing device to this driver, where neither
// installed() nor updated() runs until the user saves preferences.
private void ensureInitialized() {
    if (state.initVersion == DRIVER_VERSION) return
    boolean firstRun = state.initVersion == null
    if (state.cfg == null) state.cfg = [:]
    if (state.cfgTries == null) state.cfgTries = [:]
    if (state.duplicatesDropped == null) state.duplicatesDropped = 0
    if (device.currentValue("numberOfButtons") == null) sendEvent(name: "numberOfButtons", value: 1)
    if (device.currentValue("suspectedMissedEvents") == null) sendEvent(name: "suspectedMissedEvents", value: 0)
    if (device.currentValue("healthStatus") == null) sendEvent(name: "healthStatus", value: "online")
    if (settings.logEnable == null) {
        device.updateSetting("logEnable", [value: "true", type: "bool"])
        runIn(1800, "logsOff")
    }
    if (firstRun) {
        // Data left by a previous driver can't be trusted: re-read everything at the next wake-up.
        state.refreshPending = true
        state.cfg = [:]
        state.wakeIntervalReported = null
    }
    state.initVersion = DRIVER_VERSION
    scheduleHealthCheck()
}

// ---------------------------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------------------------

void push(buttonNumber = 1) {
    sendEvent(name: "pushed", value: 1, isStateChange: true, type: "digital",
              descriptionText: "${device.displayName} button 1 was pushed (digital)")
}

void clearTamper() {
    sendEvent(name: "tamper", value: "clear", type: "digital", descriptionText: "${device.displayName} tamper cleared manually")
    logInfo("Tamper cleared manually")
}

void resetCounters() {
    state.duplicatesDropped = 0
    sendEvent(name: "suspectedMissedEvents", value: 0)
    logInfo("Counters reset")
}

// ---------------------------------------------------------------------------------------------
// Parsing and Z-Wave events
// ---------------------------------------------------------------------------------------------

void parse(String description) {
    ensureInitialized()
    hubitat.zwave.Command cmd = zwave.parse(description, CMD_CLASS_VERS)
    if (cmd) {
        markAlive()
        logDebug("Parsed: ${cmd}")
        zwaveEvent(cmd)
    } else {
        logDebug("Could not parse: ${description}")
    }
}

// Supervised messages: process once, and ALWAYS confirm (duplicates and handler failures included),
// so the sensor never retries because of a bug on the hub side.
void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd, boolean supervised = false) {
    hubitat.zwave.Command inner = null
    try {
        inner = cmd.encapsulatedCommand(CMD_CLASS_VERS)
        String key = "${cmd.sessionID}|${inner ? inner.format() : 'none'}".toString()
        if (isDuplicateSupervision(key)) {
            state.duplicatesDropped = ((state.duplicatesDropped ?: 0) as Integer) + 1
            logDebug("Dropped retransmitted message (session ${cmd.sessionID}): ${inner}")
        } else if (inner) {
            zwaveEvent(inner, true)
        }
    } catch (e) {
        logWarn("Handler failed for ${inner ?: cmd}: ${e}")
    } finally {
        sendSupervisionReport(cmd.sessionID as Integer)
    }
}

void zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd, boolean supervised = false) {
    // S0 only; kept for completeness.
    hubitat.zwave.Command inner = cmd.encapsulatedCommand(CMD_CLASS_VERS)
    if (inner) zwaveEvent(inner, supervised)
}

void zwaveEvent(hubitat.zwave.commands.notificationv8.NotificationReport cmd, boolean supervised = false) {
    Integer type = cmd.notificationType as Integer
    Integer evt = cmd.event as Integer
    Integer param0 = (cmd.eventParametersLength && cmd.eventParameter) ? (cmd.eventParameter[0] as Integer) : null
    logDebug("Notification type ${type}, event ${evt}, param ${param0}, supervised ${supervised}")

    switch (type) {
        case 0x07: handleHomeSecurity(evt, param0, supervised); break
        case 0x08: handlePowerManagement(evt, supervised); break
        case 0x09: handleSystem(evt, param0); break
        default:   logDebug("Unhandled notification type ${type}"); break
    }
}

void zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd, boolean supervised = false) {
    logDebug("Wake-up notification")
    startWakeSession(supervised)
}

private void startWakeSession(boolean supervised) {
    device.updateDataValue("lastWakeUp", formatNow())
    Long lastBurst = state.lastBurstAt as Long
    if (lastBurst != null && now() - lastBurst < 10000L) {
        logDebug("Wake-up within 10 s of the last burst; ignored")
        return
    }
    state.lastBurstAt = now()
    if (supervised) {
        // Let the delayed Supervision Report go out first, then send the burst.
        Integer wait = effectiveSupervisionDelay() + 300
        logDebug("Supervised wake-up: burst in ${wait} ms")
        runInMillis(wait, "deferredWakeBurst")
    } else {
        wakeBurst(true)
    }
}

void deferredWakeBurst() {
    wakeBurst(true)
}

void zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd, boolean supervised = false) {
    Integer secs = cmd.seconds as Integer
    state.wakeIntervalReported = secs
    if (secs == desiredWakeSeconds()) {
        state.wakeTries = 0
        logInfo("Wake-up interval is ${secs} s")
    } else {
        logWarn("Wake-up interval reported ${secs} s, wanted ${desiredWakeSeconds()} s")
    }
    updateConfigStatus()
    replyReceived()
}

void zwaveEvent(hubitat.zwave.commands.configurationv4.ConfigurationReport cmd, boolean supervised = false) {
    Integer p = cmd.parameterNumber as Integer
    Integer v = cmd.scaledConfigurationValue as Integer
    Map c = (state.cfg ?: [:]) as Map
    c[p.toString()] = v
    state.cfg = c
    Map meta = CONFIG_PARAMS[p]
    if (meta) {
        Integer want = desiredParam(p)
        if (v == want) {
            setTries(p, 0)
            logDebug("Parameter ${p} (${meta.label}) = ${v}")
        } else if (getTries(p) >= MAX_CONFIG_TRIES) {
            logWarn("Parameter ${p} (${meta.label}): sensor keeps ${v} instead of ${want}; accepting its value")
        } else {
            logWarn("Parameter ${p} (${meta.label}) reported ${v}, wanted ${want}; will retry at next wake-up")
        }
    }
    updateConfigStatus()
    replyReceived()
}

void zwaveEvent(hubitat.zwave.commands.batteryv2.BatteryReport cmd, boolean supervised = false) {
    Integer level = cmd.batteryLevel as Integer
    if (level == 0xFF) {
        sendEvent(name: "battery", value: 1, unit: "%", descriptionText: "${device.displayName} battery is low")
        logWarn("Battery low")
    } else {
        level = Math.max(0, Math.min(100, level))
        sendEvent(name: "battery", value: level, unit: "%", descriptionText: "${device.displayName} battery is ${level}%")
        logDebug("Battery ${level}%")
    }
    replyReceived()
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd, boolean supervised = false) {
    String fw = "${cmd.firmware0Version}.${cmd.firmware0SubVersion}".toString()
    String proto = "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}".toString()
    device.updateDataValue("firmwareVersion", fw)
    device.updateDataValue("protocolVersion", proto)
    device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}".toString())
    logInfo("Firmware ${fw}, Z-Wave protocol ${proto}, hardware ${cmd.hardwareVersion}")
    replyReceived()
}

void zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.DeviceSpecificReport cmd, boolean supervised = false) {
    if ((cmd.deviceIdType as Integer) == 1) {
        String serial = ""
        if ((cmd.deviceIdDataFormat as Integer) == 1) {
            cmd.deviceIdData.each { serial += hubitat.helper.HexUtils.integerToHexString((it as Integer) & 0xFF, 1).padLeft(2, "0") }
        } else {
            cmd.deviceIdData.each { serial += (char) (it as Integer) }
        }
        device.updateDataValue("serialNumber", serial)
    }
    replyReceived()
}

void zwaveEvent(hubitat.zwave.commands.deviceresetlocallyv1.DeviceResetLocallyNotification cmd, boolean supervised = false) {
    String msg = "Sensor was factory reset and has left the network; exclude and re-include it"
    sendEvent(name: "lastSystemEvent", value: msg)
    sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName}: ${msg}")
    logWarn(msg)
}

void zwaveEvent(hubitat.zwave.Command cmd, boolean supervised = false) {
    logDebug("Ignored: ${cmd}")
}

// ---------------------------------------------------------------------------------------------
// Notification handlers
// ---------------------------------------------------------------------------------------------

private void handleHomeSecurity(Integer evt, Integer param0, boolean supervised) {
    if (evt == 0x0B) { handleMagnetic("detected"); return }
    if (evt == 0x00 && param0 == 0x0B) { handleMagnetic("clear"); return }
    if (evt == 0xFE) { logDebug("Home Security state unknown"); return }

    String target = null   // "contact" or "tamper"
    String value = null
    if (evt == 0x02) { target = "contact"; value = "open" }
    else if (evt == 0x03) { target = "tamper"; value = "detected" }
    else if (evt == 0x00 && param0 == 0x02) { target = "contact"; value = "closed" }
    else if (evt == 0x00 && param0 == 0x03) { target = "tamper"; value = "clear" }

    boolean resync = !supervised && resyncActive()

    if (evt == 0x00 && param0 == null) {
        // A bare "idle" carries no event id. During a re-sync it answers the oldest open query.
        if (!resync) { logDebug("Home Security idle without parameter (not a re-sync reply); ignored"); return }
        target = (state.resyncQueue as List)[0] as String
        value = target == "contact" ? "closed" : "clear"
        logDebug("Bare idle reply mapped to ${target} ${value}")
    }

    if (target == null) { logDebug("Unhandled Home Security event ${evt}"); return }

    if (resync) {
        consumeResync(target)
        replyReceived()
    }
    if (target == "contact") handleContact(value, supervised, resync)
    else handleTamper(value, resync)
}

private void handleContact(String value, boolean supervised, boolean resync) {
    String current = device.currentValue("contact", true)

    if (resync) {
        if (current == null) {
            sendEvent(name: "contact", value: value, descriptionText: "${device.displayName} is ${value} (initial state)")
            state.lastContactEventAt = now()
        } else if (current != value) {
            incrementMissed()
            sendEvent(name: "contact", value: value,
                      descriptionText: "${device.displayName} is ${value} (corrected on re-sync; an event was missed)")
            logWarn("Re-sync corrected contact ${current} -> ${value}")
            state.lastContactEventAt = now()
        } else {
            logDebug("Re-sync: contact still ${value}")
        }
        return
    }

    if (current != value) {
        sendEvent(name: "contact", value: value, type: "physical", descriptionText: "${device.displayName} is ${value}")
        state.lastContactEventAt = now()
        logInfo("Contact ${value}")
        return
    }

    // Same state as the hub already has.
    Long last = state.lastContactEventAt as Long
    if (last != null && now() - last < sameStateWindowMs()) {
        state.duplicatesDropped = ((state.duplicatesDropped ?: 0) as Integer) + 1
        logDebug("Dropped duplicate contact ${value}")
        return
    }
    incrementMissed()
    String missedWhat = value == "open" ? "close" : "open"
    if (supervised && (settings.repeatedState ?: "event") == "event") {
        logWarn("Received ${value} while already ${value}; the previous ${missedWhat} was likely missed. Recording a new ${value} event.")
        sendEvent(name: "contact", value: value, isStateChange: true, type: "physical",
                  descriptionText: "${device.displayName} is ${value}")
        state.lastContactEventAt = now()
    } else {
        logWarn("Received ${value} while already ${value}; the previous ${missedWhat} was likely missed (not recorded${supervised ? '' : ': unconfirmed report'})")
    }
}

private void handleTamper(String value, boolean resync) {
    String current = device.currentValue("tamper", true)
    if (current == value) {
        logDebug("Tamper already ${value}")
        return
    }
    if (resync && current != null) incrementMissed()
    String desc = value == "detected" ? "${device.displayName} tamper detected (cover removed)" : "${device.displayName} tamper cleared (cover on)"
    if (resync && current != null) desc += " (corrected on re-sync)"
    Map evt = [name: "tamper", value: value, descriptionText: desc]
    if (!resync) evt.type = "physical"
    sendEvent(evt)
    logInfo(desc)
}

private void handleMagnetic(String value) {
    if (device.currentValue("magneticInterference", true) == value) return
    sendEvent(name: "magneticInterference", value: value, descriptionText: "${device.displayName} magnetic interference ${value}")
    if (value == "detected") logWarn("Magnetic field interference detected; contact readings may be unreliable")
    else logInfo("Magnetic interference cleared")
}

private void handlePowerManagement(Integer evt, boolean supervised) {
    switch (evt) {
        case 0x01:
            logInfo("Power applied (batteries inserted); the sensor is awake, delivering queued settings")
            clearSupervisionCache()   // the sensor's session counter may restart
            startWakeSession(supervised)
            break
        case 0x05: logWarn("Battery voltage drop detected"); break
        case 0x0A: logWarn("Replace battery soon"); break
        case 0x0B: logWarn("Replace battery now"); break
        default:   logDebug("Power management event ${evt}"); break
    }
}

private void handleSystem(Integer evt, Integer param0) {
    switch (evt) {
        case 0x05:   // Ring: "Comm Test Button Pressed" (spec name: Heartbeat)
            if (settings.buttonEvents != false) {
                sendEvent(name: "pushed", value: 1, isStateChange: true, type: "physical",
                          descriptionText: "${device.displayName} button 1 was pushed")
                logInfo("Button pushed")
            }
            break
        case 0x04:
            String desc = param0 == null ? "System event (no code)" : (SYSTEM_FAULTS[param0] ?: "System event (code ${param0})")
            sendEvent(name: "lastSystemEvent", value: "${desc} at ${formatNow()}".toString())
            logWarn(desc)
            if (param0 == 0xAB || param0 == 0xAC || param0 == 0x55 || param0 == 0xAA || param0 == 0xA9) clearSupervisionCache()
            break
        default:
            logDebug("System event ${evt}, param ${param0}")
            break
    }
}

// ---------------------------------------------------------------------------------------------
// Wake-up handling: deliver queued settings, re-sync state, sleep once all replies are in
// ---------------------------------------------------------------------------------------------

private void wakeBurst(boolean sleepAfter) {
    List<String> cmds = []
    int expected = 0

    pendingParams().each { Integer p ->
        Map meta = CONFIG_PARAMS[p]
        cmds << zwave.configurationV4.configurationSet(parameterNumber: p, size: meta.size as Integer, scaledConfigurationValue: desiredParam(p)).format()
        cmds << zwave.configurationV4.configurationGet(parameterNumber: p).format()
        setTries(p, getTries(p) + 1)
        expected++
    }

    if (wakeIntervalPending()) {
        cmds << zwave.wakeUpV2.wakeUpIntervalSet(seconds: desiredWakeSeconds(), nodeid: getZwaveHubNodeId()).format()
        cmds << zwave.wakeUpV2.wakeUpIntervalGet().format()
        state.wakeTries = ((state.wakeTries ?: 0) as Integer) + 1
        expected++
    }

    boolean refreshing = state.refreshPending == true
    if (refreshing || !device.getDataValue("firmwareVersion")) {
        cmds << zwave.versionV3.versionGet().format()
        cmds << zwave.manufacturerSpecificV2.deviceSpecificGet(deviceIdType: 1).format()
        expected += 2
    }
    if (refreshing || device.currentValue("battery") == null) {
        cmds << zwave.batteryV2.batteryGet().format()
        expected++
    }
    if (refreshing || settings.resyncOnWake != false) {
        cmds << zwave.notificationV8.notificationGet(notificationType: 0x07, event: 0x02).format()
        cmds << zwave.notificationV8.notificationGet(notificationType: 0x07, event: 0x03).format()
        state.resyncQueue = ["contact", "tamper"]
        state.resyncExpiresAt = now() + RESYNC_FALLBACK_MS
        expected += 2
    }
    state.refreshPending = false

    if (cmds) {
        List<String> seq = delayBetween(cmds.collect { zwaveSecureEncap(it) }, 300) as List<String>
        sendHubCommand(new hubitat.device.HubMultiAction(seq, hubitat.device.Protocol.ZWAVE))
    }
    logDebug("Wake-up: sent ${cmds.size()} command(s), expecting ${expected} repl${expected == 1 ? 'y' : 'ies'}")

    if (sleepAfter) {
        state.sleepPending = true
        state.awaitingReplies = expected
        if (expected == 0) sendSleep()
        else runInMillis(cmds.size() * 300 + 6000, "sleepFallback")
    } else {
        state.sleepPending = false
        state.awaitingReplies = 0
    }
    updateConfigStatus()
}

private void replyReceived() {
    if (state.sleepPending != true) return
    Integer left = ((state.awaitingReplies ?: 0) as Integer) - 1
    state.awaitingReplies = Math.max(0, left)
    if (left <= 0) sendSleep()
}

void sleepFallback() {
    if (state.sleepPending == true) logDebug("Not every reply arrived (${state.awaitingReplies} missing); sending sleep anyway")
    sendSleep()
}

private void sendSleep() {
    if (state.sleepPending != true) return
    state.sleepPending = false
    state.awaitingReplies = 0
    unschedule("sleepFallback")
    sendHubCommand(new hubitat.device.HubMultiAction(
        ["delay 300", zwaveSecureEncap(zwave.wakeUpV2.wakeUpNoMoreInformation().format())], hubitat.device.Protocol.ZWAVE))
    logDebug("Told the sensor to go back to sleep")
}

private boolean resyncActive() {
    List q = state.resyncQueue as List
    Long until = state.resyncExpiresAt as Long
    if (!q || until == null) return false
    if (now() > until) {
        state.resyncQueue = []
        return false
    }
    return true
}

private void consumeResync(String target) {
    List q = ((state.resyncQueue ?: []) as List).collect { it as String }
    q.remove(target)
    state.resyncQueue = q
}

// ---------------------------------------------------------------------------------------------
// Supervision
// ---------------------------------------------------------------------------------------------

private void sendSupervisionReport(Integer sessionID) {
    String report = zwaveSecureEncap(zwave.supervisionV1.supervisionReport(
        sessionID: sessionID, moreStatusUpdates: false, status: 0xFF, duration: 0).format())
    Integer delay = effectiveSupervisionDelay()
    List<String> seq = delay > 0 ? ["delay ${delay}".toString(), report] : [report]
    sendHubCommand(new hubitat.device.HubMultiAction(seq, hubitat.device.Protocol.ZWAVE))
    logDebug("Supervision report for session ${sessionID} in ${delay} ms")
}

// Never let the delay approach the timeout the sensor is actually using right now. A late
// confirmation only causes a retransmission (filtered); a too-early one risks the lock-up.
private Integer effectiveSupervisionDelay() {
    Integer wanted = settingInt("supervisionDelayMs", 1000)
    if (wanted <= 0) return 0
    Integer timeout = (state.cfg?.get("6") as Integer) ?: DEVICE_DEFAULT_SUP_TIMEOUT_MS
    return Math.max(0, Math.min(wanted, timeout - SUP_DELAY_MARGIN_MS))
}

private boolean isDuplicateSupervision(String key) {
    String id = device.id.toString()
    Map seen = (Map) SUP_CACHE.get(id)
    if (seen == null) {
        seen = new ConcurrentHashMap()
        SUP_CACHE.put(id, seen)
    }
    long t = now()
    new ArrayList(seen.keySet()).each { k ->
        Long ts = seen.get(k) as Long
        if (ts != null && t - ts > SUPERVISION_DEDUP_WINDOW_MS) seen.remove(k)
    }
    boolean dup = seen.containsKey(key)
    seen.put(key, t)
    return dup
}

private void clearSupervisionCache() {
    SUP_CACHE.remove(device.id.toString())
}

// Long enough to cover the sensor's whole retry schedule, so a late retransmission that slips past
// the session check is still treated as a duplicate.
private long sameStateWindowMs() {
    int retries = desiredParam(2)
    long waitMs = desiredParam(3) * 1000L
    long timeoutMs = desiredParam(6)
    long backoff = waitMs * (1L << Math.min(retries, 5))
    long computed = (retries + 1) * (timeoutMs + backoff)
    return Math.max(30000L, Math.min(180000L, computed))
}

// ---------------------------------------------------------------------------------------------
// Configuration bookkeeping
// ---------------------------------------------------------------------------------------------

private Integer desiredParam(Integer p) {
    Map meta = CONFIG_PARAMS[p]
    return settingInt(meta.setting as String, meta.defaultValue as Integer)
}

private Integer desiredWakeSeconds() {
    return settingInt("wakeUpHours", 4) * 3600
}

private Integer getTries(Integer p) {
    return (((state.cfgTries ?: [:]) as Map).get(p.toString()) ?: 0) as Integer
}

private void setTries(Integer p, Integer n) {
    Map t = (state.cfgTries ?: [:]) as Map
    t[p.toString()] = n
    state.cfgTries = t
}

// Mismatched parameters that haven't used up their attempts.
private List<Integer> pendingParams() {
    Map reported = (state.cfg ?: [:]) as Map
    List<Integer> out = []
    CONFIG_PARAMS.keySet().each { k ->
        Integer p = k as Integer
        if ((reported.get(p.toString()) as Integer) != desiredParam(p) && getTries(p) < MAX_CONFIG_TRIES) out << p
    }
    return out
}

private boolean wakeIntervalPending() {
    return (state.wakeIntervalReported as Integer) != desiredWakeSeconds() && ((state.wakeTries ?: 0) as Integer) < MAX_CONFIG_TRIES
}

private boolean hasPendingConfig() {
    return pendingParams() || wakeIntervalPending()
}

private void updateConfigStatus() {
    Map reported = (state.cfg ?: [:]) as Map
    List<String> pending = pendingParams().collect { CONFIG_PARAMS[it].label as String }
    if (wakeIntervalPending()) pending << "wake-up interval"
    List<String> refused = []
    CONFIG_PARAMS.keySet().each { k ->
        Integer p = k as Integer
        Integer rep = reported.get(p.toString()) as Integer
        if (rep != null && rep != desiredParam(p) && getTries(p) >= MAX_CONFIG_TRIES) refused << "${CONFIG_PARAMS[p].label}=${rep}".toString()
    }
    String status
    if (pending) {
        status = "pending: ${pending.join(', ')} (applies at next wake-up)"
        if (reported.get("6") == null) status += "; confirmation delay limited to ${effectiveSupervisionDelay()} ms until then"
    } else {
        status = "synced"
    }
    if (refused) status += "; sensor kept ${refused.join(', ')}"
    status = status.toString()
    if (device.currentValue("configStatus") != status) sendEvent(name: "configStatus", value: status)
}

// ---------------------------------------------------------------------------------------------
// Health
// ---------------------------------------------------------------------------------------------

private void markAlive() {
    long t = now()
    Long lastStamp = state.lastCheckinAt as Long
    if (lastStamp == null || t - lastStamp > 60000L) {
        state.lastCheckinAt = t
        device.updateDataValue("lastCheckin", formatNow())
        scheduleHealthCheck()
    }
    if (device.currentValue("healthStatus") != "online") {
        sendEvent(name: "healthStatus", value: "online", descriptionText: "${device.displayName} is online")
        logInfo("Online")
        scheduleHealthCheck()
    }
}

private void scheduleHealthCheck() {
    Integer heartbeat = (state.cfg?.get("1") as Integer) ?: desiredParam(1)
    runIn((heartbeat * 3 + 5) * 60, "healthTimeout")
}

void healthTimeout() {
    if (device.currentValue("healthStatus") == "offline") return
    sendEvent(name: "healthStatus", value: "offline", descriptionText: "${device.displayName} has not checked in for 3 heartbeats")
    logWarn("Offline: nothing heard for 3 heartbeat intervals (battery, range or pairing problem)")
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

private void incrementMissed() {
    Integer n = ((device.currentValue("suspectedMissedEvents") ?: 0) as Integer) + 1
    sendEvent(name: "suspectedMissedEvents", value: n)
}

private Integer settingInt(String name, Integer fallback) {
    def v = settings[name]
    if (v == null || v.toString().trim() == "") return fallback
    try {
        return (v.toString() as BigDecimal).intValue()
    } catch (e) {
        return fallback
    }
}

private String formatNow() {
    return new Date().format("yyyy-MM-dd HH:mm:ss", location?.timeZone ?: TimeZone.getDefault())
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (txtEnable != false) log.info "${device.displayName}: ${msg}" }
private void logWarn(String msg)  { log.warn "${device.displayName}: ${msg}" }

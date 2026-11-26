import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import hubitat.helper.ColorUtils
import hubitat.helper.HexUtils
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// AiDot WiFi Bulb (Local) - Hubitat driver
// Controls AiDot / Linkind Wi-Fi bulbs directly over the LAN (TCP 10000), no cloud.
// Protocol ported from python-aidot (Home Assistant's AiDot integration).
// Packet: 0x1EED (2B) | msgType (2B: 1=data, 2=ping) | bodyLen (4B) | AES-ECB(JSON)
// Setup: run aidot_get_keys.py once, give the bulb a DHCP reservation, fill in preferences.

@Field static final int    AIDOT_PORT = 10000
@Field static final String GEN1_MODEL = "lk.WIFI-RGBWLight-D0006"
@Field static ConcurrentHashMap<String, String> rxBuffers = new ConcurrentHashMap<>()

metadata {
    definition(name: "AiDot WiFi Bulb (Local)", namespace: "adam", author: "Adam",
               importUrl: "https://raw.githubusercontent.com/metman2013/hubitat-aidot/main/AiDot_WiFi_Bulb_Local.groovy") {
        capability "Actuator"
        capability "Light"
        capability "Switch"
        capability "SwitchLevel"
        capability "ColorTemperature"
        capability "ColorControl"
        capability "ColorMode"
        capability "Refresh"
        capability "Initialize"

        attribute "connection", "enum", ["online", "offline", "connecting"]

        command "reconnect"
    }
    preferences {
        input "ipAddress",     "text",     title: "Bulb IP address", required: true
        input "deviceId",      "text",     title: "Device ID",  required: true
        input "userId",        "text",     title: "User ID",    required: true
        input "devPassword",   "password", title: "Device password", required: true
        input "aesKey",        "password", title: "AES key",    required: true
        input "modelId",       "text",     title: "Model ID (optional)", required: false
        input "simpleVersion", "text",     title: "simpleVersion (leave blank if script showed None)", required: false
        input "hasColor",      "bool",     title: "Bulb supports RGB color", defaultValue: true
        input "ctMin",         "number",   title: "Min color temp (K)", defaultValue: 2700
        input "ctMax",         "number",   title: "Max color temp (K)", defaultValue: 6500
        input "logEnable",     "bool",     title: "Enable debug logging (auto-off 30 min)", defaultValue: true
        input "txtEnable",     "bool",     title: "Enable description text logging", defaultValue: true
    }
}

// ============================== lifecycle ==============================

void installed() { initialize() }

void updated() {
    log.info "${device.displayName}: preferences updated"
    if (logEnable) runIn(1800, "logsOff")
    initialize()
}

void initialize() {
    disconnect()
    if (!settings.ipAddress || !settings.deviceId || !settings.userId || !settings.aesKey) {
        log.warn "${device.displayName}: not configured yet"
        return
    }
    runIn(2, "connect")
}

void logsOff() {
    device.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "${device.displayName}: debug logging disabled"
}

// ============================== connection ==============================

void connect() {
    rxBuffers.put(bufKey(), "")
    state.loggedIn = false
    state.missedPings = 0
    sendEvent(name: "connection", value: "connecting")
    try {
        logDebug "connecting to ${settings.ipAddress}:${AIDOT_PORT}"
        interfaces.rawSocket.connect([byteInterface: true], settings.ipAddress, AIDOT_PORT)
        state.seq = 1
        sendLogin()
        runIn(10, "loginTimeout")
    } catch (e) {
        log.warn "${device.displayName}: connect failed: ${e.message}"
        markOffline()
        scheduleReconnect()
    }
}

void disconnect() {
    unschedule("ping")
    unschedule("reconnect")
    unschedule("loginTimeout")
    try { interfaces.rawSocket.close() } catch (ignored) { }
    state.loggedIn = false
}

void reconnect() {
    log.info "${device.displayName}: reconnecting"
    disconnect()
    connect()
}

void scheduleReconnect() {
    unschedule("ping")
    runIn(30, "reconnect")
}

void loginTimeout() {
    if (!state.loggedIn) {
        log.warn "${device.displayName}: no login response from bulb"
        markOffline()
        scheduleReconnect()
    }
}

void markOffline() {
    state.loggedIn = false
    if (device.currentValue("connection") != "offline") sendEvent(name: "connection", value: "offline")
}

def socketStatus(String message) {
    log.warn "${device.displayName}: socket status: ${message}"
    markOffline()
    scheduleReconnect()
}

// ============================== protocol ==============================

private void sendLogin() {
    String seq = now().toString()[-9..-1]
    Map msg = [
        service : "device",
        method  : "loginReq",
        seq     : seq,
        srcAddr : settings.userId,
        deviceId: settings.deviceId,
        payload : [
            userId   : settings.userId,
            password : settings.devPassword,
            timestamp: new Date().format("yyyy-MM-dd HH:mm:ss.SSS") + "000",
            ascNumber: 1
        ]
    ]
    sendPacket(msg, 1)
}

private void sendAction(attr, String method) {
    if (!state.loggedIn) {
        log.warn "${device.displayName}: bulb not connected, command dropped"
        return
    }
    state.seq = ((state.seq ?: 1) as int) + 1
    String seq = "hb93" + String.format("%05d", (state.seq as int) % 100000)
    boolean full = settings.simpleVersion && settings.simpleVersion != "None"
    Map payload = [
        devId    : full ? settings.deviceId : "",
        parentId : full ? settings.deviceId : "",
        userId   : full ? settings.userId : "",
        password : full ? settings.devPassword : "",
        attr     : attr,
        channel  : "tcp",
        ascNumber: state.ascNumber ?: 1
    ]
    Map msg = [
        method  : method,
        service : "device",
        clientId: full ? "ha-${settings.userId}".toString() : "",
        srcAddr : "0.${settings.userId}".toString(),
        seq     : seq,
        payload : payload,
        tst     : now(),
        deviceId: settings.deviceId
    ]
    sendPacket(msg, 1)
}

private void sendPacket(Map msg, int msgType) {
    String json = JsonOutput.toJson(msg)
    logDebug "TX(${msgType}): ${json.replace(settings.devPassword ?: '\u0000', '***')}"
    byte[] body = aes(json.getBytes("UTF-8"), Cipher.ENCRYPT_MODE)
    byte[] pkt = new byte[8 + body.length]
    pkt[0] = (byte) 0x1E; pkt[1] = (byte) 0xED
    pkt[2] = (byte) ((msgType >> 8) & 0xFF); pkt[3] = (byte) (msgType & 0xFF)
    int n = body.length
    pkt[4] = (byte) ((n >> 24) & 0xFF); pkt[5] = (byte) ((n >> 16) & 0xFF)
    pkt[6] = (byte) ((n >> 8) & 0xFF);  pkt[7] = (byte) (n & 0xFF)
    for (int i = 0; i < n; i++) pkt[8 + i] = body[i]
    try {
        interfaces.rawSocket.sendMessage(HexUtils.byteArrayToHexString(pkt))
    } catch (e) {
        log.warn "${device.displayName}: send failed: ${e.message}"
        markOffline()
        scheduleReconnect()
    }
}

// TCP can split or merge frames, so buffer and pull out complete packets.
def parse(String hex) {
    String buf = (rxBuffers.get(bufKey()) ?: "") + hex.toUpperCase()
    while (buf.length() >= 16) {
        if (!buf.startsWith("1EED")) {
            log.warn "${device.displayName}: bad frame header, dropping buffer"
            buf = ""
            break
        }
        int bodyLen = Long.parseLong(buf.substring(8, 16), 16) as int
        int total = 16 + bodyLen * 2
        if (buf.length() < total) break
        String bodyHex = buf.substring(16, total)
        buf = buf.substring(total)
        try {
            String json = new String(aes(HexUtils.hexStringToByteArray(bodyHex), Cipher.DECRYPT_MODE), "UTF-8")
            handleMessage(json)
        } catch (e) {
            log.warn "${device.displayName}: could not decrypt frame (wrong AES key?): ${e.message}"
        }
    }
    rxBuffers.put(bufKey(), buf)
}

private void handleMessage(String json) {
    logDebug "RX: ${json}"
    state.missedPings = 0
    def msg = new JsonSlurper().parseText(json)
    if (msg.service == "test") return   // ping reply

    if (!state.loggedIn) {
        if (msg.ack?.code == 200) {
            unschedule("loginTimeout")
            state.loggedIn = true
            state.ascNumber = ((msg.payload?.ascNumber ?: 0) as int) + 1
            sendEvent(name: "connection", value: "online")
            log.info "${device.displayName}: connected to bulb"
            runIn(heartbeatSecs(), "ping")
            refresh()
        } else if (msg.ack) {
            log.error "${device.displayName}: bulb rejected login (code ${msg.ack.code}) - check Device ID / User ID / password"
            markOffline()
            scheduleReconnect()
        }
        return
    }

    if (msg.payload?.ascNumber) state.ascNumber = msg.payload.ascNumber as int
    if (msg.payload?.attr) applyAttr(msg.payload.attr as Map)
}

void ping() {
    if (!state.loggedIn) return
    int missed = (state.missedPings ?: 0) as int
    if (missed >= 3) {
        log.warn "${device.displayName}: bulb stopped answering, reconnecting"
        markOffline()
        reconnect()
        return
    }
    state.missedPings = missed + 1
    if (isGen1()) {
        sendAction(syncProps(), "getDevAttrReq")
    } else {
        sendPacket([service: "test", method: "pingreq", seq: "123456", srcAddr: "123456", payload: [:]], 2)
    }
    runIn(heartbeatSecs(), "ping")
}

// ============================== state from bulb ==============================

private void applyAttr(Map a) {
    if (a.OnOff != null) {
        String sw = (a.OnOff as int) ? "on" : "off"
        if (device.currentValue("switch") != sw) logInfo "switch is ${sw}"
        sendEvent(name: "switch", value: sw)
    }
    if (a.Dimming != null) {
        sendEvent(name: "level", value: a.Dimming as int, unit: "%")
    }

    Integer r = null, g = null, b = null, w = null
    if (a.RGBW != null && hasColor) {
        long v = (a.RGBW as long) & 0xFFFFFFFFL
        r = (int) ((v >> 24) & 0xFF); g = (int) ((v >> 16) & 0xFF)
        b = (int) ((v >> 8) & 0xFF);  w = (int) (v & 0xFF)
    }
    boolean rgbActive = (r != null) && (r + g + b > 0) && (w == 0)

    if (a.CCT != null && (a.CCT as int) > 0) {
        sendEvent(name: "colorTemperature", value: a.CCT as int, unit: "K")
    }
    if (rgbActive) {
        def hsv = ColorUtils.rgbToHSV([r, g, b])
        sendEvent(name: "hue", value: Math.round(hsv[0] as double) as int)
        sendEvent(name: "saturation", value: Math.round(hsv[1] as double) as int)
        sendEvent(name: "color", value: String.format("#%02X%02X%02X", r, g, b))
        sendEvent(name: "colorMode", value: "RGB")
    } else if (a.CCT != null || r != null) {
        sendEvent(name: "colorMode", value: "CT")
    }
}

// ============================== commands ==============================

void on()  { setAttr([OnOff: 1]) }
void off() { setAttr([OnOff: 0]) }

void setLevel(level, duration = null) {
    int lvl = clamp(level as int, 0, 100)
    if (lvl == 0) { off(); return }
    setAttr([OnOff: 1, Dimming: lvl])
}

void setColorTemperature(ct, level = null, transitionTime = null) {
    int k = clamp(ct as int, (settings.ctMin ?: 2700) as int, (settings.ctMax ?: 6500) as int)
    Map attr = [OnOff: 1, CCT: k]
    if (level != null) attr.Dimming = clamp(level as int, 1, 100)
    setAttr(attr)
}

void setColor(Map c) {
    if (!hasColor) { log.warn "${device.displayName}: color disabled in preferences"; return }
    def hue = c.hue != null ? c.hue : (device.currentValue("hue") ?: 0)
    def sat = c.saturation != null ? c.saturation : (device.currentValue("saturation") ?: 100)
    def rgb = ColorUtils.hsvToRGB([hue as double, sat as double, 100d])
    long v = (((rgb[0] as long) & 0xFF) << 24) | (((rgb[1] as long) & 0xFF) << 16) | (((rgb[2] as long) & 0xFF) << 8)
    Map attr = [OnOff: 1, RGBW: (int) v]   // bulb expects a signed 32-bit int
    if (c.level != null) attr.Dimming = clamp(c.level as int, 1, 100)
    setAttr(attr)
}

void setHue(hue) {
    setColor([hue: hue, saturation: device.currentValue("saturation") ?: 100])
}

void setSaturation(sat) {
    setColor([hue: device.currentValue("hue") ?: 0, saturation: sat])
}

void refresh() {
    sendAction(syncProps(), "getDevAttrReq")
}

private void setAttr(Map attr) {
    sendAction(attr, "setDevAttrReq")
    runInMillis(1500, "refresh")   // confirm actual state from the bulb
}

// ============================== helpers ==============================

private byte[] aes(byte[] data, int mode) {
    byte[] k = settings.aesKey.getBytes("UTF-8")
    if (k.length < 16) {
        byte[] padded = new byte[16]
        for (int i = 0; i < k.length; i++) padded[i] = k[i]
        k = padded
    }
    Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(mode, new SecretKeySpec(k, "AES"))
    return cipher.doFinal(data)
}

private List syncProps() {
    return hasColor ? ["OnOff", "Dimming", "RGBW", "CCT"] : ["OnOff", "Dimming", "CCT"]
}

private boolean isGen1() { return settings.modelId == GEN1_MODEL }
private int heartbeatSecs() { return isGen1() ? 10 : 30 }
private String bufKey() { return device.id.toString() }
private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)) }
private void logDebug(String m) { if (logEnable) log.debug "${device.displayName}: ${m}" }
private void logInfo(String m)  { if (txtEnable) log.info "${device.displayName}: ${m}" }

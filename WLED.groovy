/**
*
*  WLED Device Type
*
*  Author: bryan@joyful.house
*
*  Date: 2019-11-27
*/
import java.net.URLEncoder

metadata {
    definition (name: "WLED", namespace: "joyfulhouse", author: "Bryan Li") {
        capability "Color Control"
        capability "Color Temperature"
        capability "Refresh"
        capability "Switch"
        capability "Switch Level"
        capability "Light"
        capability "ColorMode"

        capability "Alarm"

        attribute "colorName", "string"
        attribute "effectName", "string"
        attribute "paletteName", "string"
        attribute "presetValue", "Number"
        
        //command "getEffects"
        //command "getPalettes"
        command "setEffect", 
            [
                [name:"FX ID", type: "NUMBER", description: "Effect ID", constraints: []],
                [name:"FX Speed", type: "NUMBER", description: "Relative Effect Speed (0-255)", constraints: []],
                [name:"FX Intensity", type: "NUMBER", description: "Effect Intensity(0-255)", constraints: []],
                [name:"Color Palette", type: "NUMBER", description: "Color Palette", constraints: []]
            ]
        command "setPreset", 
            [
                [name:"Preset", type: "NUMBER", description: "Preset Number", constraints: []],
            ]
    }
    
    // Preferences
    preferences {
        input "uri", "text", title: "URI", description: "(eg. http://[wled_ip_address])", required: true, displayDuringSetup: true
        input name: "ledSegment", type: "number", title: "LED Segment", defaultValue: 0
        input name: "transitionTime", type: "enum", description: "", title: "Transition time", options: [[500:"500ms"],[1000:"1s"],[1500:"1.5s"],[2000:"2s"],[5000:"5s"]], defaultValue: 1000
        input name: "refreshInterval", type: "enum", description: "", title: "Refresh interval", options: [
            [30: "30 Seconds"],[60:"1 Minute"],[300:"5 Minutes"],[600:"10 Minutes"],[1800:"30 Minutes"],[3600:"1 Hour"],[0:"Disabled"]],
            defaultValue: 3600
        input name: "powerOffParent", type: "bool", description:"Turn off segment and parent controller", title: "Power Off", defaultValue: false
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def initialize() {
     installed()
}

def installed() {
    setSchedule()
    refresh()
}

def updated() {
    setSchedule()
    getEffects()
    getPalettes()
    refresh()
}

def setSchedule() {
  logDebug "Setting refresh interval to ${settings.refreshInterval}s"
  unschedule()
  switch(settings.refreshInterval){
    case "0":
      unschedule()
      break
    case "30":
      schedule("0/30 * * ? * * *", refresh)
      break
    case "60":
      schedule("0 * * ? * * *", refresh)
      break
    case "300":
      schedule("0 0/5 * ? * * *", refresh)
      break
    case "600":
      schedule("0 0/10 * ? * * *", refresh)
      break
    case "1800":
      schedule("0 0/30 * ? * * *", refresh)
      break
    case "3600":
      schedule("0 * 0/1 ? * * *", refresh)
      break
    default:
      unschedule()
  }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def parseResp(resp) {
    // Handle Effects and Palettes
    if(!state.effects)
        getEffects()
    
    if(!state.palettes)
        getPalettes()
    
    def effects = state.effects
    def palettes = state.palettes
    
    // Update State
    logDebug resp
    state = resp
    state.effects = effects
    state.palettes = palettes
    
    synchronize(resp)
}

// Handle async callback
def parseResp(resp, data) {
    if(resp.getStatus() == 200)
        parseResp(resp.getJson())
    else if(resp.getStatus() == 408)
        log.error "HTTP Request Timeout"
    else
        log.error "Unhandled HTTP Error"
}

def parsePostResp(resp){
    // TODO
}

def parsePostResp(resp, data) {
    if(resp.getStatus() == 200)
        parsePostResp(resp.getJson())
    else if(resp.getStatus() == 408)
        log.error "HTTP Request Timeout"
    else
        log.error "Unhandled HTTP Error"
}

def synchronize(data){
    logDebug "Synchronizing status: ${data.seg[settings.ledSegment?.toInteger() ?: 0]}}"
    seg = data.seg[settings.ledSegment?.toInteger() ?: 0]
    
    // Power
    if(seg.on){
        if(device.currentValue("switch") != "on")
            sendEvent(name: "switch", value: "on")
    }
    else {
        if(device.currentValue("switch") == "on")
            sendEvent(name: "switch", value: "off")
    }
    
    //TODO: Synchronize everything else
}

// Switch Capabilities
def on() {
    sendEthernetPost("/json/state","{\"on\":true, \"seg\": [{\"id\": ${ledSegment}, \"on\":true}]}")  
    sendEvent(name: "switch", value: "on")
}

def off() {
    if(powerOffParent)
        parentOff()
    else
        segmentOff()
}

def parentOff(){
    sendEthernetPost("/json/state","{\"on\":false,\"pl\":0,\"ps\":0,\"seg\": [{\"id\": ${ledSegment}, \"on\":false}]}")    
    sendEvent(name: "switch", value: "off")
}

def segmentOff() {
    sendEthernetPost("/json/state","{\"pl\":0,\"ps\":0,\"seg\": [{\"id\": ${ledSegment}, \"on\":false}]}")    
    sendEvent(name: "switch", value: "off")
}

// Color Names
def setGenericTempName(temp){
    if (!temp) return
    def genericName
    def value = temp.toInteger()
    if (value <= 2000) genericName = "Sodium"
    else if (value <= 2100) genericName = "Starlight"
    else if (value < 2400) genericName = "Sunrise"
    else if (value < 2800) genericName = "Incandescent"
    else if (value < 3300) genericName = "Soft White"
    else if (value < 3500) genericName = "Warm White"
    else if (value < 4150) genericName = "Moonlight"
    else if (value <= 5000) genericName = "Horizon"
    else if (value < 5500) genericName = "Daylight"
    else if (value < 6000) genericName = "Electronic"
    else if (value <= 6500) genericName = "Skylight"
    else if (value < 20000) genericName = "Polar"
    def descriptionText = "${device.getDisplayName()} color is ${genericName}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "colorName", value: genericName ,descriptionText: descriptionText)
}

def setGenericName(hue){
    def colorName
    hue = hue.toInteger()
    if (!hiRezHue) hue = (hue * 3.6)
    switch (hue.toInteger()){
        case 0..15: colorName = "Red"
            break
        case 16..45: colorName = "Orange"
            break
        case 46..75: colorName = "Yellow"
            break
        case 76..105: colorName = "Chartreuse"
            break
        case 106..135: colorName = "Green"
            break
        case 136..165: colorName = "Spring"
            break
        case 166..195: colorName = "Cyan"
            break
        case 196..225: colorName = "Azure"
            break
        case 226..255: colorName = "Blue"
            break
        case 256..285: colorName = "Violet"
            break
        case 286..315: colorName = "Magenta"
            break
        case 316..345: colorName = "Rose"
            break
        case 346..360: colorName = "Red"
            break
    }
    def descriptionText = "${device.getDisplayName()} color is ${colorName}"
    if (txtEnable) log.info "${descriptionText}"
    sendEvent(name: "colorName", value: colorName ,descriptionText: descriptionText)
}

// Dimmer function
def setLevel(value) {
    setLevel(value, (transitionTime?.toBigDecimal() ?: 1000) / 1000)
}

def setLevel(value, rate) {
    // TODO: implement transition rate
    
    if(value > 0){
        def isOn = device.currentValue("switch") == "on"
        if(!isOn)
            on()
        
        if(value >= 100) {
            setValue = 255
            value = 100
        }
        else {
            setValue = (value.toInteger() * 2.55).toInteger()
        }
        
        msg = "{\"on\":true, \"seg\": [{\"id\": ${ledSegment}, \"on\":true, \"bri\": ${setValue}}]}"
        sendEthernetPost("/json/state", msg)
        sendEvent(name: "level", value: value, descriptionText: "${device.displayName} is ${value}%", unit: "%")
    } else {
        off()
    }
    
    refresh()
}

// Color Functions
def setColor(value){
    if (value.hue == null || value.saturation == null) return
    def rate = transitionTime?.toInteger() ?: 1000

    // Turn off if level is set to 0/black
    if (value.level == 0) {
        off()
        return
    } else if(value.level >= 100) {
        level = 255
    }    else {
        level = value.level * 256
    }
    
    // Convert to RGB from HSV
    rgbValue = hsvToRgb(value.hue, value.saturation, value.level)
    
    // Send to WLED
    logDebug("Setting RGB Color to ${rgbValue}")
    setRgbColor(rgbValue)
    setGenericName(value.hue)
}

def setColorTemperature(temp, level, transitionTime){
    if (level) {
      setLevel(level, transitionTime)
    }
    
    setColorTemperature(temp)
}

def setColorTemperature(temp, level){
    if (level) {
      setLevel(level)
    }
    
    setColorTemperature(temp)
}

def setColorTemperature(temp){
    on()
    rgbValue = colorTempToRgb(temp)
    setRgbColor(rgbValue)
    setGenericTempName(temp)
}

def getCurrentValueMap(){
    def map = [:]
    map.hue = device.currentValue("hue")
    map.saturation = device.currentValue("saturation")
    map.level = device.currentValue("level")
    return map
}

def setHue(value){
    color = getCurrentValueMap()
    color.hue = value
    setColor(color)
    sendEvent(name: "hue", value: value)
}

def setSaturation(value){
    color = getCurrentValueMap()
    color.saturation = value
    setColor(color)
    sendEvent(name: "saturation", value: value)
}

def setRgbColor(rgbValue){
    // Turn off any active effects
    setEffect(0,0)
    
    // Send Color
    body = "{\"on\":true, \"seg\": [{\"id\": ${ledSegment}, \"on\":true, \"col\": [${rgbValue}]}]}"
    logDebug("Setting color: ${body}")
    sendEthernetPost("/json/state", body)
    refresh()
}

// Device Functions
def refresh() {
    sendEthernet("/json/state")
}

def sendEthernet(path) {
    if(settings.uri != null){
        def params = [
            uri: "${settings.uri}",
            path: "${path}",
            requestContentType: 'application/json',
            contentType: 'application/json',
            headers: [:],
            timeout: 5
        ]

        try {
            asynchttpGet('parseResp',params)
        } catch (e) {
            log.error "something went wrong: $e"
        }
    }
}

def sendEthernetPost(path, body) {
    if(settings.uri != null){
        
        def params = [
            uri: "${settings.uri}",
            path: "${path}",
            requestContentType: 'application/json',
            contentType: 'application/json',
            body: "${body}",
            timeout: 5
        ]

        try {            
            asynchttpPut(null, params)
        } catch (e) {
            log.error "something went wrong: $e"
        }
    }
}

// Helper Functions
def logDebug(message){
    if(logEnable) log.debug(message)
}

def hsvToRgb(float hue, float saturation, float value) {
    if(hue==100) hue = 99
    hue = hue/100
    saturation = saturation/100
    value = value/100
    
    int h = (int)(hue * 6)
    float f = hue * 6 - h
    float p = value * (1 - saturation)
    float q = value * (1 - f * saturation)
    float t = value * (1 - (1 - f) * saturation)

    switch (h) {
      case 0: return rgbToString(value, t, p)
      case 1: return rgbToString(q, value, p)
      case 2: return rgbToString(p, value, t)
      case 3: return rgbToString(p, q, value)
      case 4: return rgbToString(t, p, value)
      case 5: return rgbToString(value, p, q)
      default: log.error "Something went wrong when converting from HSV to RGB. Input was " + hue + ", " + saturation + ", " + value
    }
}

def colorTempToRgb(kelvin){
    temp = kelvin/100
    
    if( temp <= 66 ){ 
        red = 255
        green = temp
        green = 99.4708025861 * Math.log(green) - 161.1195681661
        if( temp <= 19){
            blue = 0
        } else {
            blue = temp-10
            blue = 138.5177312231 * Math.log(blue) - 305.0447927307
        }
    } else {
        red = temp - 60
        red = 329.698727446 * Math.pow(red, -0.1332047592)
        green = temp - 60
        green = 288.1221695283 * Math.pow(green, -0.0755148492 )
        blue = 255
    }
    
    rs = clamp(red,0, 255)
    gs = clamp(green,0,255)
    bs = clamp(blue,0, 255)
    
    return "[" + rs + "," + gs + "," + bs + "]";
}

def rgbToString(float r, float g, float b) {
    String rs = (int)(r * 255)
    String gs = (int)(g * 255)
    String bs = (int)(b * 255)
    return "[" + rs + "," + gs + "," + bs + "]";
}

def clamp( x, min, max ) {
    if(x<min){ return min; }
    if(x>max){ return max; }
    return x;
}

// FastLED FX and Palletes

def getEffects(){
    logDebug "Getting Effects List"
    def params = [
        uri: "${settings.uri}",
        path: "/json/effects",
        headers: [
            "Content-Type": "application/json",
            "Accept": "*/*"
        ],
        body: "${body}",
        timeout: 5
    ]

    try {
        httpGet(params) {
            resp ->
                state.effects = resp.data
        }
    } catch (e) {
        log.error "something went wrong: $e"
    }
}

def getPalettes(){
    logDebug "Getting Palettes List"
    def params = [
        uri: "${settings.uri}",
        path: "/json/palettes",
        headers: [
            "Content-Type": "application/json",
            "Accept": "*/*"
        ],
        body: "${body}",
        timeout: 5
    ]

    try {
        httpGet(params) {
            resp ->
                state.palettes = resp.data
        }
    } catch (e) {
        log.error "something went wrong: $e"
    }
}

def setEffect(fx){
    def i = ledSegment?.toInteger() ?: 0
    setEffect(fx, state.seg[i].sx, state.seg[i].ix, state.seg[i].pal)
}

def setEffect(fx, pal){
    def i = ledSegment?.toInteger() ?: 0
    setEffect(fx, state.seg[i].sx, state.seg[i].ix, pal)
}

def setEffect(fx,sx,ix){
    def i = ledSegment?.toInteger() ?: 0
    setEffect(fx, sx, ix, state.seg[i].pal)
}

def setEffect(fx, sx, ix, pal){
    logDebug("Setting Effect: [{\"id\": ${ledSegment},\"fx\": ${fx},\"sx\": ${sx},\"ix\": ${ix},\"pal\": ${pal}}]")
    body = "{\"on\":true, \"seg\": [{\"id\": ${ledSegment},\"fx\": ${fx},\"sx\": ${sx},\"ix\": ${ix},\"pal\": ${pal}}]}"
    
    sendEthernetPost("/json/state", body)
    
    // Effect Name
    def effectName = state.effects.getAt(fx.intValue())
    def descriptionText = "${device.getDisplayName()} effect is ${effectName}"
    if (txtEnable) log.info "${descriptionText}"
        sendEvent(name: "effectName", value: effectName, descriptionText: descriptionText)
        
    // Palette Name
    def paletteName = state.palettes.getAt(pal.intValue())
    descriptionText = "${device.getDisplayName()} color palette is ${paletteName}"
    if (txtEnable) log.info "${descriptionText}"
        sendEvent(name: "paletteName", value: paletteName, descriptionText: descriptionText)

    if(fx > 0){
        // Color Name
        descriptionText = "${device.getDisplayName()} color is defined by palette"
        sendEvent(name: "colorName", value: "Palette", descriptionText: descriptionText)
    }
    
    // Refresh
    refresh()
}

def setEffectCustom(fx, sx, ix, pal){
    // support for webCORE
    setEffect(fx, sx, ix, pal)
}

def setPreset(preset)
{
    logDebug("${device.getDisplayName()} setting preset to ${preset}")

    msg = "{\"on\":true, \"ps\": ${preset}}"
    sendEthernetPost("/json/state", msg)
    sendEvent(name: "presetValue", value: preset, descriptionText: "${device.displayName} preset is set to ${preset}")
}

// Alarm Functions
def siren(){
    // Play "Siren" effect
    logDebug("Alarm \"siren\" activated")
    setEffect(38,255,255,0)
}

def strobe(){
    // Set Effect to Strobe
    logDebug("Alarm strobe activated")
    setEffect(23,255,255,0)
}

def both(){
    //Cannot do both, default to strobe
    strobe()
}

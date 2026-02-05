package com.gogle.virhook

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.app.Activity
import java.io.FileOutputStream


class CameraHook: IXposedHookLoadPackage{
    // Frame Buffer for Fail Recovery
    private val frameBuffer = java.util.LinkedList<ByteArray>()
    private val MAX_BUFFER_SIZE = 30
    
    // FPS Counters
    private var fpsCounter = 0
    private var lastFpsTime = 0L
    private var currentFps = 0

    override fun handleLoadPackage(lpparam: LoadPackageParam){
        // 1. SAFETY PANIC SWITCH: Immediate exit if file exists. Prevents bootloops.
        // Support both old and new conventions for safety
        if (File("/sdcard/vircam_disable").exists() || File("/sdcard/vircam_stop").exists()) {
             XposedBridge.log("Vircam: DISABLE/STOP file found. Hook disabled for safe mode.")
             return
        }

        // 2. TARGET APP FILTERING: Only hook apps that actually USE the camera.
        // Prevent hooking System UI, Phone, etc. which causes crashes/resets.
        val targetApps = setOf(
            "com.instagram.android",
            "com.snapchat.android",
            "org.telegram.messenger",
            "com.whatsapp",
            "com.google.android.GoogleCamera",
            "com.android.camera2" // Generic camera
        )
        
        // Allow hooking if it's in our target list OR if the user manually added it to config (todo)
        // For now, stricter safety:
        if (!targetApps.contains(lpparam.packageName)) {
            return 
        }

        try {
            // --- SKILL: Sensor Sync (Sine Wave) ---
            // Simulates hand tremors when camera is active
            hookSensorData(lpparam.classLoader)
            
            // --- SKILL: Ghost Device (Hardware & Build) ---
            hookHardwareCapabilities(lpparam.classLoader)
            hookSystemBuild(lpparam.classLoader)
            hookSystemProperties(lpparam.classLoader)
            hookPackageManager(lpparam.classLoader) // NEW: Package Isolation
            hookSystemProperties(lpparam.classLoader) // NEW: Prop Hook

            // --- SKILL: Verification Debugger ---
            hookDiagnosticOverlay(lpparam.classLoader)
            hookVerificationCapture(lpparam.classLoader)
            hookNetworkSniffer(lpparam)
            hookCaptureCallback(lpparam)
            hookErrorInterceptor(lpparam)
            hookVerificationReasoning(lpparam.classLoader)
            hookVerificationReasoning(lpparam.classLoader)
            
        } catch(e: Throwable) {
             XposedBridge.log("Vircam Ghost Hook Error: " + e.message)
        }

        try{
            val hook = object: XC_MethodHook(50){
                override fun beforeHookedMethod(param: MethodHookParam){
                    try {
                        // Global Panic Switch Check (Runtime)
                        val activeFile = File("/sdcard/Download/vircam_active.txt")
                        if(activeFile.exists() && activeFile.readText().trim() == "0"){
                            // Panic Mode: Do NOT inject metadata.
                            return
                        }

                        val exif = param.thisObject
                        val metaFile = File("/sdcard/Download/vircam_meta.txt")
                        if(metaFile.exists()){
                            val p = Properties()
                            p.load(FileInputStream(metaFile))
                            
                            // 1. Auto-Time (Realism)
                            val now = Date()
                            val df = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                            val dateStr = df.format(now)
                            val gpsDateFmt = SimpleDateFormat("yyyy:MM:dd", Locale.US)
                            val gpsTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                            
                            XposedHelpers.callMethod(exif, "setAttribute", "DateTime", dateStr)
                            XposedHelpers.callMethod(exif, "setAttribute", "DateTimeOriginal", dateStr)
                            XposedHelpers.callMethod(exif, "setAttribute", "DateTimeDigitized", dateStr)
                            XposedHelpers.callMethod(exif, "setAttribute", "GPSDateStamp", gpsDateFmt.format(now))
                            XposedHelpers.callMethod(exif, "setAttribute", "GPSTimeStamp", gpsTimeFmt.format(now))

                            // 2. Anti-AI / Sanitization
                            // Overwrite Software to ensure no "AI Generated" or editor tags remain
                            // User is on Android 14 (Build UP1A.231105.001.B2)
                            val software = p.getProperty("Software", "Android 14") 
                            XposedHelpers.callMethod(exif, "setAttribute", "Software", software)
                            // Clear ImageDescription if not provided (common place for AI prompts)
                            if(!p.containsKey("ImageDescription")) {
                                XposedHelpers.callMethod(exif, "setAttribute", "ImageDescription", "DCIM")
                            }

                            // 3. Smart GPS (Decimal -> DMS)
                            // Users often just have "40.7128", Exif needs "40/1, 42/1, ..."
                            if(p.containsKey("Lat") && p.containsKey("Lon")){
                                try {
                                    val lat = p.getProperty("Lat").toDouble()
                                    val lon = p.getProperty("Lon").toDouble()
                                    
                                    XposedHelpers.callMethod(exif, "setAttribute", "GPSLatitude", convertToDms(lat))
                                    XposedHelpers.callMethod(exif, "setAttribute", "GPSLatitudeRef", if(lat>0) "N" else "S")
                                    XposedHelpers.callMethod(exif, "setAttribute", "GPSLongitude", convertToDms(lon))
                                    XposedHelpers.callMethod(exif, "setAttribute", "GPSLongitudeRef", if(lon>0) "E" else "W")
                                } catch(e: Exception){
                                    XposedBridge.log("Vircam GPS Parse Error: ${e.message}")
                                }
                            }

                            // 4. Apply other raw overrides
                            p.forEach { k, v ->
                                val key = k.toString()
                                if(key != "Lat" && key != "Lon" && key != "IMEI" && key != "IMEI_SV") {
                                    XposedHelpers.callMethod(exif, "setAttribute", key, v.toString())
                                }
                            }
                            XposedBridge.log("Vircam: Injected REALISTIC metadata into ${lpparam.packageName}")
                        }
                    } catch(e: Throwable){
                        XposedBridge.log("Vircam Error: "+e.message)
                    }
                }
            }
            
            XposedHelpers.findAndHookMethod("android.media.ExifInterface", lpparam.classLoader, "saveAttributes", hook)
            try {
                XposedHelpers.findAndHookMethod("androidx.exifinterface.media.ExifInterface", lpparam.classLoader, "saveAttributes", hook)
            } catch(e: Throwable){ }

            // --- SKILL: Device Integrity (Sync) ---
            val props = Properties()
            val configFile = File("/sdcard/Download/vircam_meta.txt") // Assuming this is the config file
            if (configFile.exists()) {
                props.load(FileInputStream(configFile))
            }

            val timeZoneId = props.getProperty("TimeZone") // e.g. "America/New_York"
            if (!timeZoneId.isNullOrEmpty()) {
                 hookTimeZone(lpparam.classLoader, timeZoneId)
                 XposedBridge.log("Vircam: Hooked TimeZone to $timeZoneId")
            }

            val virtualIp = props.getProperty("VirtualIP") // e.g. "192.168.1.150"
            if (!virtualIp.isNullOrEmpty()) {
                hookNetworkIdentity(lpparam.classLoader, virtualIp)
                XposedBridge.log("Vircam: Hooked Network IP to $virtualIp")
            }

            // --- SKILL: PIXEL 4a 5G METADATA INJECTION ---
            // Intercepts Camera2 API results to spoof sensor specs
            try {
                // 1. Capture Results (Frame Data)
                val metadataMap = mapOf(
                    android.hardware.camera2.CaptureResult.LENS_APERTURE to 1.7f,
                    android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH to 4.38f,
                    android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME to 10000000L, // 1/100s
                    android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY to 400, // ISO 400
                    android.hardware.camera2.CaptureResult.LENS_STATE to android.hardware.camera2.CaptureResult.LENS_STATE_STATIONARY,
                    android.hardware.camera2.CaptureResult.FLASH_STATE to android.hardware.camera2.CaptureResult.FLASH_STATE_READY,
                    android.hardware.camera2.CaptureResult.CONTROL_AE_STATE to android.hardware.camera2.CaptureResult.CONTROL_AE_STATE_CONVERGED
                )

                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraMetadataNative",
                    lpparam.classLoader,
                    "get",
                    android.hardware.camera2.CaptureResult.Key::class.java,
                    object : XC_MethodHook(50) { // PRIORITY_HIGHEST
                        override fun afterHookedMethod(param: MethodHookParam) {
                            // Check Panic Switch (Global)
                            val activeFile = File("/sdcard/Download/vircam_active.txt")
                            if(activeFile.exists() && activeFile.readText().trim() == "0") return

                            val key = param.args[0] as android.hardware.camera2.CaptureResult.Key<*>
                            if (metadataMap.containsKey(key)) {
                                param.result = metadataMap[key]
                            }
                        }
                    }
                )

                // 2. Camera Characteristics (Static Capability Data)
                val characteristicsMap = mapOf(
                    android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to 
                        android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL,
                    android.hardware.camera2.CameraCharacteristics.LENS_FACING to 
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK,
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES to floatArrayOf(1.7f),
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS to floatArrayOf(4.38f)
                )

                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraMetadataNative",
                    lpparam.classLoader,
                    "get",
                    android.hardware.camera2.CameraCharacteristics.Key::class.java,
                    object : XC_MethodHook(50) { // PRIORITY_HIGHEST
                       override fun afterHookedMethod(param: MethodHookParam) {
                           // Check Panic Switch
                           val activeFile = File("/sdcard/Download/vircam_active.txt")
                           if(activeFile.exists() && activeFile.readText().trim() == "0") return

                           val key = param.args[0] as android.hardware.camera2.CameraCharacteristics.Key<*>
                           if (characteristicsMap.containsKey(key)) {
                               param.result = characteristicsMap[key]
                           }
                       }
                    }
                )
             } catch(t: Throwable) {
                 XposedBridge.log("Vircam: Metadata Hook Error: " + t.message)
             }
             
             // --- VIDEO INJECTION HOOK ---
             // Intercepts createCaptureSession to swap real surface with Virtual Surface
             // Note: In a real implementation, 'virtualSurface' needs to be sourced via SurfaceControl or Shared Memory.
             // For now, we define the structure for the "Injection" logic.
             /* 
             val virtualSurface = ... // TODO: Acquire Surface from RTMP Receiver Service
             hookCameraSurface(lpparam.classLoader, virtualSurface) 
             */

             // --- SKILL: Downward Focus Override ---
             try {
                // Hook CaptureRequest builder to set Focus Distance for Downward Mode
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureRequest\$Builder",
                    lpparam.classLoader,
                    "set",
                    android.hardware.camera2.CaptureRequest.Key::class.java,
                    Any::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val key = param.args[0] as android.hardware.camera2.CaptureRequest.Key<*>
                            if (key == android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE) {
                                if (File("/sdcard/vircam_downward").exists()) { // Check Toggle
                                    param.args[1] = 5.0f // 1.0/0.2m = 5.0 Diopters (Macro/Close-up)
                                    // XposedBridge.log("Vircam: Forced Macro Focus (Downward Mode)")
                                }
                            }
                        }
                    }
                )
             } catch(t: Throwable) {}

        }catch(t:Throwable){ XposedBridge.log(t) }

        // --- IMEI / DEVICE ID SPOOFING HOOK ---
        try {
           val tmHook = object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val metaFile = File("/sdcard/Download/vircam_meta.txt")
                    if (metaFile.exists()) {
                         // Check Panic Switch
                        val activeFile = File("/sdcard/Download/vircam_active.txt")
                        if(activeFile.exists() && activeFile.readText().trim() == "0") return

                        val p = Properties()
                        p.load(FileInputStream(metaFile))
                        
                        if (param.method.name == "getDeviceSoftwareVersion") {
                            if (p.containsKey("IMEI_SV")) {
                                param.result = p.getProperty("IMEI_SV")
                            }
                        } else {
                            if (p.containsKey("IMEI")) {
                                param.result = p.getProperty("IMEI")
                            }
                        }
                    }
                }
            }
            
            // Hook multiple methods
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader, "getDeviceId", tmHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader, "getImei", tmHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader, "getImei", Int::class.javaPrimitiveType, tmHook)
            XposedHelpers.findAndHookMethod("android.telephony.TelephonyManager", lpparam.classLoader, "getDeviceSoftwareVersion", tmHook) // SV Hook
        } catch (t: Throwable) { /* Method might not exist on all Android versions */ }

        // --- AUDIO INJECTION HOOK ---
        try{
            XposedHelpers.findAndHookMethod(
                "android.media.AudioRecord",
                lpparam.classLoader,
                "read",
                ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                object: XC_MethodHook(50){
                    // Keep a persistent socket per AudioRecord instance logic, 
                    // or just one global since usually one active recording at a time.
                    private var socket: android.net.LocalSocket? = null
                    private var inputStream: java.io.InputStream? = null

                    override fun afterHookedMethod(param: MethodHookParam){
                        // Check Panic Switch
                        val activeFile = File("/sdcard/Download/vircam_active.txt")
                        if(activeFile.exists() && activeFile.readText().trim() == "0") return

                        val buffer = param.args[0] as ByteArray
                        val sizeRequest = param.args[2] as Int
                        var actualRead = param.result as Int

                        if(actualRead <= 0) return

                        // Connect if needed
                        try {
                            if (socket == null || !socket!!.isConnected) {
                                socket = android.net.LocalSocket()
                                socket!!.connect(android.net.LocalSocketAddress("com.gogle.vircam.audio"))
                                inputStream = socket!!.inputStream
                            }
                            
                            // Try to read from our socket 'actualRead' amount of bytes
                            // Note: This might block if we don't have enough data, causing "lag".
                            // Ideally, we'd use available() check.
                            if(inputStream != null && inputStream!!.available() > 0) {
                                // We have data! Overwrite the real mic data.
                                // We read UP TO actualRead bytes.
                                val bytesFromSocket = inputStream!!.read(buffer, 0, actualRead)
                                
                                // If we got partial data, silence the rest? 
                                // Or leave real mic noise? Silence is cleaner for "Bridge".
                                if (bytesFromSocket < actualRead) {
                                    java.util.Arrays.fill(buffer, bytesFromSocket, actualRead, 0.toByte())
                                }
                            } else {
                                // Connected but no data yet? Silence the real mic to avoid leak?
                                // Let's silence it to be sure we don't broadcast room noise while waiting for stream.
                                java.util.Arrays.fill(buffer, 0, actualRead, 0.toByte())
                            }
                        } catch (e: Exception) {
                            // Connection failed (Service not running?), fall back to real mic
                            socket = null
                        }
                    }
                }
            )
        }catch(t:Throwable){ /* AudioRecord not found or already hooked */ }
    }

    private fun convertToDms(coord: Double): String {
        val absolute = abs(coord)
        val degrees = absolute.toInt()
        val minutes = ((absolute - degrees) * 60).toInt()
        val seconds = ((((absolute - degrees) * 60) - minutes) * 60 * 1000).toInt()
        return "$degrees/1,$minutes/1,$seconds/1000"
    }

    // --- SKILL: Resolution Matcher ---
    private fun getSurfaceDimensions(surface: android.view.Surface): Pair<Int, Int> {
        return try {
            // Use reflection to access hidden getWidth/getHeight methods
            val w = XposedHelpers.callMethod(surface, "getWidth") as Int
            val h = XposedHelpers.callMethod(surface, "getHeight") as Int
            Pair(w, h)
        } catch (e: Exception) {
            // Fallback to Pixel 4a 5G default if reflection fails
            Pair(1080, 1920) 
        }
    }

    // --- HELPER: VIDEO SUFACE INJECTION ---
    fun hookCameraSurface(classLoader: ClassLoader, virtualSurface: android.view.Surface) {
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.impl.CameraDeviceImpl",
            classLoader,
            "createCaptureSession",
            List::class.java, // This is the list of surfaces the app wants
            "android.hardware.camera2.CameraCaptureSession.StateCallback",
            "android.os.Handler",
            object : XC_MethodHook(50) { // PRIORITY_HIGHEST
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        // The app sent a list of real camera surfaces
                        val originalSurfaces = param.args[0] as MutableList<android.view.Surface>
                        if (originalSurfaces.isNotEmpty()) {
                            // Detect target resolution from the first surface (preview/record)
                            val (targetW, targetH) = getSurfaceDimensions(originalSurfaces[0])
                            
                            // LOG for Resolution Matcher
                            XposedBridge.log("VIRCAM_DEBUG: Resolution Matcher - App requests ${targetW}x${targetH}")
                            
                            // TODO: Send these dimensions to RTMPReceiverService via Intent/Binder
                            // ex: sendUpdateIntent(targetW, targetH)
                        }

                        // CRITICAL: Clear the real surfaces and inject our OBS surface
                        if (virtualSurface.isValid) {
                            originalSurfaces.clear()
                            originalSurfaces.add(virtualSurface)
                            XposedBridge.log("Vircam: Injected OBS Surface into Camera Session!")
                        }
                    } catch(e: Exception) {
                        XposedBridge.log("Vircam Surface Inject Error: ${e.message}")
                    }
                }
            }
        )
    }

    // --- SKILL: Time Zone Sync ---
    fun hookTimeZone(classLoader: ClassLoader, targetZoneId: String) {
        // 1. Hook the Java TimeZone default
        XposedHelpers.findAndHookMethod(
            java.util.TimeZone::class.java,
            "getDefault",
            object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.result = java.util.TimeZone.getTimeZone(targetZoneId)
                }
            }
        )

        // 2. Hook SystemProperties (where Android stores the zone string)
        try {
            XposedHelpers.findAndHookMethod(
                "android.os.SystemProperties",
                classLoader,
                "get",
                String::class.java,
                object : XC_MethodHook(50) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == "persist.sys.timezone") {
                            param.result = targetZoneId
                        }
                    }
                }
            )
        } catch(e: Throwable) { /* SystemProperties might differ on some ROMs */ }
    }

    // --- SKILL: IP Address & Network Identity ---
    fun hookNetworkIdentity(classLoader: ClassLoader, fakeIp: String) {
        // Hook InetAddress to return your "Virtual IP"
        try {
            XposedHelpers.findAndHookMethod(
                java.net.InetAddress::class.java,
                "getHostAddress",
                object : XC_MethodHook(50) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Only override local addresses or all? Risk of breaking connectivity if we override everything.
                        // Assuming user wants to spoof the "My IP" check.
                        // Ideally checking if it's the loopback or local. 
                        // For SAFETY, we only override if the original result is NOT loopback (127.0.0.1)
                        // but represents a local identity. 
                        // User request: "Force the getHostAddress method to return a specific 'Virtual IP'"
                        // We'll apply it broadly but caution is needed.
                        param.result = fakeIp
                    }
                }
            )
        } catch(e: Throwable) {}
    }

    // --- SKILL: Sensor Sync (Sine Wave) ---
    fun hookSensorData(classLoader: ClassLoader) {
        val sensorEventListenerClass = XposedHelpers.findClass("android.hardware.SensorEventListener", classLoader)
        var startTime = System.currentTimeMillis()
        
        XposedBridge.hookAllMethods(sensorEventListenerClass, "onSensorChanged", object : XC_MethodHook(50) {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    val event = param.args[0] as android.hardware.SensorEvent
                    
                    if (event.sensor.type == android.hardware.Sensor.TYPE_GYROSCOPE || 
                        event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                        
                            // SKILL: "Human Motion" Sync (Behavioral Liveness)
                        // Randomized 'Micro-Tremor' (0.002 rad/s variance)
                        val timeSec = (System.currentTimeMillis() - startTime) / 1000.0
                        
                        // Base breathing (Sine Wave)
                        val breathing = Math.sin(timeSec * 3.0) * 0.05
                        
                        // Micro-Tremor (High Frequency Noise)
                        var jitter = ((Math.random() * 0.002) + 0.001).toFloat() 
                        if (Math.random() > 0.5) jitter *= -1
                        
                        // DOWNWARD MODE CHECK
                        val downwardMode = File("/sdcard/vircam_downward").exists()
                        if (downwardMode) {
                            // Emulate 70-degree Forward Tilt (Gravity on Z and Y axes)
                            // 9.8 * cos(70) ~ 3.4 (Z)
                            // 9.8 * sin(70) ~ 9.2 (Y)
                            if (event.sensor.type == android.hardware.Sensor.TYPE_ACCELEROMETER) {
                                event.values[0] = 0f + jitter // X ~ 0
                                event.values[1] = 9.2f + jitter // Y ~ 9.2 (Gravity component)
                                event.values[2] = 3.4f + jitter // Z ~ 3.4 (Gravity component)
                                return
                            }
                        }

                        // Apply to X axis (Tilt)
                        event.values[0] += breathing.toFloat() + jitter.toFloat()
                        
                        // Add Jitter (Noise) to other axes
                        event.values[1] += ((Math.random() * 0.002) + 0.001).toFloat() * (if (Math.random() > 0.5) 1 else -1)
                        event.values[2] += ((Math.random() * 0.002) + 0.001).toFloat() * (if (Math.random() > 0.5) 1 else -1)
                    }
                } catch(e: Throwable) { }
            }
        })
    }

    // --- SKILL: Hardware Capability Bypass ---
    fun hookHardwareCapabilities(classLoader: ClassLoader) {
         XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.impl.CameraMetadataNative",
            classLoader,
            "get",
            android.hardware.camera2.CameraCharacteristics.Key::class.java,
            object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val key = param.args[0] as android.hardware.CameraCharacteristics.Key<*>
                    if (key == android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) {
                        // Return only STANDARD capabilities. Hide RAW, DEPTH if not needed, definitively hide STREAM_USE_CASE if suspicious.
                        // For Pixel 4a 5G, usually BACKWARD_COMPATIBLE is key.
                        param.result = intArrayOf(
                            android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE,
                            android.hardware.camera2.CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                        )
                    }
                }
            }
        )
    }

    // --- SKILL: Deep System Spoofing (Build Props) ---
    fun hookSystemBuild(classLoader: ClassLoader) {
        // Only for TARGET Apps, hook Build class directly to ensure internal consistency
        val buildClass = XposedHelpers.findClass("android.os.Build", classLoader)
        
        XposedHelpers.setStaticObjectField(buildClass, "MODEL", "Pixel 4a (5G)")
        XposedHelpers.setStaticObjectField(buildClass, "MANUFACTURER", "Google")
        XposedHelpers.setStaticObjectField(buildClass, "DEVICE", "bramble")
        XposedHelpers.setStaticObjectField(buildClass, "PRODUCT", "bramble")
        XposedHelpers.setStaticObjectField(buildClass, "FINGERPRINT", "google/bramble/bramble:14/UP1A.231105.001.B2/11065275:user/release-keys")
    }

    // --- SKILL: Verification Debugger (Diagnostic Overlay) ---
    fun hookDiagnosticOverlay(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.app.Activity",
            classLoader,
            "onResume",
            object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val activity = param.thisObject as Activity
                    val window = activity.window
                    val decorView = window.decorView as ViewGroup
                    
                    // Avoid duplicate adds
                    if (decorView.findViewWithTag<android.view.View>("VIRCAM_OVERLAY") != null) return

                    val statsText = TextView(activity).apply {
                        tag = "VIRCAM_OVERLAY"
                        text = "STREAM: CONNECTED | METADATA: GOOGLE_BRAMBLE | TILT: DOWN"
                        textSize = 10f
                        setTextColor(Color.GREEN)
                        setShadowLayer(1f, 0f, 0f, Color.BLACK)
                        setBackgroundColor(Color.parseColor("#40000000"))
                        setPadding(5, 5, 5, 5)
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            gravity = Gravity.TOP or Gravity.START
                            topMargin = 50
                            leftMargin = 10
                        }
                    }
                    
                    // Simple Visibility Toggle (No Pulse)
                    Thread {
                         while(true) {
                             try {
                                 Thread.sleep(1000)
                                 val isDownward = File("/sdcard/vircam_downward").exists()
                                 activity.runOnUiThread {
                                     statsText.text = if(isDownward) 
                                        "STREAM: CONNECTED | METADATA: GOOGLE_BRAMBLE | TILT: DOWN"
                                     else 
                                        "STREAM: CONNECTED | METADATA: GOOGLE_BRAMBLE | TILT: FRONT"
                                 }
                             } catch(e:Exception){}
                         }
                    }.start()

                    decorView.addView(statsText)
                }
            }
        )
    }

    // --- SKILL: Verification Debugger (Frame Capture) ---
    fun hookVerificationCapture(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.impl.CameraCaptureSessionImpl", // Often the impl class
                classLoader,
                "capture",
                "android.hardware.camera2.CaptureRequest",
                "android.hardware.camera2.CameraCaptureSession.CaptureCallback",
                "android.os.Handler",
                object : XC_MethodHook(50) {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            XposedBridge.log("Vircam: Capture Requested! Triggering Dump.")
                            // Write trigger file for RTMP service to save the frame
                            val triggerDir = File("/sdcard/vircam_debug")
                            if (!triggerDir.exists()) triggerDir.mkdirs()
                            File(triggerDir, "capture_trigger").writeText("1")
                        } catch(e: Exception) {
                            XposedBridge.log("Vircam Capture Hook Failed: ${e.message}")
                        }
                    }
                }
            )
        } catch(e: Throwable) {
            XposedBridge.log("Vircam: Could not hook Capture Session (Might be different class name on device)")
        }
    }

    // --- SKILL: Verification Reasoning (Reasoning Logger: Sniffer) ---
    fun hookNetworkSniffer(lpparam: LoadPackageParam) {
        try {
            // Hook HttpURLConnection to inspect 400/403 errors (Reasoning)
            val shim = object : XC_MethodHook(50) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val conn = param.thisObject as java.net.HttpURLConnection
                        val code = conn.responseCode
                        if (code >= 400) {
                            val errorStream = conn.errorStream
                            val response = errorStream?.bufferedReader()?.use { it.readText() } ?: "[No Body]"
                            XposedBridge.log("VIRCAM_DEBUG: Reasoning (Error $code): $response")
                            
                            // Dump to /sdcard/vircam_verify/reason.log
                            val logFile = File("/sdcard/vircam_verify/reason.log")
                            if(!logFile.parentFile.exists()) logFile.parentFile.mkdirs()
                            logFile.appendText("Error $code: $response\n")
                        }
                    } catch(e: Exception) {}
                }
            }
            
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", lpparam.classLoader, "getInputStream", shim)
            XposedHelpers.findAndHookMethod("javax.net.ssl.HttpsURLConnection", lpparam.classLoader, "getInputStream", shim)
            
        } catch(e: Throwable) {
            XposedBridge.log("Vircam Sniffer Error: ${e.message}")
        }
    }

    // --- SKILL: Verification Reasoning (ImageReader Hook & FPS) ---
    fun hookVerificationReasoning(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.media.ImageReader",
                classLoader,
                "acquireLatestImage",
                object : XC_MethodHook(50) {
                    override fun afterHookedMethod(param: MethodHookParam) {
                       try {
                           // FPS Calculation
                           val now = System.currentTimeMillis()
                           fpsCounter++
                           if (now - lastFpsTime >= 1000) {
                               currentFps = fpsCounter
                               fpsCounter = 0
                               lastFpsTime = now
                               // Write FPS to debug file for Overlay to pick up
                               File("/sdcard/vircam_debug/stats.txt").writeText("FPS:$currentFps")
                           }

                           val activeFile = File("/sdcard/Download/vircam_active.txt")
                           if(activeFile.exists() && activeFile.readText().trim() == "0") return

                           val image = param.result as? android.media.Image ?: return
                           
                           val format = image.format
                           val width = image.width
                           val height = image.height
                           
                           // Buffer Logic (Copy bytes for buffer)
                           if (image.format == 256) { // JPEG
                               val planes = image.planes
                               val buffer = planes[0].buffer
                               val bytes = ByteArray(buffer.remaining())
                               buffer.get(bytes)
                               buffer.rewind()
                               
                               synchronized(frameBuffer) {
                                   if (frameBuffer.size >= MAX_BUFFER_SIZE) frameBuffer.removeFirst()
                                   frameBuffer.add(bytes)
                               }
                               
                               // Save diagnostic shot (throttled)
                               val lastSave = File("/sdcard/vircam_verify/last_save_ts")
                               if(!lastSave.exists() || (now - lastSave.lastModified()) > 1000) {
                                   saveDebugImage(bytes, width, height) // Updated sig
                                   lastSave.writeText(now.toString())
                               }
                           }

                       } catch(e: Throwable) {
                           XposedBridge.log("Vircam Reasoning Error: ${e.message}")
                       }
                    }
                }
            )

        } catch(e: Throwable) {
             XposedBridge.log("Vircam: ImageReader Hook Error: ${e.message}")
        }
    }

    private fun saveDebugImage(bytes: ByteArray, w: Int, h: Int) {
        try {
            val dir = File("/sdcard/vircam_debug")
            if (!dir.exists()) dir.mkdirs()
            
            // Frame Audit (The "Black Box" Verification Logic)
            val file = File(dir, "fail_reason.jpg")
            FileOutputStream(file).use { it.write(bytes) }
            
            // Audit Log
            val log = File(dir, "audit.log")
            val msg = "[${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}] Frame Captured (${w}x${h}) | Downward Mode: ${File("/sdcard/vircam_downward").exists()}\n"
            log.appendText(msg)
            
        } catch (e: Exception) {}
    }

    // --- SKILL: CAPTURE LOGGER ---
    fun hookCaptureCallback(lpparam: LoadPackageParam) {
        val captureCallbackClass = XposedHelpers.findClass("android.hardware.camera2.CameraCaptureSession.CaptureCallback", lpparam.classLoader)
        
        XposedBridge.hookAllMethods(captureCallbackClass, "onCaptureCompleted", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val result = param.args[2] as android.hardware.camera2.TotalCaptureResult
                    val iso = result.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
                    val focal = result.get(android.hardware.camera2.CaptureResult.LENS_FOCAL_LENGTH)
                    val expTime = result.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
                    
                    val logMsg = "CAPTURE: ISO=$iso Focal=$focal Exp=$expTime\n"
                    val file = File("/sdcard/vircam_params.log")
                    file.appendText(logMsg)
                    XposedBridge.log("VIRCAM_DEBUG: $logMsg")
                } catch(e: Exception) {
                    XposedBridge.log("VIRCAM_DEBUG: Capture Logger Error: $e")
                }
            }
        })
    }

    // --- SKILL: ERROR INTERCEPTOR & FAIL RECOVERY ---
    fun hookErrorInterceptor(lpparam: LoadPackageParam) {
        XposedHelpers.findAndHookMethod(TextView::class.java, "setText", CharSequence::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val text = param.args[0]?.toString() ?: return
                    if (text.contains("Unable to verify", ignoreCase = true) || 
                        text.contains("Failed", ignoreCase = true) ||
                        text.contains("try again", ignoreCase = true)) {
                            
                        XposedBridge.log("VIRCAM_DEBUG: Verification FAIL Detected: $text")
                        
                        // 1. Screenshot UI
                        val view = param.thisObject as TextView
                        view.post {
                            try {
                                val rootView = view.rootView
                                rootView.isDrawingCacheEnabled = true
                                val bitmap = Bitmap.createBitmap(rootView.drawingCache)
                                rootView.isDrawingCacheEnabled = false
                                File("/sdcard/vircam_debug/fail_ui.png").writeBitmap(bitmap, Bitmap.CompressFormat.PNG, 100)
                            } catch(e: Exception) {}
                        }

                        // 2. Dump Frame Buffer (Fail Recovery)
                        synchronized(frameBuffer) {
                            val dir = File("/sdcard/vircam_debug/crash_dump_${System.currentTimeMillis()}")
                            if (!dir.exists()) dir.mkdirs()
                            frameBuffer.forEachIndexed { i, bytes ->
                                File(dir, "frame_$i.jpg").writeBytes(bytes)
                            }
                            XposedBridge.log("VIRCAM_DEBUG: Dumped ${frameBuffer.size} frames to ${dir.absolutePath}")
                        }
                    }
                } catch(e: Exception) {}
            }
        })
    }

    // --- SKILL: Identity Sync (SystemProperties) ---
    fun hookSystemProperties(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod("android.os.SystemProperties", classLoader, "get", String::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
               val key = param.args[0] as String
               if (key == "ro.build.fingerprint") param.result = "google/bramble/bramble:11/RQ3A.211001.001/7641976:user/release-keys"
               if (key == "ro.product.model") param.result = "Pixel 4a (5G)"
            }
        })
    }

    // --- SKILL: BLACK BOX LOGGER ---
    fun hookBlackBoxLogger(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.hardware.camera2.CameraDevice.StateCallback",
            classLoader,
            "onOpened",
            "android.hardware.camera2.CameraDevice",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val packageName = lpparam.packageName
                        val logMsg = "[${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}] Camera Opened by: $packageName | Specs: Pixel 4a 5G (f/1.7, 4.38mm)\n"
                        val logFile = File("/sdcard/vircam_log.txt")
                        if (!logFile.exists()) logFile.createNewFile()
                        logFile.appendText(logMsg)
                        XposedBridge.log("VIRCAM_DEBUG: $logMsg")
                    } catch (e: Exception) {
                        XposedBridge.log("VIRCAM_DEBUG: Logger Failed: ${e.message}")
                    }
                }
            }
        )
    }

    // --- SKILL: Package Isolation (Anti-Forensics) ---
    fun hookPackageManager(classLoader: ClassLoader) {
        val pmClass = try { XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader) } catch(e:Throwable) { return }
        
        XposedBridge.hookAllMethods(pmClass, "getInstalledPackages", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val packages = param.result as? MutableList<android.content.pm.PackageInfo> ?: return
                    val iterator = packages.iterator()
                    while (iterator.hasNext()) {
                        val pkg = iterator.next()
                        if (pkg.packageName.contains("vircam", ignoreCase = true) ||
                            pkg.packageName.contains("lsposed", ignoreCase = true) ||
                            pkg.packageName.contains("joystick", ignoreCase = true) ||
                            pkg.packageName == "com.topjohnwu.magisk") {
                            iterator.remove()
                            XposedBridge.log("VIRCAM_STEALTH: Hid package ${pkg.packageName}")
                        }
                    }
                } catch (e: Exception) {}
            }
        })
    }
}

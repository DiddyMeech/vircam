package com.example.vircam

import android.app.Activity
import android.content.pm.PackageInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.widget.TextView
import android.graphics.Color
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName == "com.android.systemui") return // Safety
        
        // 4. INVISIBLE MODE (Hide Vircam & Joystick)
        hookPackageManager(lpparam)

        // 1. IDENTITY SPOOFING (Pixel 4a 5G)
        spoofDeviceIdentity(lpparam)
        hookTelephony(lpparam)

        if (isFileExists("/sdcard/vircam_stop")) return // Panic switch

        try {
            hookCamera(lpparam.classLoader)
            hookSensors(lpparam.classLoader)
            hookUIForReasoning(lpparam.classLoader)
            
        } catch (t: Throwable) {
            XposedBridge.log(t)
        }
    }

    private fun isFileExists(path: String) = File(path).exists()

    // 1. IDENTITY SPOOFING
    private fun spoofDeviceIdentity(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", "Pixel 4a (5G)")
        XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", "bramble")
        XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", "bramble")
        XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", "Google")
        XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", "google")
        XposedHelpers.setStaticObjectField(Build::class.java, "FINGERPRINT", "google/bramble/bramble:13/TQ3A.230901.001/10750989:user/release-keys")
    }

    private fun hookTelephony(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook IMEI retrieval (simplistic approach for common methods)
            // Note: Modern Android uses simpler ID access restrictions, but legacy apps check this.
            val tmClass = XposedHelpers.findClass("android.telephony.TelephonyManager", lpparam.classLoader)
            
            val replacement = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // TAC 35364411 (Pixel 4a 5G) + Random 7 digits
                    param.result = "35364411${(1000000..9999999).random()}" 
                }
            }
            
            XposedHelpers.findAndHookMethod(tmClass, "getDeviceId", replacement)
            XposedHelpers.findAndHookMethod(tmClass, "getImei", replacement)
            XposedHelpers.findAndHookMethod(tmClass, "getImei", Int::class.javaPrimitiveType, replacement)
        } catch (e: Throwable) {}
    }

    // 2. VERIFICATION REASONING (Dump logs on "Unable to verify")
    private fun hookUIForReasoning(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.widget.TextView",
            classLoader,
            "setText",
            CharSequence::class.java,
            android.widget.TextView.BufferType::class.java,
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, 
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val text = param.args[0]?.toString() ?: return
                    if (text.contains("Unable to verify", ignoreCase = true) || text.contains("Verification failed", ignoreCase = true)) {
                        // TRIGGER DUMP
                        val logFile = File("/sdcard/vircam_debug/reasoning_dump.txt")
                        if (!logFile.parentFile.exists()) logFile.parentFile.mkdirs()
                        
                        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                        logFile.appendText("[$date] FAILURE DETECTED: '$text'\n")
                        logFile.appendText("Device: ${Build.MODEL}, Hook Active: ${!isFileExists("/sdcard/vircam_stop")}\n")
                        logFile.appendText("Env: Downward=${isFileExists("/sdcard/vircam_downward")}\n\n")
                    }
                }
            }
        )
    }

    // 3. ENVIRONMENTAL LIVENESS & CAMERA
    private fun hookSensors(classLoader: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            "android.hardware.SensorManager",
            classLoader,
            "registerListener",
            SensorEventListener::class.java,
            Sensor::class.java,
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] as? SensorEventListener ?: return
                    val sensor = param.args[1] as? Sensor ?: return
                    
                    val wrapper = object : SensorEventListener {
                        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
                            listener.onAccuracyChanged(s, accuracy)
                        }

                        override fun onSensorChanged(event: SensorEvent) {
                             // Environmental Liveness: Sync Accelerometer to "Looking Down" (+ Jitter)
                             if (isFileExists("/sdcard/vircam_downward") && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                                val jitter = ((Math.random() * 0.002) - 0.001).toFloat()
                                event.values[0] = 0.0f + jitter
                                event.values[1] = 9.2f + jitter // Y Gravity
                                event.values[2] = 3.4f + jitter // Z Gravity
                             }
                             
                             // Sync Light Sensor (Simple Liveness: Ensure it's not 0 or abnormally flat)
                             if (event.sensor.type == Sensor.TYPE_LIGHT) {
                                 // Modulate light slightly to simulate hand shadow/movement
                                 val baseLight = event.values[0]
                                 if (baseLight > 0) {
                                     event.values[0] = baseLight + ((Math.random() * 10) - 5).toFloat()
                                 }
                             }
                             
                             listener.onSensorChanged(event)
                        }
                    }
                    param.args[0] = wrapper
                }
            }
        )
    }

    private fun hookCamera(classLoader: ClassLoader) {
        // Macro Focus for "Desk" Perspective
        try {
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CaptureRequest\$Builder",
                classLoader,
                "set",
                android.hardware.camera2.CaptureRequest.Key::class.java,
                Any::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as android.hardware.camera2.CaptureRequest.Key<*>
                        if (key == android.hardware.camera2.CaptureRequest.LENS_FOCUS_DISTANCE) {
                            if (isFileExists("/sdcard/vircam_downward")) {
                                param.args[1] = 5.0f // 0.2m
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {}
    }

    // 4. INVISIBLE MODE
    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)
        // Hook getInstalledPackages to remove our traces
        XposedHelpers.findAndHookMethod(pmClass, "getInstalledPackages", Int::class.javaPrimitiveType, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val packages = param.result as? MutableList<PackageInfo> ?: return
                packages.removeIf { it.packageName.contains("vircam") || it.packageName.contains("joystick") }
            }
        })
    }
}

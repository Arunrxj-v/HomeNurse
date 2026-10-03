package com.homenurse.core.model

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import com.homenurse.ai.ModelManifestEntry
import java.io.File

/**
 * Device capability checks for local Gemma inference.
 *
 * Blockers are hard physical constraints documented by the runtime/artifact:
 *  * CPU architecture must be one the LiteRT-LM runtime ships native
 *    libraries for (arm64-v8a or x86_64 — verified from the published AAR).
 *  * Free storage must fit the model file (plus room for the runtime cache).
 *  * Android version must satisfy the model manifest's minSdk (which is at
 *    least the LiteRT-LM requirement of API 24 and ML Kit's API 23).
 *
 * RAM is reported as a recommendation: the model must fit into RAM alongside
 * the app, so a device with less memory than roughly the model size is likely
 * to be slow or be killed by the system. This is a physical-availability
 * heuristic, clearly labelled as such in the UI — not a vendor-published
 * hard requirement (Google does not publish a minimum RAM figure).
 */
data class DeviceCapabilities(
    val totalRamBytes: Long,
    val availableStorageBytes: Long,
    val sdkInt: Int,
    val abis: List<String>,
)

data class CapabilityReport(
    val blockers: List<String>,
    val warnings: List<String>,
) {
    val isSupported: Boolean get() = blockers.isEmpty()
}

object DeviceCapabilityChecker {

    private val supportedAbis = setOf("arm64-v8a", "x86_64")

    fun current(context: Context): DeviceCapabilities {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val filesDir: File = context.filesDir
        val stat = StatFs(filesDir.path)
        val available = stat.availableBlocksLong * stat.blockSizeLong
        return DeviceCapabilities(
            totalRamBytes = memoryInfo.totalMem,
            availableStorageBytes = available,
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
        )
    }

    fun check(caps: DeviceCapabilities, model: ModelManifestEntry): CapabilityReport {
        val blockers = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (caps.abis.none { it in supportedAbis }) {
            blockers += "cpu_arch"
        }
        if (caps.sdkInt < model.minSdk) {
            blockers += "android_version"
        }
        // Model file + a 10% margin for the runtime's cache dir.
        if (caps.availableStorageBytes < (model.sizeBytes * 11 / 10)) {
            blockers += "storage"
        }
        if (caps.totalRamBytes in 1 until model.recommendedRamBytes) {
            warnings += "low_ram"
        }
        return CapabilityReport(blockers = blockers, warnings = warnings)
    }

    fun availableStorage(context: Context): Long {
        val stat = StatFs(context.filesDir.path)
        return stat.availableBlocksLong * stat.blockSizeLong
    }
}

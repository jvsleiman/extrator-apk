package com.joao.extratorapk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val sourceDir: String,
    val splitDirs: List<String>,
    val sizeBytes: Long,
) {
    val isSplit: Boolean get() = splitDirs.isNotEmpty()
}

object AppRepository {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val apps = if (Build.VERSION.SDK_INT >= 33) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        return apps.mapNotNull { info -> toEntry(pm, info) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun toEntry(pm: PackageManager, info: ApplicationInfo): AppEntry? {
        val source = info.sourceDir ?: return null
        if (!File(source).canRead()) return null
        val splits = info.splitSourceDirs?.toList().orEmpty()
        val pkgInfo = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(info.packageName, 0)
            }
        }.getOrNull()
        val versionCode = when {
            pkgInfo == null -> 0L
            Build.VERSION.SDK_INT >= 28 -> pkgInfo.longVersionCode
            else -> @Suppress("DEPRECATION") pkgInfo.versionCode.toLong()
        }
        val size = (listOf(source) + splits).sumOf { File(it).length() }
        return AppEntry(
            packageName = info.packageName,
            label = info.loadLabel(pm).toString().ifBlank { info.packageName },
            versionName = pkgInfo?.versionName ?: "",
            versionCode = versionCode,
            isSystem = info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            sourceDir = source,
            splitDirs = splits,
            sizeBytes = size,
        )
    }
}

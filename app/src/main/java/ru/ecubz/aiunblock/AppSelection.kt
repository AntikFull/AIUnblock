package ru.ecubz.aiunblock

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

data class SelectableApp(
    val packageName: String,
    val label: String,
    val isBuiltIn: Boolean,
)

object AppSelection {
    private const val PREFERENCES_NAME = "selected_apps"
    private const val CUSTOM_PACKAGES_KEY = "custom_packages"

    val builtInPackages = setOf(
        "com.google.android.apps.bard",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.labs.language.tailwind",
        "com.openai.chatgpt",
        "com.anthropic.claude",
    )

    fun loadCustomPackages(context: Context): Set<String> =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getStringSet(CUSTOM_PACKAGES_KEY, emptySet())
            .orEmpty()
            .toSet()

    fun saveCustomPackages(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(CUSTOM_PACKAGES_KEY, packages.toSet())
            .apply()
    }

    fun activePackages(context: Context): Set<String> =
        builtInPackages + loadCustomPackages(context)

    fun loadLaunchableApps(context: Context): List<SelectableApp> {
        val packageManager = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
        }

        return activities
            .asSequence()
            .mapNotNull { activity ->
                val packageName = activity.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                SelectableApp(
                    packageName = packageName,
                    label = activity.loadLabel(packageManager).toString().ifBlank { packageName },
                    isBuiltIn = packageName in builtInPackages,
                )
            }
            .distinctBy(SelectableApp::packageName)
            .sortedWith(
                compareByDescending<SelectableApp> { it.isBuiltIn }
                    .thenBy { it.label.lowercase(Locale.ROOT) },
            )
            .toList()
    }
}

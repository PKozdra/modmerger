// src/main/kotlin/com/dominions/modmerger/ui/ModMergerController.kt
package com.dominions.modmerger.ui

import com.dominions.modmerger.MergeResult
import com.dominions.modmerger.constants.GameConstants
import com.dominions.modmerger.core.ModMergerService
import com.dominions.modmerger.domain.*
import com.dominions.modmerger.infrastructure.FileSystem
import com.dominions.modmerger.infrastructure.GamePathsManager
import com.dominions.modmerger.ui.model.ModListItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.io.File
import javax.swing.SwingUtilities

class ModMergerController(
    private val modMergerService: ModMergerService,
    private val fileSystem: FileSystem,
    private val gamePathsManager: GamePathsManager,
    private val logDispatcher: LogDispatcher
) {
    private val logger = KotlinLogging.logger {}
    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var modLoadListener: ((List<ModListItem>) -> Unit)? = null

    fun setModLoadListener(listener: (List<ModListItem>) -> Unit) {
        modLoadListener = listener
    }

    fun loadMods(customPathText: String) {
        val paths = buildList {
            // Add Steam workshop path if available
            gamePathsManager.findSteamModPath()?.let {
                add(it)
                logDispatcher.log(LogLevel.INFO, "Found Steam workshop path: $it")
            }

            // Add local mods directory
            add(gamePathsManager.getLocalModPath().also {
                logDispatcher.log(LogLevel.INFO, "Using local mods path: $it")
            })

            // Add custom path if provided
            customPathText.takeIf { it.isNotBlank() }?.let {
                File(it).also { file ->
                    add(file)
                    logDispatcher.log(LogLevel.INFO, "Using custom path: $file")
                }
            }
        }

        if (paths.isEmpty()) {
            logDispatcher.log(LogLevel.WARN, "No valid mod paths found. Please specify a custom path.")
            return
        }

        val modItems = mutableListOf<ModListItem>()
        var totalMods = 0

        paths.forEach { path ->
            try {
                val mods = findModFiles(path)
                mods.forEach { modFile ->
                    modItems.add(ModListItem(modFile))
                    totalMods++
                }
            } catch (e: Exception) {
                logger.error(e) { "Error loading mods from $path" }
                logDispatcher.log(LogLevel.ERROR, "Error loading mods from $path: ${e.message}")
            }
        }

        SwingUtilities.invokeLater {
            modLoadListener?.invoke(modItems)
        }
        logDispatcher.log(LogLevel.INFO, "Found $totalMods total mods")
    }

    fun mergeMods(mods: List<ModFile>, onMergeCompleted: () -> Unit) {
        logDispatcher.log(LogLevel.INFO, "Starting merge of ${mods.size} mods...")

        coroutineScope.launch {
            try {
                logDispatcher.log(LogLevel.INFO, "Processing mods: ${mods.joinToString { it.name }}")
                val result = modMergerService.processMods(mods)
                SwingUtilities.invokeLater {
                    when (result) {
                        is MergeResult.Success -> {
                            logDispatcher.log(LogLevel.INFO, "Merge completed successfully!")
                            if (result.warnings.isNotEmpty()) {
                                logDispatcher.log(LogLevel.WARN, "Warnings encountered during merge:")
                                result.warnings.forEach { warning ->
                                    logDispatcher.log(LogLevel.WARN, "- $warning")
                                }
                            }
                        }

                        is MergeResult.Failure -> {
                            logDispatcher.log(LogLevel.ERROR, "Merge failed: ${result.error}")
                        }
                    }
                    onMergeCompleted()
                }
            } catch (e: Exception) {
                logger.error(e) { "Error during merge" }
                SwingUtilities.invokeLater {
                    logDispatcher.log(LogLevel.ERROR, "Error during merge: ${e.message}")
                    onMergeCompleted()
                }
            }
        }
    }

    private fun findModFiles(path: File): List<ModFile> {
        return path.walkTopDown()
            .filter { it.isFile && it.extension == GameConstants.MOD_FILE_EXTENSION }
            .map { ModFile.fromFile(it) }
            .toList()
    }
}

package com.dominions.modmerger.core.writing

import com.dominions.modmerger.constants.ModPatterns
import com.dominions.modmerger.core.processing.EntityProcessor
import com.dominions.modmerger.core.processing.SpellBlockProcessor
import com.dominions.modmerger.domain.MappedModDefinition
import com.dominions.modmerger.domain.MergeWarning
import com.dominions.modmerger.infrastructure.Logging
import com.dominions.modmerger.utils.ModUtils
import java.io.Writer

class ModContentWriter(
    private val entityProcessor: EntityProcessor,
    private val spellBlockProcessor: SpellBlockProcessor = SpellBlockProcessor(),
) : Logging {
    private val warnings = mutableListOf<MergeWarning>()

    private data class ModProcessingContext(
        var inMultilineDescription: Boolean = false,
        var processedLines: MutableList<String> = mutableListOf()
    )

    fun processModContent(
        mappedDefinitions: Map<String, MappedModDefinition>,
        writer: Writer
    ): List<MergeWarning> {
        warnings.clear()
        try {
            debug("Starting to process ${mappedDefinitions.size} mod definitions")

            val totalStartTime = System.currentTimeMillis()
            mappedDefinitions.forEach { (name, mappedDef) ->
                val modStartTime = System.currentTimeMillis()
                debug("Processing mod: $name with ${mappedDef.modFile.content.lines().size} lines")
                writer.write("\n-- Begin content from mod: $name\n")
                processModFile(mappedDef, writer)
                val modEndTime = System.currentTimeMillis()
                info("Processed mod '$name' in ${modEndTime - modStartTime} ms")
            }
            val totalEndTime = System.currentTimeMillis()
            writer.write("\n-- End merged content\n")

            info("Total mod content processing time: ${totalEndTime - totalStartTime} ms")
            info("Successfully wrote mod content with ${warnings.size} warnings")

        } catch (e: Exception) {
            val errorMsg = "Failed to process mod content: ${e.message}"
            error(errorMsg, e)
            warnings.add(
                MergeWarning.GeneralWarning(
                    message = errorMsg,
                    modFile = null
                )
            )
            throw ModContentProcessingException("Failed to process mod content", e)
        }

        return warnings
    }

    private fun processModFile(mappedDef: MappedModDefinition, writer: Writer) {
        val lines = mappedDef.modFile.content.lines()
        val context = ModProcessingContext()

        debug("Starting to process mod file: ${mappedDef.modFile.name}")
        trace("File has ${lines.size} lines")

        var index = 0
        val totalLines = lines.size
        while (index < totalLines) {
            val lineStartTime = System.currentTimeMillis()
            try {
                index = processNextLine(lines, index, context, mappedDef)
            } catch (e: Exception) {
                val errorMsg = "Error processing line $index in ${mappedDef.modFile.name}: ${e.message}"
                error(errorMsg, e)
                debug("Problematic line content: ${lines.getOrNull(index)}")
                throw ModContentProcessingException(errorMsg, e)
            }
            val lineEndTime = System.currentTimeMillis()
            // Optional: Log processing time for each line if needed
            // log(LogLevel.TRACE, "Processed line $index in ${lineEndTime - lineStartTime} ms")
        }

        debug("Processed ${context.processedLines.size} lines for ${mappedDef.modFile.name}")
        writeProcessedLines(context.processedLines, writer)
    }

    private fun isDescriptionStart(line: String): Boolean =
        ModPatterns.MOD_DESCRIPTION_START.matches(line) && !ModPatterns.MOD_DESCRIPTION_LINE.matches(line)

    private fun shouldSkipLine(line: String, index: Int, context: ModProcessingContext): Boolean {
        return when {
            line.isEmpty() && index == 0 -> true
            context.inMultilineDescription -> {
                if (line.contains("\"")) context.inMultilineDescription = false
                true
            }

            isDescriptionStart(line) -> {
                context.inMultilineDescription = !line.endsWith("\"")
                true
            }

            isModInfoLine(line) -> true
            else -> false
        }
    }

    private fun isModInfoLine(line: String): Boolean =
        line.matches(ModPatterns.MOD_NAME) ||
                line.matches(ModPatterns.MOD_DESCRIPTION_LINE) ||
                line.matches(ModPatterns.MOD_ICON_LINE) ||
                line.matches(ModPatterns.MOD_VERSION_LINE) ||
                line.matches(ModPatterns.MOD_DOMVERSION_LINE)

    private fun processNextLine(
        lines: List<String>,
        currentIndex: Int,
        context: ModProcessingContext,
        mappedDef: MappedModDefinition
    ): Int {
        val line = lines[currentIndex]
        val trimmedLine = ModUtils.removeUnreadableCharacters(line)
        // remove all unreadable unicode characters from the line


        trace(
            "Processing line $currentIndex: ${if (trimmedLine.length > 50) trimmedLine.take(50) + "..." else trimmedLine}",
            useDispatcher = false
        )

        return when {
            shouldSkipLine(trimmedLine, currentIndex, context) -> {
                trace("Skipping line $currentIndex", useDispatcher = false)
                currentIndex + 1
            }

            ModPatterns.SPELL_BLOCK_START.matches(trimmedLine) -> {
                trace("Found spell block start at line $currentIndex", useDispatcher = false)
                spellBlockProcessor.startNewBlock(line)
                context.processedLines.add(line)
                processSpellBlock(lines, currentIndex + 1, mappedDef, context)
            }

            else -> {
                processRegularLine(line, mappedDef, context)
                currentIndex + 1
            }
        }
    }

    private fun createRemapComment(oldId: Long, newId: Long): String {
        val entityType = if (oldId > 0) "Monster" else "Montag"
        val absOldId = kotlin.math.abs(oldId)
        val absNewId = kotlin.math.abs(newId)
        return "-- MOD MERGER: Remapped $entityType $absOldId -> $absNewId"
    }

    private fun processRegularLine(
        line: String,
        mappedDef: MappedModDefinition,
        context: ModProcessingContext
    ) {
        val processed = entityProcessor.processEntity(line, mappedDef) { type, oldId, newId ->
            "-- MOD MERGER: Remapped ${type.name.lowercase().replaceFirstChar { it.titlecase() }} $oldId -> $newId"
        }

        processed.remapComment?.let { context.processedLines.add(it) }
        context.processedLines.add(processed.line)
    }

    private fun writeProcessedLines(lines: List<String>, writer: Writer) {
        var skipNextEnd = false
        var lastLineWasEnd = false

        lines.forEach { line ->
            val trimmedLine = line.trim()

            when {
                trimmedLine.startsWith("#end") -> {
                    if (!skipNextEnd) {
                        writer.write("$line\n")
                        skipNextEnd = true
                        lastLineWasEnd = true
                    }
                }

                trimmedLine.startsWith("--") -> {
                    // Always write comments
                    writer.write("$line\n")
                }

                else -> {
                    writer.write("$line\n")
                    skipNextEnd = false
                    lastLineWasEnd = false
                }
            }
        }
    }

    private fun processSpellBlock(
        lines: List<String>,
        startIndex: Int,
        mappedDef: MappedModDefinition,
        context: ModProcessingContext
    ): Int {
        var currentIndex = startIndex
        trace("Processing spell block starting at line $startIndex", useDispatcher = false)

        while (currentIndex < lines.size) {
            val line = lines[currentIndex]
            val trimmedLine = line.trim()

            when {
                ModPatterns.END.matches(trimmedLine) -> {
                    trace("Found spell block end at line $currentIndex", useDispatcher = false)
                    context.processedLines.add(line)
                    spellBlockProcessor.currentBlock = null
                    return currentIndex + 1
                }

                trimmedLine.startsWith("#damage") -> {
                    val processedBlock = spellBlockProcessor.processSpellLine(line, mappedDef)
                    processedBlock.damageMapping?.let { (oldId, newId) ->
                        trace("Remapping spell damage ID $oldId to $newId", useDispatcher = false)
                        context.processedLines.add(createRemapComment(oldId, newId))
                        context.processedLines.add(ModUtils.replaceId(line, oldId, newId))
                    } ?: context.processedLines.add(line)
                }

                else -> {
                    // Process non-damage lines normally without remap comments
                    context.processedLines.add(line)
                }
            }

            currentIndex++
        }

        return currentIndex
    }
}

class ModContentProcessingException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
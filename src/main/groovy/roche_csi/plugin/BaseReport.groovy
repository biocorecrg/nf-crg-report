package roche_csi.plugin

import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Abstract base class for all reports providing common functionality
 */
@Slf4j
abstract class BaseReport {

    protected Session session
    protected Map config
    protected String name
    protected String outputFilename
    protected boolean enabled = false
    protected String outputDir = './reports'
    protected String prefix = ''
    protected String suffix = ''
    // protected String format = 'json'
    protected List<String> format = ['json']
    protected String htmlTemplatePath = null
    protected Map<String, File> outputFiles = [:]
    protected List<String> fields = []
    protected boolean createLinkToLatestReport = false

    BaseReport(String name) {
        this.name = name
    }

    void init(Session session, Map config) {
        this.session = session
        this.config = config ?: [:]

        // Parse common configuration options
        this.enabled = config.enabled != null ? config.enabled as boolean : true
        this.outputDir = config.outputDir ?: './reports'
        this.outputFilename = config.outputFilename ?: name
        this.prefix = config.prefix ?: ''
        def timestamp = (session?.workflowMetadata?.start) ? session.workflowMetadata.start.format(java.time.format.DateTimeFormatter.ofPattern('yyyyMMddHHmmss')) : new Date().format('yyyyMMddHHmmss')
        this.suffix = config.suffix != null ? config.suffix : "-${timestamp}"
        if (config.format) {
            this.format = config.format instanceof List ? config.format as List<String> : [(config.format as String).toLowerCase()]
        } else {
            this.format = ['json']
        }
        this.htmlTemplatePath = config.htmlTemplatePath ?: null
        this.fields = config.fields instanceof List ? config.fields as List<String> : []
        this.createLinkToLatestReport = config.createLinkToLatestReport != null ? config.createLinkToLatestReport as boolean : false

        // Create output directory if it doesn't exist
        if (enabled) {
            def dir = new File(outputDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            for (String fmt : format) {
                createOutputFile(fmt, outputFilename)
            }
        }

        log.debug("Initialized ${name} report: enabled=${enabled}, outputDir=${outputDir}")
    }

    boolean isEnabled() {
        return enabled
    }

    String getName() {
        return name
    }

    /**
     * Create the output file with proper naming
     */
    protected void createOutputFile(String extension = null, String outputFilename = null) {
        def ext = extension ?: format
        def filename = "${prefix}${outputFilename ?: name}${suffix}.${ext}"
        outputFiles[ext] = new File(outputDir, filename)
    }

    /**
     * Write content to the output file
     */
    protected void writeToFile(String content, String format = 'json', boolean append = false) {
        def outputFile = outputFiles[format]
        if (!outputFile) {
            return
        }

        try {
            if (append) {
                outputFile << content
            } else {
                outputFile.text = content
            }

            if (createLinkToLatestReport) {
                def latestLink = new File(outputDir, "${outputFilename ?: name}-latest.${format}")
                Files.deleteIfExists(latestLink.toPath())
                Files.createSymbolicLink(latestLink.toPath(), outputFile.toPath().toAbsolutePath())
            }
        } catch (IOException e) {
            log.error("Failed to write to ${outputFile}: ${e.message}")
        }
    }

    /**
     * Get workflow information
     */
    protected Map getWorkflowInfo() {
        return [
            workflowName: session.workflowMetadata.scriptName ?: 'Unknown',
            sessionId: session.uniqueId,
            runName: session.runName,
            projectDir: session.baseDir.toString(),
            launchDir: session.workDir.toString(),
            startTime: session.workflowMetadata.start ?: new Date(),
            nextflowVersion: session.workflowMetadata.nextflow?.version ?: 'Unknown'
        ]
    }

    // Default implementations for report lifecycle methods
    void onWorkflowStart() {
        if (!enabled) {
            return
        }
        log.debug("${name} report: workflow started")
    }

    void onTaskStart(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }
        log.debug("${name} report: task started - ${handler.task.name}")
    }

    void onTaskComplete(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }
        log.debug("${name} report: task completed - ${handler.task.name}")
    }

    void onTaskCached(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }
        log.debug("${name} report: task cached - ${handler.task.name}")
    }

    void onFilePublish(Path destination, Path source) {
        if (!enabled) {
            return
        }
        log.debug("${name} report: file published - ${destination}")
    }

    void onWorkflowError(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }
        log.debug("${name} report: workflow error - ${handler?.task?.name}")
    }

    void onWorkflowComplete() {
        if (!enabled) {
            return
        }
        log.debug("${name} report: workflow completed")
    }

    void close() {
    // Override in subclasses if cleanup is needed
    }

    Map toMap() {
        [
            report_type: name,
            generated_at: Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        ]
    }

}

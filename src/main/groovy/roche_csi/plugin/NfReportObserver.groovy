package roche_csi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import java.nio.file.Path

/**
 * Implements a trace observer that generates various reports
 * during nextflow execution events.
 */
@Slf4j
class NfReportObserver implements TraceObserver {

    private Session session
    private List<BaseReport> reports = []
    private Map globalConfig = [:]
    private boolean enabled = true
    private Map emailConfig = [:]

    @Override
    boolean enableMetrics() {
        return true
    }

    @Override
    void onFlowCreate(Session session) {
        this.session = session

        // Parse plugin configuration
        parseConfiguration()

        if (!enabled) {
            log.info('nf-report plugin is disabled')
            return
        }

        // Initialize reports based on configuration
        initializeReports()

        log.info("nf-report plugin initialized with ${reports.size()} reports")

        // Notify all reports of workflow start
        reports.each { report ->
            try {
                report.onWorkflowStart()
            } catch (Exception e) {
                log.error("Error in ${report.name} report during workflow start: ${e.message}", e)
            }
        }
    }

    @Override
    void onProcessStart(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onTaskStart(handler, trace)
            } catch (Exception e) {
                log.error("Error in ${report.name} report during task submit: ${e.message}", e)
            }
        }
    }

    @Override
    void onProcessComplete(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onTaskComplete(handler, trace)
            } catch (Exception e) {
                log.error("Error in ${report.name} report during task complete: ${e.message}", e)
            }
        }
    }

    @Override
    void onProcessCached(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onTaskCached(handler, trace)
            } catch (Exception e) {
                log.error("Error in ${report.name} report during task cached: ${e.message}", e)
            }
        }
    }

    @Override
    void onFilePublish(Path destination, Path source) {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onFilePublish(destination, source)
            } catch (Exception e) {
                log.error("Error in ${report.name} report during file publish: ${e.message}", e)
            }
        }
    }

    @Override
    void onFlowError(TaskHandler handler, TraceRecord trace) {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onWorkflowError(handler, trace)
            } catch (Exception e) {
                log.error("Error in ${report.name} report during workflow error: ${e.message}", e)
            }
        }
    }

    @Override
    void onFlowComplete() {
        if (!enabled) {
            return
        }

        reports.each { report ->
            try {
                report.onWorkflowComplete()
            } catch (Exception e) {
                log.error("Error in ${report.name} report during workflow complete: ${e.message}", e)
            }
        }

        // Send email notification if configured
        if (emailConfig.enabled) {
            EmailNotification.sendEmailNotification(emailConfig, reports)
        }

        // Clean up reports
        reports.each { report ->
            try {
                report.close()
            } catch (Exception e) {
                log.error("Error closing ${report.name} report: ${e.message}", e)
            }
        }

        log.info('nf-report plugin completed reporting')
    }

    private Map evaluateClosures(Map config) {
        config.collectEntries { key, value ->
            if (value instanceof Closure) {
                try {
                    [(key): value.call()]
                } catch (Exception e) {
                    log.warn("Failed to evaluate closure for config key '${key}': ${e.message}")
                    [(key): null]
                }
            } else if (value instanceof Map) {
                [(key): evaluateClosures(value)]
            } else {
                [(key): value]
            }
        }
    }

    private void parseConfiguration() {
        try {
            // Get plugin configuration from session
            def pluginConfig = session.config.navigate('nfreport') as Map

            if (!pluginConfig) {
                log.debug('No nfreport configuration found, using defaults')
                pluginConfig = [:]
            }
            // Recursively evaluate closures in the configuration
            pluginConfig = evaluateClosures(pluginConfig)

            globalConfig = pluginConfig
            enabled = pluginConfig.enabled != null ? pluginConfig.enabled as boolean : true

            emailConfig = pluginConfig.email ?: [:]

            log.debug("nf-report configuration: ${pluginConfig}")
        } catch (Exception e) {
            log.warn("Failed to parse nf-report configuration, using defaults: ${e.message}")
            globalConfig = [:]
            enabled = true
        }
    }

    private void initializeReports() {
        reports.clear()

        // Global configuration options
        def baseOutputDir = globalConfig.outputDir ?: './reports'
        def basePrefix = globalConfig.prefix ?: ''
        def baseSuffix = globalConfig.suffix ?: ''
        def baseFormat = globalConfig.format?.toString()?.toLowerCase() ?: 'json'
        def createLinkToLatestReport = globalConfig.createLinkToLatestReport != null ? globalConfig.createLinkToLatestReport as boolean : false

        // Initialize Execution Report
        def executionConfig = (globalConfig.executionReport as Map) ?: [:]
        executionConfig = mergeGlobalConfig(executionConfig, baseOutputDir, basePrefix, baseSuffix, baseFormat, createLinkToLatestReport)
        if (executionConfig.enabled == true) {
            def executionReport = new ExecutionReport()
            executionReport.init(session, executionConfig)
            if (executionReport.enabled) {
                reports << executionReport
            }
        }

        // Initialize Task Status Report
        def taskStatusConfig = (globalConfig.taskStatusReport as Map) ?: [:]
        taskStatusConfig = mergeGlobalConfig(taskStatusConfig, baseOutputDir, basePrefix, baseSuffix, baseFormat, createLinkToLatestReport)
        if (taskStatusConfig.enabled == true) {
            def taskStatusReport = new TaskStatusReport()
            taskStatusReport.init(session, taskStatusConfig)
            if (taskStatusReport.enabled) {
                reports << taskStatusReport
            }
        }

        // Initialize Sample Status Report
        def sampleStatusConfig = (globalConfig.sampleStatusReport as Map) ?: [:]
        sampleStatusConfig = mergeGlobalConfig(sampleStatusConfig, baseOutputDir, basePrefix, baseSuffix, baseFormat, createLinkToLatestReport)
        if (sampleStatusConfig.enabled == true) {
            def sampleStatusReport = new SampleStatusReport()
            sampleStatusReport.init(session, sampleStatusConfig)
            if (sampleStatusReport.enabled) {
                reports << sampleStatusReport
            }
        }

        log.info("Initialized ${reports.size()} reports: ${reports.collect { it.name }.join(', ')}")
    }

    private Map mergeGlobalConfig(Map reportConfig, String baseOutputDir, String basePrefix, String baseSuffix, String baseFormat, boolean createLinkToLatestReport) {
        def merged = [:] + reportConfig

        // Apply global defaults if not overridden
        if (!merged.containsKey('outputDir')) {
            merged.outputDir = baseOutputDir
        }
        if (!merged.containsKey('prefix')) {
            merged.prefix = basePrefix
        }
        if (!merged.containsKey('suffix')) {
            merged.suffix = baseSuffix
        }
        if (!merged.containsKey('format')) {
            merged.format = baseFormat
        }
        if (!merged.containsKey('createLinkToLatestReport')) {
            merged.createLinkToLatestReport = createLinkToLatestReport
        }

        return merged
    }

}

package roche_csi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceObserver
import nextflow.trace.TraceRecord
import roche_csi.plugin.config.NfReportConfig
import java.nio.file.Path

/**
 * Implements a trace observer that generates various reports
 * during nextflow execution events.
 */
@Slf4j
class NfReportObserver implements TraceObserver {

    private Session session
    private List<BaseReport> reports = []
    private NfReportConfig pluginConfig
    private boolean enabled = true

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
        if (pluginConfig.email.enabled) {
            EmailNotification.sendEmailNotification(pluginConfig.email.toMap(), reports)
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
            def configMap = session.config.navigate('nfreport') as Map

            if (!configMap) {
                log.debug('No nfreport configuration found, using defaults')
                configMap = [:]
            }
            configMap = evaluateClosures(configMap)

            pluginConfig = new NfReportConfig(configMap)
            enabled = pluginConfig.enabled

            log.debug('nf-report configuration parsed successfully')
        } catch (Exception e) {
            log.warn("Failed to parse nf-report configuration, using defaults: ${e.message}")
            pluginConfig = new NfReportConfig([:])
            enabled = true
        }
    }

    private void initializeReports() {
        reports.clear()

        if (pluginConfig.executionReport.enabled) {
            def executionReport = new ExecutionReport()
            executionReport.init(session, pluginConfig.executionReport.toMap())
            if (executionReport.enabled) {
                reports << executionReport
            }
        }

        if (pluginConfig.taskStatusReport.enabled) {
            def taskStatusReport = new TaskStatusReport()
            taskStatusReport.init(session, pluginConfig.taskStatusReport.toMap())
            if (taskStatusReport.enabled) {
                reports << taskStatusReport
            }
        }

        if (pluginConfig.sampleStatusReport.enabled) {
            def sampleStatusReport = new SampleStatusReport()
            sampleStatusReport.init(session, pluginConfig.sampleStatusReport.toMap())
            if (sampleStatusReport.enabled) {
                reports << sampleStatusReport
            }
        }

        log.debug("Initialized ${reports.size()} reports: ${reports.collect { it.name }.join(', ')}")
    }

}

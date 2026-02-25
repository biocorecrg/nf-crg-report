package roche_csi.plugin

import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import groovy.json.JsonBuilder
import groovy.text.GStringTemplateEngine
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.trace.TraceRecord

/**
 * Generates execution reports for workflow start, end, and failure events
 */
@Slf4j
class ExecutionReport extends BaseReport {

    private List<Map> executionEvents = []

    ExecutionReport() {
        super('execution-report')
    }

    @Override
    void init(Session session, Map config) {
        super.init(session, config)
    }

    @Override
    void onWorkflowStart() {
        super.onWorkflowStart()
        if (!enabled) {
            return
        }

        def workflowInfo = session.workflowMetadata
        def timestampFormatted = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def event = [
            event_type: 'workflow_start',
            timestamp: timestampFormatted,
            run_name: workflowInfo.runName,
            script_id: workflowInfo.scriptId,
            script_name: workflowInfo.scriptName,
            command_line: workflowInfo.commandLine,
            params: nextflow.Global.config.params,
            repository: workflowInfo.repository,
            commit_id: workflowInfo.commitId,
            revision: workflowInfo.revision,
            start_time: workflowInfo.start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            nextflow_version: workflowInfo.nextflow?.version,
            project_dir: workflowInfo.projectDir.toString(),
            launch_dir: workflowInfo.launchDir.toString(),
            output_dir: workflowInfo.outputDir.toString(),
            work_dir: workflowInfo.workDir.toString(),
            home_dir: workflowInfo.homeDir.toString(),
            user_name: workflowInfo.userName,
            profile: workflowInfo.profile,
            session_id: workflowInfo.sessionId,
            container_engine: workflowInfo.containerEngine,
            config_files: workflowInfo.configFiles.collect { it.toString() }
        ]
        recordEvent(event)
        writeReport()
    }

    @Override
    void onWorkflowComplete() {
        super.onWorkflowComplete()
        if (!enabled) {
            return
        }

        def workflowInfo = super.session.workflowMetadata
        def timestampFormatted = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def event = [
            event_type: 'workflow_complete',
            timestamp: timestampFormatted,
            run_name: workflowInfo.runName,
            script_id: workflowInfo.scriptId,
            script_name: workflowInfo.scriptName,
            command_line: workflowInfo.commandLine,
            params: nextflow.Global.config.params,
            repository: workflowInfo.repository,
            commit_id: workflowInfo.commitId,
            revision: workflowInfo.revision,
            start_time: workflowInfo.start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            end_time: workflowInfo.complete.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            duration: workflowInfo.duration,
            success: workflowInfo.success,
            exit_status: workflowInfo.exitStatus,
            error_message: workflowInfo.errorMessage,
            error_report: workflowInfo.errorReport,
            nextflow_version: workflowInfo.nextflow?.version,
            project_dir: workflowInfo.projectDir.toString(),
            launch_dir: workflowInfo.launchDir.toString(),
            output_dir: workflowInfo.outputDir.toString(),
            work_dir: workflowInfo.workDir.toString(),
            home_dir: workflowInfo.homeDir.toString(),
            user_name: workflowInfo.userName,
            profile: workflowInfo.profile,
            session_id: workflowInfo.sessionId,
            container_engine: workflowInfo.containerEngine,
            config_files: workflowInfo.configFiles.collect { it.toString() }
        ]
        recordEvent(event)
        writeReport()
    }

    private void recordEvent(Map event) {
        synchronized(executionEvents) {
            executionEvents << event
        }
    }

    @Override
    Map toMap() {
        def map = super.toMap()
        map << [
            events: executionEvents.collect { event ->
                event.findAll { k, v -> fields.isEmpty() || fields.contains(k) }
            }
        ]
        return map
    }

    private void writeJsonReport() {
        def json = new JsonBuilder(toMap())
        writeToFile(json.toPrettyString(), 'json')
    }

    private void writeHtmlReport() {
        def engine = new GStringTemplateEngine()
        def template = null
        if (htmlTemplatePath) {
            def templateFile = new File(htmlTemplatePath)
            if (!templateFile.exists()) {
                log.error("HTML template file not found at: ${htmlTemplatePath}")
                return
            }
            template = engine.createTemplate(templateFile)
        } else {
            def defaultTemplateStream = this.class.getResourceAsStream('/roche_csi/plugin/execution-report-template.html')
            template = engine.createTemplate(new InputStreamReader(defaultTemplateStream))
        }
        def htmlContent = template.make(toMap()).toString()
        writeToFile(htmlContent, 'html')
    }

    private void writeTsvReport() {
        def tsvContent = new StringWriter()
        def headerKeys = fields ?: toMap().events.collectMany { it.keySet() }.unique()
        tsvContent << headerKeys.join('\t') + '\n'
        // Write event data
        executionEvents.each { event ->
            def row = headerKeys.collect { key -> event[key] ?: '' }
            tsvContent << "${row.join('\t')}\n"
        }
        writeToFile(tsvContent.toString(), 'tsv')
    }

    private void writeReport() {
        if (format.contains('json')) {
            writeJsonReport()
        }
        if (format.contains('html')) {
            writeHtmlReport()
        }
        if (format.contains('tsv')) {
            writeTsvReport()
        }
    }

}

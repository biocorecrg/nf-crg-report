package roche_csi.plugin

import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import nextflow.util.MemoryUnit
import nextflow.script.params.FileOutParam
import nextflow.script.params.TupleOutParam
import groovy.text.GStringTemplateEngine

/**
 * Generates task status reports with tasks grouped by status
 */
@Slf4j
class TaskStatusReport extends BaseReport {

    private Map<String, List<Map>> tasksByStatus = [
        'COMPLETED': [],
        'CACHED': [],
        'FAILED': [],
        'ABORTED': [],
        'RETRIED': []
    ]

    private List<Map> tasks = []

    TaskStatusReport() {
        super('task-status-report')
    }

    @Override
    void init(Session session, Map config) {
        super.init(session, config)
    }

    @Override
    void onTaskComplete(TaskHandler handler, TraceRecord trace) {
        super.onTaskComplete(handler, trace)
        if (!enabled) {
            return
        }

        def status = trace.get('status')?.toString() ?: 'COMPLETED'
        if (trace.error_action == 'RETRY') {
            status = 'RETRIED'
        }
        def taskData = createTaskData(handler, trace, status).findAll { k, v -> fields.isEmpty() || fields.contains(k) }
        recordTask(status, taskData)

        writeReport()
    }

    @Override
    void onTaskCached(TaskHandler handler, TraceRecord trace) {
        super.onTaskCached(handler, trace)
        if (!enabled) {
            return
        }

        def taskData = createTaskData(handler, trace, 'CACHED')
        recordTask('CACHED', taskData)

        writeReport()
    }

    @Override
    void onWorkflowComplete() {
        super.onWorkflowComplete()
        if (!enabled) {
            return
        }

        writeReport()
    }

    List<String> getOutputs(TaskHandler handler) {
        final task = handler.task as TaskRun
        final outputParams = task.getOutputsByType(FileOutParam)
        def outputs = []
        outputParams.each { param, files ->
            final fileList = files instanceof List ? files : [files]
            fileList.each { file ->
                if (file != null) {
                    outputs << file.toString()
                }
            }
        }
        return outputs
    }

    private Map createTaskData(TaskHandler handler, TraceRecord trace, String status) {
        def submit_time = Instant.ofEpochMilli((trace.get('submit') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def start_time = Instant.ofEpochMilli((trace.get('start') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def complete_time = Instant.ofEpochMilli((trace.get('complete') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        return [
            task_id: trace.get('task_id')?.toString(),
            hash: trace.get('hash')?.toString(),
            process_name: handler.task.processor.name,
            task_name: handler.task.name,
            tag: handler.task.config.tag?.toString(),
            status: trace.get('status')?.toString() ?: status,
            exit_status: trace.get('exit'),
            submit_time: submit_time,
            start_time: start_time,
            complete_time: complete_time,
            duration_ms: trace.get('duration') ?: 0,
            realtime_ms: trace.get('realtime') ?: 0,
            attempt: handler.task.config.attempt ?: 1,
            rchar: (trace.get('rchar') != null) ? MemoryUnit.of(trace.get('rchar') ?: 0).toString() : null,
            wchar: (trace.get('wchar') != null) ? MemoryUnit.of(trace.get('wchar') ?: 0).toString() : null,
            pct_cpu: trace.get('%cpu'),
            peak_rss: (trace.get('peak_rss') != null) ? MemoryUnit.of(trace.get('peak_rss') ?: 0).toString() : null,
            peak_vmem: (trace.get('peak_vmem') != null) ? MemoryUnit.of(trace.get('peak_vmem') ?: 0).toString() : null,
            workdir: trace.get('workdir')?.toString(),
            log: trace.get('workdir') ? "${trace.get('workdir')}/.command.log" : null,
            out: trace.get('workdir') ? "${trace.get('workdir')}/.command.out" : null,
            err: trace.get('workdir') ? "${trace.get('workdir')}/.command.err" : null,
            scratch: trace.get('scratch')?.toString(),
            container: trace.get('container')?.toString(),
            outputs: getOutputs(handler)
        ]
    }

    private void recordTask(String status, Map taskData) {
        synchronized(tasksByStatus) {
            if (taskData && tasksByStatus.containsKey(status)) {
                tasksByStatus[status] << taskData
            }
        }
        synchronized(tasks) {
            tasks << taskData
        }
    }

    @Override
    Map toMap() {
        def map = super.toMap()
        synchronized(tasksByStatus) {
            map << [
                summary: [
                    total_tasks: tasksByStatus.values().flatten().size(),
                    completed: tasksByStatus['COMPLETED'].size(),
                    cached: tasksByStatus['CACHED'].size(),
                    failed: tasksByStatus['FAILED'].size(),
                    aborted: tasksByStatus['ABORTED'].size(),
                    retried: tasksByStatus['RETRIED'].size()
                ],
                tasks_by_status: tasksByStatus
            ]
        }
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
            def defaultTemplateStream = this.class.getResourceAsStream('/roche_csi/plugin/task-status-report-template.html')
            template = engine.createTemplate(new InputStreamReader(defaultTemplateStream))
        }
        def htmlContent = template.make(toMap()).toString()
        writeToFile(htmlContent, 'html')
    }

    private void writeTsvReport() {
        def tsvContent = new StringWriter()
        def headerKeys = fields ?: tasks.collectMany { it.keySet() }.unique()
        tsvContent << "${headerKeys.join('\t')}\n"
        // Write event data
        tasks.each { task ->
            def row = headerKeys.collect { key -> task[key] ?: '' }
            tsvContent << "${row.join('\t')}\n"
        }
        writeToFile(tsvContent.toString(), 'tsv')
    }

    private void writeReport() {
        synchronized(tasksByStatus) {
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

}

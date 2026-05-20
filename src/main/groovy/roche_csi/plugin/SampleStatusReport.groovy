package roche_csi.plugin

import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.io.InputStreamReader
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import nextflow.util.MemoryUnit
import nextflow.script.params.InParam
import nextflow.script.params.ValueInParam
import nextflow.script.params.FileOutParam
import groovy.text.GStringTemplateEngine

/**
 * Generates sample status reports with samples grouped by processing status
 */
@Slf4j
class SampleStatusReport extends BaseReport {

    private Map<String, Map> sampleData = [:]
    private String sampleNameTagPattern = null
    private String sampleNameMetaKeyPattern = null
    private String extractSampleNameFrom = 'tag' // or "meta_map"
    private boolean printCompletionSummary = false

    SampleStatusReport() {
        super('sample-status-report')
    }

    @Override
    void init(Session session, Map config) {
        super.init(session, config)
        extractSampleNameFrom = config.extractSampleNameFrom ?: 'tag'
        sampleNameTagPattern = config.sampleNameTagPattern ?: null
        sampleNameMetaKeyPattern = config.sampleNameMetaKeyPattern ?: null
        printCompletionSummary = config.printCompletionSummary ?: false
    }

    @Override
    void onTaskComplete(TaskHandler handler, TraceRecord trace) {
        super.onTaskComplete(handler, trace)
        if (!enabled) {
            return
        }

        def sampleIds = extractSampleIds(handler, trace)
        if (sampleIds) {
            def status = trace.get('status')?.toString() ?: 'COMPLETED'
            if (trace.get('error_action') == 'RETRY') {
                status = 'RETRIED'
            } else if (trace.get('error_action') == 'IGNORE') {
                status = 'IGNORED'
            }
            sampleIds.each { sampleId ->
                updateSampleData(sampleId, handler, trace, status)
            }
        }
        writeReport()
    }

    @Override
    void onTaskCached(TaskHandler handler, TraceRecord trace) {
        super.onTaskCached(handler, trace)
        if (!enabled) {
            return
        }

        def sampleIds = extractSampleIds(handler, trace)
        sampleIds.each { sampleId ->
            updateSampleData(sampleId, handler, trace, 'CACHED')
        }

        writeReport()
    }

    @Override
    void onWorkflowComplete() {
        super.onWorkflowComplete()
        if (!enabled) {
            return
        }

        writeReport()

        if (printCompletionSummary) {
            printSummary()
        }
    }

    private void printSummary() {
        def summary = buildCompletionSummary()
        def hasFailures = sampleData.values().any { it?.status in ['FAILED', 'PARTIALLY_COMPLETED'] }
        if (hasFailures) {
            log.warn(summary)
        } else {
            log.info(summary)
        }
    }

    String buildCompletionSummary() {
        def samplesByStatus = [
            'COMPLETED': [],
            'PARTIALLY_COMPLETED': [],
            'FAILED': [],
            'PENDING': []
        ]
        synchronized(sampleData) {
            sampleData.each { sampleId, sample ->
                if (sample?.status && samplesByStatus.containsKey(sample.status)) {
                    samplesByStatus[sample.status] << sampleId
                }
            }
        }

        def sb = new StringBuilder()
        sb << '\n=== Sample Status Summary ===\n'

        if (samplesByStatus['COMPLETED']) {
            sb << "\nCompleted (${samplesByStatus['COMPLETED'].size()}):\n"
            samplesByStatus['COMPLETED'].sort().each { sb << "  - ${it}\n" }
        }

        if (samplesByStatus['PARTIALLY_COMPLETED']) {
            sb << "\nPartially Completed (${samplesByStatus['PARTIALLY_COMPLETED'].size()}):\n"
            samplesByStatus['PARTIALLY_COMPLETED'].sort().each { sb << "  - ${it}\n" }
        }

        if (samplesByStatus['FAILED']) {
            sb << "\nFailed (${samplesByStatus['FAILED'].size()}):\n"
            samplesByStatus['FAILED'].sort().each { sb << "  - ${it}\n" }
        }

        if (samplesByStatus['PENDING']) {
            sb << "\nPending (${samplesByStatus['PENDING'].size()}):\n"
            samplesByStatus['PENDING'].sort().each { sb << "  - ${it}\n" }
        }

        // Include normalized paths to generated report files
        def reportPaths = outputFiles.values().collect { it.toPath().toAbsolutePath().normalize().toString() }
        if (reportPaths) {
            sb << "\nReport: ${reportPaths.join(', ')}\n"
        }

        sb << '============================='
        return sb.toString()
    }

    private static <T extends InParam> Map<T,Object> getInputsByType(TaskRun task, Class<T>... types) {
        Map<T, Object> result = [:]
        for (Object it : task.inputs) {
            if (types.contains(it.key.class)) {
                result << it
            }
        }
        return result
    }

    private List<String> extractSampleIds(TaskHandler handler, TraceRecord trace) {
        if (extractSampleNameFrom == 'tag') {
            def tag = handler.task.config.tag
            if (tag && tag != 'null' && tag.toString().trim()) {
                def tagStr = tag.toString()
                if (sampleNameTagPattern != null) {
                    def pattern = ~sampleNameTagPattern
                    def matcher = tagStr =~ pattern
                    if (matcher.find()) {
                        return [matcher.group()]
                    }
                }
                return [tagStr]
            }
        } else if (extractSampleNameFrom == 'meta_map') {
            def params = getInputsByType(handler.task as TaskRun, ValueInParam)
            for (param in params) {
                if (param.value instanceof Map) {
                    for (item in param.value) {
                        if (sampleNameMetaKeyPattern != null) {
                            log.debug "Checking meta_map key: ${item.key} with value: ${item.value}, against pattern: ${sampleNameMetaKeyPattern}"
                            def pattern = ~sampleNameMetaKeyPattern
                            def matcher = item.key.toString() =~ pattern
                            if (matcher.find()) {
                                log.debug "Found key: ${item.key}, returning value: ${item.value}"
                                return toSampleIdList(item.value)
                            }
                        } else {
                            return toSampleIdList(item.value)
                        }
                    }
                }
            }
        }
        return []
    }

    private static List<String> toSampleIdList(Object value) {
        if (value instanceof Collection) {
            return value.collect { it.toString() }.findAll { it.trim() }
        }
        def str = value.toString().trim()
        return str ? [str] : []
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

    private void updateSampleData(String sampleId, TaskHandler handler, TraceRecord trace, String taskStatus) {
        synchronized(sampleData) {
            if (!sampleData.containsKey(sampleId)) {
                sampleData[sampleId] = [
                    sample_id: sampleId,
                    status: 'PENDING',
                    tasks: [],
                    task_counts: [
                        total: 0,
                        completed: 0,
                        cached: 0,
                        failed: 0,
                        aborted: 0,
                        failure_retried: 0,
                        failure_ignored: 0,
                    ],
                    first_task_start: null,
                    last_task_complete: null,
                    total_duration_ms: 0,
                    processes: [] as Set
                ]
            }

            def sample = sampleData[sampleId]

            def submit_time = Instant.ofEpochMilli((trace.get('submit') ?: 0) as Long)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            def start_time = Instant.ofEpochMilli((trace.get('start') ?: 0) as Long)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            def complete_time = Instant.ofEpochMilli((trace.get('complete') ?: 0) as Long)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            // Add task information
            def taskInfo = [
                task_id: trace.get('task_id')?.toString(),
                process_name: handler.task.processor.name,
                task_name: handler.task.name,
                status: taskStatus,
                submit_time: submit_time,
                start_time: start_time,
                complete_time: complete_time,
                duration_ms: trace.get('duration') ?: 0,
                realtime_ms: trace.get('realtime') ?: 0,
                exit_status: trace.get('exit'),
                attempt: trace.get('attempt') ?: 1,
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

            sample.tasks << taskInfo.findAll { k, v -> fields.isEmpty() || fields.contains(k) }
            sample.processes << handler.task.processor.name

            // Update counters
            sample.task_counts.total++
            switch (taskStatus) {
                case 'COMPLETED':
                    sample.task_counts.completed++
                    break
                case 'CACHED':
                    sample.task_counts.cached++
                    break
                case 'FAILED':
                    sample.task_counts.failed++
                    break
                case 'ABORTED':
                    sample.task_counts.aborted++
                    break
                case 'RETRIED':
                    sample.task_counts.failure_retried++
                    break
                case 'IGNORED':
                    sample.task_counts.failure_ignored++
                    break
            }

            // Update timing information
            if (trace.get('start')) {
                def startTime = trace.get('start') as Long
                def startTimeFormatted = Instant.ofEpochMilli(startTime)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                if (!sample.first_task_start) {
                    sample.first_task_start = startTimeFormatted
                } else {
                    // Compare with the existing start time
                    def existingStartTime = LocalDateTime.parse(sample.first_task_start, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                            .atZone(ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli()
                    if (startTime < existingStartTime) {
                        sample.first_task_start = startTimeFormatted
                    }
                }
            }

            if (trace.get('complete')) {
                def completeTime = trace.get('complete') as Long
                def completeTimeFormatted = Instant.ofEpochMilli(completeTime)
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                if (!sample.last_task_complete) {
                    sample.last_task_complete = completeTimeFormatted
                } else {
                    // Compare with the existing complete time
                    def existingCompleteTime = LocalDateTime.parse(sample.last_task_complete, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                            .atZone(ZoneId.systemDefault())
                            .toInstant()
                            .toEpochMilli()
                    if (completeTime > existingCompleteTime) {
                        sample.last_task_complete = completeTimeFormatted
                    }
                }
            }

            if (trace.get('duration')) {
                sample.total_duration_ms += (trace.get('duration') as Long)
            }

            // Update overall sample status
            if (sample.task_counts.failed + sample.task_counts.aborted + sample.task_counts.failure_ignored > 0) {
                if (sample.task_counts.completed + sample.task_counts.cached > 0) {
                    sample.status = 'PARTIALLY_COMPLETED'
                } else {
                    sample.status = 'FAILED'
                }
            } else if (sample.task_counts.completed + sample.task_counts.cached == sample.task_counts.total) {
                sample.status = 'COMPLETED'
            } else {
                sample.status = 'PENDING'
            }

            // For retried tasks, if there are any subsequent attempts that completed successfully, update status accordingly
            if (sample.task_counts.failure_retried > 0) {
                def allRetriesCompleted = true
                // Iterate through tasks to check if all retried tasks eventually completed
                sample.tasks.each { task ->
                    if (task.status == 'RETRIED') {
                        def relatedTasks = sample.tasks.findAll { it.task_name == task.task_name && it.status in ['COMPLETED', 'CACHED'] && it.attempt > task.attempt }
                        if (relatedTasks.isEmpty()) {
                            allRetriesCompleted = false
                        }
                    }
                }
                if (allRetriesCompleted) {
                    if (sample.task_counts.failed + sample.task_counts.aborted + sample.task_counts.failure_ignored > 0) {
                        sample.status = 'PARTIALLY_COMPLETED'
                    } else {
                        sample.status = 'COMPLETED'
                    }
                }
            }
        }
    }

    @Override
    Map toMap() {
        def map = super.toMap()
        def samplesByStatus = [
            'COMPLETED': [],
            'PENDING': [],
            'PARTIALLY_COMPLETED': [],
            'FAILED': []
        ]
        synchronized(sampleData) {
            sampleData.each { sampleId, sample ->
                if (sample != null && sample.status != null) {
                    samplesByStatus[sample.status] << sample
                }
            }
        }
        def generationTimeFormatted = Instant.now().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        map << [
            summary: [
                total_samples: sampleData.size(),
                completed: samplesByStatus['COMPLETED'].size(),
                pending: samplesByStatus['PENDING'].size(),
                partially_completed: samplesByStatus['PARTIALLY_COMPLETED'].size(),
                failed: samplesByStatus['FAILED'].size()
            ],
            samples_by_status: samplesByStatus
        ]
        return map
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
            def defaultTemplateStream = this.class.getResourceAsStream('/roche_csi/plugin/sample-status-report-template.html')
            template = engine.createTemplate(new InputStreamReader(defaultTemplateStream))
        }

        def htmlContent = template.make(toMap()).toString()
        writeToFile(htmlContent, 'html')
    }

    private void writeJsonReport() {
        def json = new JsonBuilder(toMap())
        writeToFile(json.toPrettyString(), 'json')
    }

    private void writeTsvReport() {
        def tsvContent = new StringBuilder()
        tsvContent << 'sample_id\tstatus\ttotal_tasks\tcompleted_tasks\tcached_tasks\tfailed_tasks\taborted_tasks\tretried_failures\tignored_failures\n'
        synchronized(sampleData) {
            sampleData.each { sampleId, sample ->
                tsvContent << "${sampleId}\t${sample.status}\t${sample.task_counts.total}\t${sample.task_counts.completed}\t${sample.task_counts.cached}\t${sample.task_counts.failed}\t${sample.task_counts.aborted}\t${sample.task_counts.failure_retried}\t${sample.task_counts.failure_ignored}\n"
            }
        }
        writeToFile(tsvContent.toString(), 'tsv')
    }

    private void writeReport() {
        synchronized(sampleData) {
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

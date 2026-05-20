package roche_csi.plugin

import nextflow.Session
import nextflow.executor.Executor
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.processor.TaskId
import nextflow.processor.TaskContext
import nextflow.processor.TaskStatus
import nextflow.processor.TaskProcessor
import nextflow.trace.TraceRecord
import nextflow.util.Duration
import nextflow.util.MemoryUnit
import spock.lang.Specification
import java.nio.file.Paths
import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tests for SampleStatusReport
 */
class SampleStatusReportTest extends Specification {

    private TaskHandler createHandler(String taskName, String tag, int taskIdValue = 100, int exitStatus = 0, long submitTime = 1000, long startTime = 1500) {
        def config = [
            tag: tag
        ]
        def task = new TaskRun(id: new TaskId(taskIdValue), workDir: Paths.get('test'), name: taskName, exitStatus: exitStatus, config: config)
        task.processor = Mock(TaskProcessor)
        task.processor.getSession() >> new Session()
        task.processor.getName() >> 'Test'
        task.processor.getExecutor() >> Mock(Executor)
        task.context = new TaskContext(Mock(Script), [:], 'none')

        def handler = Spy(TaskHandler)
        handler.task = task
        handler.status = TaskStatus.COMPLETED
        handler.submitTimeMillis = submitTime
        handler.startTimeMillis = startTime
        return handler
    }

    private TraceRecord createRecord(Map props = [:]) {
        def record = new TraceRecord()
        props.each { k, v -> record."${k}" = v }
        return record
    }

    private NfReportObserver createObserverWithSampleStatusReport(Map extraConfig = [:]) {
        def session = new Session()
        def reportConfig = [enabled: true] + extraConfig
        session.config = [
            nfreport: [
                sampleStatusReport: reportConfig
            ]
        ]

        def observer = new NfReportObserver()
        observer.onFlowCreate(session)
        return observer
    }

    private SampleStatusReport getSampleStatusReport(NfReportObserver observer) {
        def report = observer.reports.find { it instanceof SampleStatusReport }
        assert report != null
        return report as SampleStatusReport
    }

    def 'sample status report with a single successful sample where all tasks are completed' () {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 127)
        def record = createRecord(status: 'COMPLETED')

        when:
        sampleStatusReport.onTaskComplete(handler, record)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        assert result.summary.total_samples == 1
        assert result.summary.completed == 1
    }

    def 'sample status report with a single partially completed sample where some tasks failed'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100, 127)
        def handler2 = createHandler('task2', 'sample1', 101, 0)

        def record1 = createRecord(status: 'COMPLETED')
        def record2 = createRecord(status: 'FAILED')

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskComplete(handler2, record2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // If at least one task is completed while others failed, the sample is considered partially completed
        assert result.summary.total_samples == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 0
        assert result.summary.partially_completed == 1
    }

    def 'sample status report with a single completed sample where some tasks are cached'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100, 127)
        def handler2 = createHandler('task2', 'sample1', 101, 0)

        def record1 = createRecord(status: 'COMPLETED')
        def record2 = createRecord(status: 'COMPLETED')

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskCached(handler2, record2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // Cached tasks are considered successful
        assert result.summary.total_samples == 1
        assert result.summary.completed == 1
    }

    def 'sample status report with a single completed sample where failed tasks succeeded upon retry'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 127)
        def handlerRetry = createHandler('task1', 'sample1', 101, 0, 2000, 2500)

        def record = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1, start: 1500, complete: 2000, duration: 500)
        def recordRetry = createRecord(status: 'COMPLETED', attempt: 2, start: 2500, complete: 3000, duration: 500)

        when:
        sampleStatusReport.onTaskComplete(handler, record)
        sampleStatusReport.onTaskComplete(handlerRetry, recordRetry)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // If a task with a failed status is retried and succeeds, the sample is considered completed
        assert result.summary.total_samples == 1
        assert result.summary.completed == 1
        assert result.summary.failed == 0
        assert result.summary.partially_completed == 0
        assert result.samples_by_status.COMPLETED[0].first_task_start == Instant.ofEpochMilli(1500)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assert result.samples_by_status.COMPLETED[0].last_task_complete == Instant.ofEpochMilli(3000)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assert result.samples_by_status.COMPLETED[0].total_duration_ms == 1000
    }

    def 'sample status report with a single completed sample where failed tasks still failed upon retry'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 127)
        def handlerRetry = createHandler('task1', 'sample1', 101, 0)

        def record = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1)
        def recordRetry = createRecord(status: 'FAILED', attempt: 2)

        when:
        sampleStatusReport.onTaskComplete(handler, record)
        sampleStatusReport.onTaskComplete(handlerRetry, recordRetry)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // If a task with a failed status is retried and still fails, the sample is considered failed
        assert result.summary.total_samples == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 1
        assert result.summary.partially_completed == 0
    }

    def 'sample status report with a single sample where all tasks failed'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100, 127)
        def handler2 = createHandler('task2', 'sample1', 101, 127)

        def record1 = createRecord(status: 'FAILED', attempt: 1)
        def record2 = createRecord(status: 'ABORTED', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskComplete(handler2, record2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // If all tasks failed, the sample is considered failed. Aborted tasks are also counted as failed.
        assert result.summary.total_samples == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 1
        assert result.summary.partially_completed == 0
    }

    def 'sample status report with a single sample where a retry is interrupted'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 127)

        // Original record indicates a retry but no subsequent attempt was made
        def record = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(handler, record)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        assert result.summary.total_samples == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 0
        assert result.summary.partially_completed == 0
        // If a workflow is interrupted when a retry is in progress
        // we mark the sample as pending
        assert result.summary.pending == 1
    }

    def 'sample status report with a single sample where all tasks are ignored failures'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100, 1)
        def handler2 = createHandler('task2', 'sample1', 101, 1)

        def record1 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)
        def record2 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskComplete(handler2, record2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // All tasks were ignored failures, so the sample is FAILED
        assert result.summary.total_samples == 1
        assert result.summary.failed == 1
        assert result.summary.completed == 0
        assert result.summary.partially_completed == 0
        def sample = result.samples_by_status.FAILED[0]
        assert sample.task_counts.failure_ignored == 2
        assert sample.task_counts.failed == 0
        assert sample.task_counts.total == 2
    }

    def 'sample status report with a single sample with mix of completed and ignored failures'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100, 0)
        def handler2 = createHandler('task2', 'sample1', 101, 1)

        def record1 = createRecord(status: 'COMPLETED', attempt: 1)
        def record2 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskComplete(handler2, record2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        assert result.summary.total_samples == 1
        assert result.summary.partially_completed == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 0
        def sample = result.samples_by_status.PARTIALLY_COMPLETED[0]
        assert sample.task_counts.completed == 1
        assert sample.task_counts.failure_ignored == 1
        assert sample.task_counts.total == 2
    }

    def 'sample status report with ignored failure and successful retry remains partially completed'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        // Task that failed and was ignored
        def handler1 = createHandler('task1', 'sample1', 100, 1)
        def record1 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        // Task that failed, was retried, and succeeded
        def handler2 = createHandler('task2', 'sample1', 101, 1)
        def record2 = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1, start: 1500, complete: 2000, duration: 500)
        def handler3 = createHandler('task2', 'sample1', 102, 0, 2000, 2500)
        def record3 = createRecord(status: 'COMPLETED', attempt: 2, start: 2500, complete: 3000, duration: 500)

        when:
        sampleStatusReport.onTaskComplete(handler1, record1)
        sampleStatusReport.onTaskComplete(handler2, record2)
        sampleStatusReport.onTaskComplete(handler3, record3)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        // Ignored failure prevents COMPLETED even though retry resolved
        assert result.summary.total_samples == 1
        assert result.summary.partially_completed == 1
        assert result.summary.completed == 0
        def sample = result.samples_by_status.PARTIALLY_COMPLETED[0]
        assert sample.task_counts.failure_ignored == 1
        assert sample.task_counts.failure_retried == 1
        assert sample.task_counts.completed == 1
        assert sample.task_counts.total == 3
    }

    def 'sample task_counts include all counter fields with correct values'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        def h1 = createHandler('task1', 'sample1', 100, 0)
        def h2 = createHandler('task2', 'sample1', 101, 0)
        def h3 = createHandler('task3', 'sample1', 102, 1)
        def h4 = createHandler('task4', 'sample1', 103, 1)
        def h5 = createHandler('task5', 'sample1', 104, 1)
        def h6 = createHandler('task6', 'sample1', 105, 1)

        def r1 = createRecord(status: 'COMPLETED', attempt: 1)
        def r2 = createRecord(status: 'COMPLETED', attempt: 1)  // will be sent as cached
        def r3 = createRecord(status: 'FAILED', attempt: 1)
        def r4 = createRecord(status: 'ABORTED', attempt: 1)
        def r5 = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1)
        def r6 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(h1, r1)
        sampleStatusReport.onTaskCached(h2, r2)
        sampleStatusReport.onTaskComplete(h3, r3)
        sampleStatusReport.onTaskComplete(h4, r4)
        sampleStatusReport.onTaskComplete(h5, r5)
        sampleStatusReport.onTaskComplete(h6, r6)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        assert result.summary.total_samples == 1
        // Sample has failures so it's partially completed (has completed + cached tasks too)
        assert result.summary.partially_completed == 1
        def sample = result.samples_by_status.PARTIALLY_COMPLETED[0]
        assert sample.task_counts.total == 6
        assert sample.task_counts.completed == 1
        assert sample.task_counts.cached == 1
        assert sample.task_counts.failed == 1
        assert sample.task_counts.aborted == 1
        assert sample.task_counts.failure_retried == 1
        assert sample.task_counts.failure_ignored == 1
    }

    def 'multiple samples with different ignored-failure outcomes'() {
        given:
        def observer = createObserverWithSampleStatusReport()
        def sampleStatusReport = getSampleStatusReport(observer)

        // Sample A: all ignored failures -> FAILED
        def hA1 = createHandler('task1', 'sampleA', 100, 1)
        def hA2 = createHandler('task2', 'sampleA', 101, 1)
        def rA1 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)
        def rA2 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        // Sample B: completed + ignored failure -> PARTIALLY_COMPLETED
        def hB1 = createHandler('task1', 'sampleB', 200, 0)
        def hB2 = createHandler('task2', 'sampleB', 201, 1)
        def rB1 = createRecord(status: 'COMPLETED', attempt: 1)
        def rB2 = createRecord(status: 'FAILED', error_action: 'IGNORE', attempt: 1)

        // Sample C: all completed -> COMPLETED
        def hC1 = createHandler('task1', 'sampleC', 300, 0)
        def hC2 = createHandler('task2', 'sampleC', 301, 0)
        def rC1 = createRecord(status: 'COMPLETED', attempt: 1)
        def rC2 = createRecord(status: 'COMPLETED', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(hA1, rA1)
        sampleStatusReport.onTaskComplete(hA2, rA2)
        sampleStatusReport.onTaskComplete(hB1, rB1)
        sampleStatusReport.onTaskComplete(hB2, rB2)
        sampleStatusReport.onTaskComplete(hC1, rC1)
        sampleStatusReport.onTaskComplete(hC2, rC2)

        then:
        def result = sampleStatusReport.toMap()
        assert result != null
        assert result.summary.total_samples == 3
        assert result.summary.failed == 1
        assert result.summary.partially_completed == 1
        assert result.summary.completed == 1
        def failedIds = result.samples_by_status.FAILED.collect { it.sample_id }
        def partialIds = result.samples_by_status.PARTIALLY_COMPLETED.collect { it.sample_id }
        def completedIds = result.samples_by_status.COMPLETED.collect { it.sample_id }
        assert failedIds == ['sampleA']
        assert partialIds == ['sampleB']
        assert completedIds == ['sampleC']
    }

    def 'completion summary lists samples by status'() {
        given:
        def observer = createObserverWithSampleStatusReport(printCompletionSummary: true)
        def sampleStatusReport = getSampleStatusReport(observer)

        // sampleA: all completed -> COMPLETED
        def hA = createHandler('task1', 'sampleA', 100, 0)
        def rA = createRecord(status: 'COMPLETED')

        // sampleB: completed + ignored failure -> PARTIALLY_COMPLETED
        def hB1 = createHandler('task1', 'sampleB', 200, 0)
        def hB2 = createHandler('task2', 'sampleB', 201, 1)
        def rB1 = createRecord(status: 'COMPLETED')
        def rB2 = createRecord(status: 'FAILED', error_action: 'IGNORE')

        // sampleC: all ignored failures -> FAILED
        def hC = createHandler('task1', 'sampleC', 300, 1)
        def rC = createRecord(status: 'FAILED', error_action: 'IGNORE')

        // sampleD: retry in progress -> PENDING
        def hD = createHandler('task1', 'sampleD', 400, 1)
        def rD = createRecord(status: 'FAILED', error_action: 'RETRY', attempt: 1)

        when:
        sampleStatusReport.onTaskComplete(hA, rA)
        sampleStatusReport.onTaskComplete(hB1, rB1)
        sampleStatusReport.onTaskComplete(hB2, rB2)
        sampleStatusReport.onTaskComplete(hC, rC)
        sampleStatusReport.onTaskComplete(hD, rD)

        then:
        def summary = sampleStatusReport.buildCompletionSummary()
        assert summary.contains('=== Sample Status Summary ===')
        assert summary.contains('Completed (1):')
        assert summary.contains('  - sampleA')
        assert summary.contains('Partially Completed (1):')
        assert summary.contains('  - sampleB')
        assert summary.contains('Failed (1):')
        assert summary.contains('  - sampleC')
        assert summary.contains('Pending (1):')
        assert summary.contains('  - sampleD')
    }

    def 'completion summary includes report file path'() {
        given:
        def observer = createObserverWithSampleStatusReport(printCompletionSummary: true)
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 0)
        def record = createRecord(status: 'COMPLETED')

        when:
        sampleStatusReport.onTaskComplete(handler, record)

        then:
        def summary = sampleStatusReport.buildCompletionSummary()
        assert summary.contains('Report:')
        assert summary.contains('sample-status-report.json')
    }

    def 'completion summary report path is normalized'() {
        given:
        // Default outputDir is './reports', which produces a path with '.' segment
        def observer = createObserverWithSampleStatusReport(printCompletionSummary: true)
        def sampleStatusReport = getSampleStatusReport(observer)

        def handler = createHandler('task1', 'sample1', 100, 0)
        def record = createRecord(status: 'COMPLETED')

        when:
        sampleStatusReport.onTaskComplete(handler, record)

        then:
        def summary = sampleStatusReport.buildCompletionSummary()
        // Path should not contain /./  or /../ segments
        def reportLine = summary.readLines().find { it.startsWith('Report:') }
        assert reportLine != null
        assert !reportLine.contains('/./') : "Path should not contain /./ segment: ${reportLine}"
        assert !reportLine.contains('/../') : "Path should not contain /../ segment: ${reportLine}"
    }

    def 'completion summary omits empty status sections'() {
        given:
        def observer = createObserverWithSampleStatusReport(printCompletionSummary: true)
        def sampleStatusReport = getSampleStatusReport(observer)

        // Only completed samples
        def h1 = createHandler('task1', 'sample1', 100, 0)
        def h2 = createHandler('task1', 'sample2', 101, 0)
        def r1 = createRecord(status: 'COMPLETED')
        def r2 = createRecord(status: 'COMPLETED')

        when:
        sampleStatusReport.onTaskComplete(h1, r1)
        sampleStatusReport.onTaskComplete(h2, r2)

        then:
        def summary = sampleStatusReport.buildCompletionSummary()
        assert summary.contains('Completed (2):')
        assert !summary.contains('Failed')
        assert !summary.contains('Partially Completed')
        assert !summary.contains('Pending')
    }

    def 'completion summary sorts sample names alphabetically'() {
        given:
        def observer = createObserverWithSampleStatusReport(printCompletionSummary: true)
        def sampleStatusReport = getSampleStatusReport(observer)

        def hC = createHandler('task1', 'charlie', 100, 0)
        def hA = createHandler('task1', 'alpha', 101, 0)
        def hB = createHandler('task1', 'bravo', 102, 0)
        def rC = createRecord(status: 'COMPLETED')
        def rA = createRecord(status: 'COMPLETED')
        def rB = createRecord(status: 'COMPLETED')

        when:
        // Submit in non-alphabetical order
        sampleStatusReport.onTaskComplete(hC, rC)
        sampleStatusReport.onTaskComplete(hA, rA)
        sampleStatusReport.onTaskComplete(hB, rB)

        then:
        def summary = sampleStatusReport.buildCompletionSummary()
        def alphaIdx = summary.indexOf('  - alpha')
        def bravoIdx = summary.indexOf('  - bravo')
        def charlieIdx = summary.indexOf('  - charlie')
        assert alphaIdx < bravoIdx
        assert bravoIdx < charlieIdx
    }

}

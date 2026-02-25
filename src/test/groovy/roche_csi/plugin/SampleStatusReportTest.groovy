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

    private NfReportObserver createObserverWithSampleStatusReport() {
        def session = new Session()
        session.config = [
            nfreport: [
                sampleStatusReport: [
                    enabled: true
                ]
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

}

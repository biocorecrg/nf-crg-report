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

/**
 * Tests for TaskStatusReport
 */
class TaskStatusReportTest extends Specification {

    private TaskHandler createHandler(String taskName, String tag, int taskIdValue = 100) {
        def config = [
            tag: tag
        ]
        def task = new TaskRun(id: new TaskId(taskIdValue), workDir: Paths.get('test'), name: taskName, exitStatus: 0, config: config)
        task.processor = Mock(TaskProcessor)
        task.processor.getSession() >> new Session()
        task.processor.getName() >> 'TestProcess'
        task.processor.getExecutor() >> Mock(Executor)
        task.context = new TaskContext(Mock(Script), [:], 'none')

        def handler = Spy(TaskHandler)
        handler.task = task
        handler.status = TaskStatus.COMPLETED
        handler.submitTimeMillis = 1000
        handler.startTimeMillis = 1500

        return handler
    }

    private NfReportObserver createObserverWithTaskStatusReport() {
        def session = new Session()
        session.config = [
            nfreport: [
                taskStatusReport: [
                    enabled: true
                ]
            ]
        ]

        def observer = new NfReportObserver()
        observer.onFlowCreate(session)
        return observer
    }

    private TaskStatusReport getTaskStatusReport(NfReportObserver observer) {
        def report = observer.reports.find { it instanceof TaskStatusReport }
        assert report != null
        return report as TaskStatusReport
    }

    def 'task status report groups a single completed task correctly'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'COMPLETED'

        when:
        taskStatusReport.onTaskComplete(handler, record)

        then:
        def result = taskStatusReport.toMap()
        assert result != null
        assert result.summary.total_tasks == 1
        assert result.summary.completed == 1
        assert result.summary.cached == 0
        assert result.summary.failed == 0
        assert result.summary.aborted == 0
        assert result.summary.retried == 0
        assert result.tasks_by_status.COMPLETED.size() == 1
    }

    def 'task status report groups multiple completed tasks correctly'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100)
        def handler2 = createHandler('task2', 'sample1', 101)

        def record1 = new TraceRecord()
        record1.status = 'COMPLETED'

        def record2 = new TraceRecord()
        record2.status = 'COMPLETED'

        when:
        taskStatusReport.onTaskComplete(handler1, record1)
        taskStatusReport.onTaskComplete(handler2, record2)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 2
        assert result.summary.completed == 2
        assert result.tasks_by_status.COMPLETED.size() == 2
    }

    def 'task status report groups a single failed task correctly'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'FAILED'

        when:
        taskStatusReport.onTaskComplete(handler, record)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 1
        assert result.tasks_by_status.FAILED.size() == 1
    }

    def 'task status report groups a cached task correctly via onTaskCached'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'CACHED'

        when:
        taskStatusReport.onTaskCached(handler, record)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 1
        assert result.summary.cached == 1
        assert result.summary.completed == 0
        assert result.tasks_by_status.CACHED.size() == 1
    }

    def 'task status report groups a retried task correctly when error_action is RETRY'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'FAILED'
        record.error_action = 'RETRY'
        record.attempt = 1

        when:
        taskStatusReport.onTaskComplete(handler, record)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 1
        assert result.summary.retried == 1
        assert result.summary.failed == 0
        assert result.summary.completed == 0
        assert result.tasks_by_status.RETRIED.size() == 1
    }

    def 'task status report groups an aborted task correctly'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'ABORTED'

        when:
        taskStatusReport.onTaskComplete(handler, record)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 1
        assert result.summary.aborted == 1
        assert result.summary.completed == 0
        assert result.summary.failed == 0
        assert result.tasks_by_status.ABORTED.size() == 1
    }

    def 'task status report groups tasks of mixed statuses correctly'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handlerCompleted = createHandler('task1', 'sample1', 100)
        def handlerFailed = createHandler('task2', 'sample2', 101)
        def handlerCached = createHandler('task3', 'sample3', 102)
        def handlerRetried = createHandler('task4', 'sample4', 103)
        def handlerAborted = createHandler('task5', 'sample5', 104)

        def recordCompleted = new TraceRecord()
        recordCompleted.status = 'COMPLETED'

        def recordFailed = new TraceRecord()
        recordFailed.status = 'FAILED'

        def recordCached = new TraceRecord()
        recordCached.status = 'CACHED'

        def recordRetried = new TraceRecord()
        recordRetried.status = 'FAILED'
        recordRetried.error_action = 'RETRY'
        recordRetried.attempt = 1

        def recordAborted = new TraceRecord()
        recordAborted.status = 'ABORTED'

        when:
        taskStatusReport.onTaskComplete(handlerCompleted, recordCompleted)
        taskStatusReport.onTaskComplete(handlerFailed, recordFailed)
        taskStatusReport.onTaskCached(handlerCached, recordCached)
        taskStatusReport.onTaskComplete(handlerRetried, recordRetried)
        taskStatusReport.onTaskComplete(handlerAborted, recordAborted)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 5
        assert result.summary.completed == 1
        assert result.summary.failed == 1
        assert result.summary.cached == 1
        assert result.summary.retried == 1
        assert result.summary.aborted == 1
        assert result.tasks_by_status.COMPLETED.size() == 1
        assert result.tasks_by_status.FAILED.size() == 1
        assert result.tasks_by_status.CACHED.size() == 1
        assert result.tasks_by_status.RETRIED.size() == 1
        assert result.tasks_by_status.ABORTED.size() == 1
    }

    def 'summary total_tasks matches sum of individual status counts'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        // Create multiple tasks across different statuses
        def h1 = createHandler('task1', 'sample1', 100)
        def h2 = createHandler('task2', 'sample1', 101)
        def h3 = createHandler('task3', 'sample2', 102)
        def h4 = createHandler('task4', 'sample2', 103)
        def h5 = createHandler('task5', 'sample3', 104)
        def h6 = createHandler('task6', 'sample3', 105)

        def r1 = new TraceRecord(); r1.status = 'COMPLETED'
        def r2 = new TraceRecord(); r2.status = 'COMPLETED'
        def r3 = new TraceRecord(); r3.status = 'FAILED'
        def r4 = new TraceRecord(); r4.status = 'FAILED'; r4.error_action = 'RETRY'; r4.attempt = 1
        def r5 = new TraceRecord(); r5.status = 'CACHED'
        def r6 = new TraceRecord(); r6.status = 'ABORTED'

        when:
        taskStatusReport.onTaskComplete(h1, r1)
        taskStatusReport.onTaskComplete(h2, r2)
        taskStatusReport.onTaskComplete(h3, r3)
        taskStatusReport.onTaskComplete(h4, r4)
        taskStatusReport.onTaskCached(h5, r5)
        taskStatusReport.onTaskComplete(h6, r6)

        then:
        def result = taskStatusReport.toMap()
        def summary = result.summary
        def sumOfCounts = summary.completed + summary.cached + summary.failed + summary.retried + summary.aborted
        assert summary.total_tasks == sumOfCounts
        assert summary.total_tasks == 6
        assert summary.completed == 2
        assert summary.failed == 1
        assert summary.retried == 1
        assert summary.cached == 1
        assert summary.aborted == 1
    }

    def 'onTaskComplete triggered via observer correctly accounts tasks'() {
        given:
        def session = new Session()
        session.config = [
            nfreport: [
                taskStatusReport: [
                    enabled: true
                ]
            ]
        ]

        def observer = new NfReportObserver()
        observer.onFlowCreate(session)

        def handler1 = createHandler('task1', 'sample1', 100)
        def handler2 = createHandler('task2', 'sample2', 101)

        def record1 = new TraceRecord()
        record1.status = 'COMPLETED'

        def record2 = new TraceRecord()
        record2.status = 'FAILED'

        when:
        // Trigger events through the observer (as Nextflow would)
        observer.onProcessComplete(handler1, record1)
        observer.onProcessComplete(handler2, record2)

        then:
        def taskStatusReport = getTaskStatusReport(observer)
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 2
        assert result.summary.completed == 1
        assert result.summary.failed == 1
    }

    def 'onTaskCached triggered via observer correctly accounts cached tasks'() {
        given:
        def session = new Session()
        session.config = [
            nfreport: [
                taskStatusReport: [
                    enabled: true
                ]
            ]
        ]

        def observer = new NfReportObserver()
        observer.onFlowCreate(session)

        def handler = createHandler('task1', 'sample1')

        def record = new TraceRecord()
        record.status = 'CACHED'

        when:
        observer.onProcessCached(handler, record)

        then:
        def taskStatusReport = getTaskStatusReport(observer)
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 1
        assert result.summary.cached == 1
    }

    def 'retry followed by success accounts both retried and completed tasks'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        def handler1 = createHandler('task1', 'sample1', 100)
        def handler2 = createHandler('task1', 'sample1', 101)

        def recordRetry = new TraceRecord()
        recordRetry.status = 'FAILED'
        recordRetry.error_action = 'RETRY'
        recordRetry.attempt = 1

        def recordSuccess = new TraceRecord()
        recordSuccess.status = 'COMPLETED'
        recordSuccess.attempt = 2

        when:
        taskStatusReport.onTaskComplete(handler1, recordRetry)
        taskStatusReport.onTaskComplete(handler2, recordSuccess)

        then:
        def result = taskStatusReport.toMap()
        assert result.summary.total_tasks == 2
        assert result.summary.retried == 1
        assert result.summary.completed == 1
        assert result.summary.failed == 0
        assert result.tasks_by_status.RETRIED.size() == 1
        assert result.tasks_by_status.COMPLETED.size() == 1
    }

    def 'task status report is empty before any events'() {
        given:
        def observer = createObserverWithTaskStatusReport()
        def taskStatusReport = getTaskStatusReport(observer)

        when:
        def result = taskStatusReport.toMap()

        then:
        assert result.summary.total_tasks == 0
        assert result.summary.completed == 0
        assert result.summary.cached == 0
        assert result.summary.failed == 0
        assert result.summary.aborted == 0
        assert result.summary.retried == 0
        assert result.tasks_by_status.COMPLETED.isEmpty()
        assert result.tasks_by_status.CACHED.isEmpty()
        assert result.tasks_by_status.FAILED.isEmpty()
        assert result.tasks_by_status.ABORTED.isEmpty()
        assert result.tasks_by_status.RETRIED.isEmpty()
    }

}

package roche_csi.plugin

import nextflow.NextflowMeta
import nextflow.Session
import nextflow.script.ScriptFile
import nextflow.script.WorkflowMetadata
import nextflow.trace.WorkflowStats
import nextflow.trace.WorkflowStatsObserver
import spock.lang.Specification
import java.nio.file.Paths
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Tests for ExecutionReport
 */
class ExecutionReportTest extends Specification {

    private WorkflowMetadata createWorkflowMetadata(Session session, Map overrides = [:]) {
        def script = Mock(ScriptFile) {
            getScriptId() >> (overrides.scriptId ?: 'abc123')
            getMain() >> Paths.get(overrides.scriptFile ?: '/test/main.nf')
            getRepository() >> (overrides.repository ?: 'https://github.com/test/repo')
            getCommitId() >> (overrides.commitId ?: 'def456')
            getRevision() >> (overrides.revision ?: 'main')
            getLocalPath() >> Paths.get(overrides.projectDir ?: '/test/project')
            getProjectName() >> (overrides.projectName ?: 'test/repo')
        }
        session.getStatsObserver() >> Mock(WorkflowStatsObserver) { getStats() >> new WorkflowStats() }
        session.fetchContainers() >> (overrides.container ?: null)
        session.commandLine >> (overrides.commandLine ?: 'nextflow run main.nf')

        def metadata = new WorkflowMetadata(session, script)
        // Stub the getter so that session.workflowMetadata returns our metadata
        session.getWorkflowMetadata() >> metadata
        return metadata
    }

    private Session createSessionWithMetadata(Map metadataOverrides = [:]) {
        def config = [
            docker: [enabled: true],
            nfreport: [
                executionReport: [
                    enabled: true
                ]
            ]
        ]
        Session session = Spy(Session, constructorArgs: [config])
        session.configFiles >> [Paths.get('/test/nextflow.config')]

        def metadata = createWorkflowMetadata(session, metadataOverrides)
        session.binding.setVariable('workflow', metadata)

        return session
    }

    private ExecutionReport createExecutionReport(Session session) {
        def executionConfig = [enabled: true]
        def report = new ExecutionReport()
        report.init(session, executionConfig)
        return report
    }

    def 'execution report records a workflow_start event'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        when:
        report.onWorkflowStart()

        then:
        def result = report.toMap()
        assert result != null
        assert result.events.size() == 1
        assert result.events[0].event_type == 'workflow_start'
        assert result.events[0].run_name == session.runName
        assert result.events[0].script_id != null
        assert result.events[0].start_time != null
    }

    def 'execution report records a workflow_complete event'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        // Simulate workflow start then complete (invokeOnComplete sets the complete timestamp)
        def metadata = session.workflowMetadata
        metadata.invokeOnComplete()

        when:
        report.onWorkflowComplete()

        then:
        def result = report.toMap()
        assert result != null
        assert result.events.size() == 1
        assert result.events[0].event_type == 'workflow_complete'
        assert result.events[0].run_name == session.runName
        assert result.events[0].start_time != null
        assert result.events[0].end_time != null
        assert result.events[0].duration != null
    }

    def 'execution report records both start and complete events in sequence'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        when:
        report.onWorkflowStart()

        // Simulate workflow completion
        def metadata = session.workflowMetadata
        metadata.invokeOnComplete()
        report.onWorkflowComplete()

        then:
        def result = report.toMap()
        assert result.events.size() == 2
        assert result.events[0].event_type == 'workflow_start'
        assert result.events[1].event_type == 'workflow_complete'
    }

    def 'execution report contains correct workflow metadata in start event'() {
        given:
        def session = createSessionWithMetadata(
            scriptId: 'test-script-id',
            repository: 'https://github.com/test/pipeline',
            commitId: 'abc123commit',
            revision: 'develop',
            commandLine: 'nextflow run pipeline.nf --input data'
        )
        def report = createExecutionReport(session)

        when:
        report.onWorkflowStart()

        then:
        def result = report.toMap()
        def event = result.events[0]
        assert event.event_type == 'workflow_start'
        assert event.script_id == 'test-script-id'
        assert event.repository == 'https://github.com/test/pipeline'
        assert event.commit_id == 'abc123commit'
        assert event.revision == 'develop'
        assert event.command_line == 'nextflow run pipeline.nf --input data'
        assert event.script_name == 'main.nf'
        assert event.profile != null
        assert event.session_id != null
        assert event.user_name != null
        assert event.config_files != null
    }

    def 'execution report contains correct workflow metadata in complete event'() {
        given:
        def session = createSessionWithMetadata(
            scriptId: 'test-script-id',
            repository: 'https://github.com/test/pipeline',
            commitId: 'abc123commit',
            revision: 'develop'
        )
        def report = createExecutionReport(session)

        def metadata = session.workflowMetadata
        metadata.invokeOnComplete()

        when:
        report.onWorkflowComplete()

        then:
        def result = report.toMap()
        def event = result.events[0]
        assert event.event_type == 'workflow_complete'
        assert event.script_id == 'test-script-id'
        assert event.repository == 'https://github.com/test/pipeline'
        assert event.commit_id == 'abc123commit'
        assert event.revision == 'develop'
        assert event.success != null
        assert event.exit_status != null
        assert event.duration != null
    }

    def 'execution report is empty before any events'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        when:
        def result = report.toMap()

        then:
        assert result != null
        assert result.events.size() == 0
        assert result.report_type == 'execution-report'
    }

    def 'execution report does not record events when disabled'() {
        given:
        def session = createSessionWithMetadata()
        def config = [enabled: false]
        def report = new ExecutionReport()
        report.init(session, config)

        when:
        report.onWorkflowStart()

        then:
        def result = report.toMap()
        assert result.events.size() == 0
    }

    def 'execution report start event has correct timestamp format'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        when:
        report.onWorkflowStart()

        then:
        def result = report.toMap()
        def event = result.events[0]
        // timestamp should be ISO 8601 formatted
        assert event.timestamp != null
        // Verify it can be parsed as ISO date time
        def parsed = OffsetDateTime.parse(event.timestamp, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assert parsed != null
    }

    def 'execution report complete event contains start_time and end_time'() {
        given:
        def session = createSessionWithMetadata()
        def report = createExecutionReport(session)

        def metadata = session.workflowMetadata
        metadata.invokeOnComplete()

        when:
        report.onWorkflowComplete()

        then:
        def result = report.toMap()
        def event = result.events[0]
        assert event.start_time != null
        assert event.end_time != null
        // Verify both can be parsed
        def startParsed = OffsetDateTime.parse(event.start_time, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def endParsed = OffsetDateTime.parse(event.end_time, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        assert startParsed != null
        assert endParsed != null
        assert endParsed >= startParsed
    }

    def 'execution report triggered via observer records workflow_start event'() {
        given:
        def config = [
            docker: [enabled: true],
            nfreport: [
                executionReport: [
                    enabled: true
                ]
            ]
        ]
        Session session = Spy(Session, constructorArgs: [config])
        session.configFiles >> [Paths.get('/test/nextflow.config')]
        session.getStatsObserver() >> Mock(WorkflowStatsObserver) { getStats() >> new WorkflowStats() }
        session.fetchContainers() >> null
        session.commandLine >> 'nextflow run main.nf'

        def script = Mock(ScriptFile) {
            getScriptId() >> 'abc123'
            getMain() >> Paths.get('/test/main.nf')
            getRepository() >> 'https://github.com/test/repo'
            getCommitId() >> 'def456'
            getRevision() >> 'main'
            getLocalPath() >> Paths.get('/test/project')
            getProjectName() >> 'test/repo'
        }
        def metadata = new WorkflowMetadata(session, script)
        session.getWorkflowMetadata() >> metadata
        session.binding.setVariable('workflow', metadata)

        def observer = new NfReportObserver()

        when:
        // onFlowCreate calls onWorkflowStart on all reports
        observer.onFlowCreate(session)

        then:
        def executionReport = observer.reports.find { it instanceof ExecutionReport }
        assert executionReport != null
        def result = executionReport.toMap()
        assert result.events.size() == 1
        assert result.events[0].event_type == 'workflow_start'
    }

    def 'execution report triggered via observer records both start and complete events'() {
        given:
        def config = [
            docker: [enabled: true],
            nfreport: [
                executionReport: [
                    enabled: true
                ]
            ]
        ]
        Session session = Spy(Session, constructorArgs: [config])
        session.configFiles >> [Paths.get('/test/nextflow.config')]
        session.getStatsObserver() >> Mock(WorkflowStatsObserver) { getStats() >> new WorkflowStats() }
        session.fetchContainers() >> null
        session.commandLine >> 'nextflow run main.nf'

        def script = Mock(ScriptFile) {
            getScriptId() >> 'abc123'
            getMain() >> Paths.get('/test/main.nf')
            getRepository() >> 'https://github.com/test/repo'
            getCommitId() >> 'def456'
            getRevision() >> 'main'
            getLocalPath() >> Paths.get('/test/project')
            getProjectName() >> 'test/repo'
        }
        def metadata = new WorkflowMetadata(session, script)
        session.getWorkflowMetadata() >> metadata
        session.binding.setVariable('workflow', metadata)

        def observer = new NfReportObserver()

        when:
        observer.onFlowCreate(session)

        // Simulate workflow completion
        metadata.invokeOnComplete()
        observer.onFlowComplete()

        then:
        def executionReport = observer.reports.find { it instanceof ExecutionReport }
        assert executionReport != null
        def result = executionReport.toMap()
        assert result.events.size() == 2
        assert result.events[0].event_type == 'workflow_start'
        assert result.events[1].event_type == 'workflow_complete'
    }

}

package roche_csi.plugin

import nextflow.Session
import spock.lang.Specification

/**
 * Tests for the NfReportFactory and NfReportObserver
 */
class NfReportObserverTest extends Specification {

    def 'should create the observer instance' () {
        given:
        def factory = new NfReportFactory()

        when:
        def result = factory.create(new Session())

        then:
        result.size() == 1
        result.first() instanceof NfReportObserver
    }

    def 'should initialize observer with default configuration' () {
        given:
        def session = new Session()
        session.config = [
            nfreport: [:]
        ]
        def observer = new NfReportObserver()

        when:
        observer.onFlowCreate(session)

        then:
        noExceptionThrown()
    }

    def 'should handle missing configuration gracefully' () {
        given:
        def session = new Session()
        def observer = new NfReportObserver()

        when:
        observer.onFlowCreate(session)

        then:
        noExceptionThrown()
    }

    def 'should initialize with custom configuration' () {
        given:
        def customConfig = [
            outputDir: './custom-reports',
            sampleStatusReport: [
              enabled: true
            ],
            taskStatusReport: [
              enabled: true
            ],
        ]
        def session = new Session()
        session.config = [
            nfreport: customConfig
        ]
        def observer = new NfReportObserver()

        when:
        observer.onFlowCreate(session)

        then:
        noExceptionThrown()
        observer.reports.size() == 2
        observer.reports.findAll { report -> report instanceof SampleStatusReport }.size() == 1
        observer.reports.findAll { report -> report instanceof TaskStatusReport }.size() == 1
        observer.reports.findAll { report -> report instanceof ExecutionReport }.size() == 0
    }

}

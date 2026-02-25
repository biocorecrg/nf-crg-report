package roche_csi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.trace.TraceObserver
import nextflow.trace.TraceObserverFactory

/**
 * Implements a factory object required to create
 * the {@link NfReportObserver} instance.
 */
@Slf4j
@CompileStatic
class NfReportFactory implements TraceObserverFactory {

    @Override
    Collection<TraceObserver> create(Session session) {
        log.debug('Creating nf-report trace observer')
        return List.<TraceObserver>of(new NfReportObserver())
    }

}

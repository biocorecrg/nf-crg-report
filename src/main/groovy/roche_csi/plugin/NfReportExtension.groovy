package roche_csi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint
import roche_csi.plugin.config.NfReportConfig

@Slf4j
@CompileStatic
class NfReportExtension extends PluginExtensionPoint {

    private Session session
    private NfReportConfig pluginConfig

    @Override
    protected void init(Session session) {
        this.session = session
        def configMap = session.config.navigate('nfreport') as Map ?: [:]
        this.pluginConfig = new NfReportConfig(configMap)
        log.debug('nf-report extension initialized')
    }

    @Function
    Map getReportConfig() {
        def config = session.config.navigate('nfreport') as Map ?: [:]
        log.debug("Retrieved report config: ${config}")
        return config
    }

    @Function
    boolean isReportEnabled(String reportType) {
        if (!pluginConfig.enabled) {
            return false
        }

        switch (reportType) {
            case 'executionReport':
                return pluginConfig.executionReport.enabled
            case 'taskStatusReport':
                return pluginConfig.taskStatusReport.enabled
            case 'sampleStatusReport':
                return pluginConfig.sampleStatusReport.enabled
            default:
                log.debug("Unknown report type: ${reportType}")
                return false
        }
    }

    @Function
    String getReportsDir() {
        def outputDir = pluginConfig.outputDir
        log.debug("Reports directory: ${outputDir}")
        return outputDir
    }

}

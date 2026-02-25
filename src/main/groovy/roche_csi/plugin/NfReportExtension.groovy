package roche_csi.plugin

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.plugin.extension.Function
import nextflow.plugin.extension.PluginExtensionPoint

/**
 * Implements custom functions for the nf-report plugin
 */
@Slf4j
@CompileStatic
class NfReportExtension extends PluginExtensionPoint {

    private Session session

    @Override
    protected void init(Session session) {
        this.session = session
        log.debug('nf-report extension initialized')
    }

    /**
     * Get the current configuration for the nf-report plugin
     *
     * @return Map containing the plugin configuration
     */
    @Function
    Map getReportConfig() {
        def config = session.config.navigate('nfreport') as Map ?: [:]
        log.debug("Retrieved report config: ${config}")
        return config
    }

    /**
     * Check if a specific report type is enabled
     *
     * @param reportType The type of report to check (execution, taskStatus, sampleStatus)
     * @return boolean indicating if the report is enabled
     */
    @Function
    boolean isReportEnabled(String reportType) {
        def config = session.config.navigate('nfreport') as Map ?: [:]

        // Check global enabled flag first
        if (config.enabled == false) {
            return false
        }

        // Check specific report configuration
        def reportConfig = config[reportType] as Map ?: [:]
        boolean enabled = reportConfig.enabled != false

        log.debug("Report ${reportType} enabled: ${enabled}")
        return enabled
    }

    /**
     * Get the output directory for reports
     *
     * @return String path to the reports output directory
     */
    @Function
    String getReportsDir() {
        def config = session.config.navigate('nfreport') as Map ?: [:]
        def outputDir = config.outputDir ?: './reports'
        log.debug("Reports directory: ${outputDir}")
        return outputDir.toString()
    }

}

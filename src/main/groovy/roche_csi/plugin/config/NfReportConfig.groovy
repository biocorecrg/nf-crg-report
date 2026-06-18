package roche_csi.plugin.config

import groovy.util.logging.Slf4j
import nextflow.script.dsl.Description
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ScopeName

@Slf4j
@ScopeName('nfreport')
@Description('Configuration for the nf-crg-report plugin')
class NfReportConfig implements ConfigScope {

    @ConfigOption
    @Description('Enable or disable the nf-report plugin globally')
    Boolean enabled = true

    @ConfigOption
    @Description('Default output format for reports (json, html, or tsv)')
    String format = 'json'

    @ConfigOption
    @Description('Default output directory for reports')
    String outputDir = './reports'

    @ConfigOption
    @Description('Default prefix for report filenames')
    String prefix = ''

    @ConfigOption
    @Description('Default suffix for report filenames')
    String suffix

    @ConfigOption
    @Description('Create a symbolic link to the latest report')
    Boolean createLinkToLatestReport = false

    @Description('Execution report configuration')
    GenericReportConfig executionReport

    @Description('Task status report configuration')
    GenericReportConfig taskStatusReport

    @Description('Sample status report configuration')
    SampleStatusReportConfig sampleStatusReport

    @Description('Email notification configuration')
    EmailConfig email

    @ConfigOption
    @Description('Custom costs configuration')
    Map costs = [:]

    NfReportConfig() { }

    NfReportConfig(Map config) {
        if (!config) {
            config = [:]
        }

        if (config.containsKey('enabled')) {
            if (config.enabled in Boolean) {
                this.enabled = config.enabled
            } else {
                log.warn("Invalid value for 'nfreport.enabled', expected a Boolean. Using default: ${this.enabled}")
            }
        }
        if (config.containsKey('format')) {
            if (config.format in CharSequence) {
                this.format = config.format.toString().toLowerCase()
            } else {
                log.warn("Invalid value for 'nfreport.format', expected a String. Using default: ${this.format}")
            }
        }
        if (config.containsKey('outputDir')) {
            if (config.outputDir in CharSequence) {
                this.outputDir = config.outputDir.toString()
            } else {
                log.warn("Invalid value for 'nfreport.outputDir', expected a String. Using default: ${this.outputDir}")
            }
        }
        if (config.containsKey('prefix')) {
            if (config.prefix in CharSequence) {
                this.prefix = config.prefix.toString()
            } else {
                log.warn("Invalid value for 'nfreport.prefix', expected a String. Using default: ${this.prefix}")
            }
        }
        if (config.containsKey('suffix')) {
            if (config.suffix == null) {
                this.suffix = null
            } else if (config.suffix in CharSequence) {
                this.suffix = config.suffix.toString()
            } else {
                log.warn("Invalid value for 'nfreport.suffix', expected a String. Using default: ${this.suffix}")
            }
        }
        if (config.containsKey('createLinkToLatestReport')) {
            if (config.createLinkToLatestReport in Boolean) {
                this.createLinkToLatestReport = config.createLinkToLatestReport
            } else {
                log.warn("Invalid value for 'nfreport.createLinkToLatestReport', expected a Boolean. Using default: ${this.createLinkToLatestReport}")
            }
        }

        if (config.containsKey('costs')) {
            if (config.costs in Map) {
                this.costs = config.costs
            } else {
                log.warn("Invalid value for 'nfreport.costs', expected a Map. Using default: ${this.costs}")
            }
        }

        def defaults = [
            format: this.format,
            outputDir: this.outputDir,
            prefix: this.prefix,
            suffix: this.suffix,
            createLinkToLatestReport: this.createLinkToLatestReport,
        ]

        this.executionReport = new GenericReportConfig(
            config.executionReport instanceof Map ? config.executionReport as Map : [:],
            defaults
        )
        this.taskStatusReport = new GenericReportConfig(
            config.taskStatusReport instanceof Map ? config.taskStatusReport as Map : [:],
            defaults
        )
        this.sampleStatusReport = new SampleStatusReportConfig(
            config.sampleStatusReport instanceof Map ? config.sampleStatusReport as Map : [:],
            defaults
        )
        this.email = new EmailConfig(
            config.email instanceof Map ? config.email as Map : [:]
        )
    }

}

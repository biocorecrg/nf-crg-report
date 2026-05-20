package roche_csi.plugin.config

import groovy.util.logging.Slf4j
import nextflow.config.spec.ConfigScope
import nextflow.config.spec.ConfigOption
import nextflow.script.dsl.Description

@Slf4j
class SampleStatusReportConfig implements ConfigScope {

    @ConfigOption
    @Description('Enable or disable this report')
    Boolean enabled = false

    @ConfigOption
    @Description('Output format for the report (json, html, tsv, or a list)')
    def format = 'json'

    @ConfigOption
    @Description('Output directory for the report')
    String outputDir = './reports'

    @ConfigOption
    @Description('Prefix for the report filename')
    String prefix = ''

    @ConfigOption
    @Description('Suffix for the report filename')
    String suffix = ''

    @ConfigOption
    @Description('Custom output filename for the report')
    String outputFilename

    @ConfigOption
    @Description('List of fields to include in the report')
    List<String> fields = []

    @ConfigOption
    @Description('Path to a custom HTML template')
    String htmlTemplatePath

    @ConfigOption
    @Description('Create a symbolic link to the latest report')
    Boolean createLinkToLatestReport = false

    @ConfigOption
    @Description('Extract sample name from task tag or meta_map')
    String extractSampleNameFrom = 'tag'

    @ConfigOption
    @Description('Regex pattern for extracting sample name from task tag')
    String sampleNameTagPattern

    @ConfigOption
    @Description('Regex pattern for matching meta map keys to extract sample name')
    String sampleNameMetaKeyPattern

    @ConfigOption
    @Description('Print a completion summary listing samples by status when the workflow finishes')
    Boolean printCompletionSummary = false

    SampleStatusReportConfig() { }

    SampleStatusReportConfig(Map config, Map defaults = [:]) {
        if (!config) {
            config = [:]
        }

        if (config.containsKey('enabled')) {
            this.enabled = config.enabled as Boolean
        }
        this.format = config.containsKey('format') ? config.format : defaults.format ?: 'json'
        this.outputDir = config.containsKey('outputDir') ? config.outputDir?.toString() : defaults.outputDir ?: './reports'
        this.prefix = config.containsKey('prefix') ? config.prefix?.toString() ?: '' : defaults.prefix ?: ''
        this.suffix = config.containsKey('suffix') ? config.suffix?.toString() ?: '' : defaults.suffix ?: ''
        if (config.containsKey('outputFilename')) {
            this.outputFilename = config.outputFilename?.toString()
        }
        if (config.containsKey('fields')) {
            this.fields = config.fields instanceof List ? config.fields as List<String> : []
        }
        if (config.containsKey('htmlTemplatePath')) {
            this.htmlTemplatePath = config.htmlTemplatePath?.toString()
        }
        if (config.containsKey('createLinkToLatestReport')) {
            this.createLinkToLatestReport = config.createLinkToLatestReport as Boolean
        } else if (defaults.containsKey('createLinkToLatestReport')) {
            this.createLinkToLatestReport = defaults.createLinkToLatestReport as Boolean
        }
        if (config.containsKey('extractSampleNameFrom')) {
            this.extractSampleNameFrom = config.extractSampleNameFrom?.toString() ?: 'tag'
        }
        if (config.containsKey('sampleNameTagPattern')) {
            this.sampleNameTagPattern = config.sampleNameTagPattern?.toString()
        }
        if (config.containsKey('sampleNameMetaKeyPattern')) {
            this.sampleNameMetaKeyPattern = config.sampleNameMetaKeyPattern?.toString()
        }
        if (config.containsKey('printCompletionSummary')) {
            this.printCompletionSummary = config.printCompletionSummary as Boolean
        }
    }

    Map toMap() {
        def map = [
            enabled: enabled,
            format: format,
            outputDir: outputDir,
            prefix: prefix,
            suffix: suffix,
            outputFilename: outputFilename,
            fields: fields,
            htmlTemplatePath: htmlTemplatePath,
            createLinkToLatestReport: createLinkToLatestReport,
            extractSampleNameFrom: extractSampleNameFrom,
            sampleNameTagPattern: sampleNameTagPattern,
            sampleNameMetaKeyPattern: sampleNameMetaKeyPattern,
            printCompletionSummary: printCompletionSummary,
        ]
        return map
    }

}

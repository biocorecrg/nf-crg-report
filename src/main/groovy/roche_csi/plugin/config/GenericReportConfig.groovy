package roche_csi.plugin.config

import groovy.util.logging.Slf4j
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.script.dsl.Description

@Slf4j
class GenericReportConfig implements ConfigScope {

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
    String suffix

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

    GenericReportConfig() { }

    GenericReportConfig(Map config, Map defaults = [:]) {
        if (!config) {
            config = [:]
        }

        if (config.containsKey('enabled')) {
            this.enabled = config.enabled as Boolean
        }
        this.format = config.containsKey('format') ? config.format : defaults.format ?: 'json'
        this.outputDir = config.containsKey('outputDir') ? config.outputDir?.toString() : defaults.outputDir ?: './reports'
        this.prefix = config.containsKey('prefix') ? config.prefix?.toString() ?: '' : defaults.prefix ?: ''
        this.suffix = config.containsKey('suffix') ? (config.suffix != null ? config.suffix.toString() : null) : (defaults.containsKey('suffix') ? (defaults.suffix != null ? defaults.suffix.toString() : null) : null)
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
    }

    Map toMap() {
        def map = [
            enabled: enabled,
            format: format,
            outputDir: outputDir,
            outputFilename: outputFilename,
            prefix: prefix,
            suffix: suffix,
            createLinkToLatestReport: createLinkToLatestReport,
            fields: fields,
            htmlTemplatePath: htmlTemplatePath,
        ]
        return map
    }

}

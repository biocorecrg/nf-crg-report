package roche_csi.plugin.config

import groovy.util.logging.Slf4j
import nextflow.config.spec.ConfigOption
import nextflow.config.spec.ConfigScope
import nextflow.script.dsl.Description

@Slf4j
class EmailConfig implements ConfigScope {

    @ConfigOption
    @Description('Enable or disable email notifications')
    Boolean enabled = false

    @ConfigOption
    @Description('Email subject line')
    String subject = 'Workflow Report'

    @ConfigOption
    @Description('Comma-separated list of recipient email addresses')
    String to

    @ConfigOption
    @Description('Comma-separated list of CC email addresses')
    String cc

    @ConfigOption
    @Description('Comma-separated list of BCC email addresses')
    String bcc

    @ConfigOption
    @Description('Path to a custom email template')
    String template

    EmailConfig() { }

    EmailConfig(Map config) {
        if (!config) {
            config = [:]
        }

        if (config.containsKey('enabled')) {
            this.enabled = config.enabled as Boolean
        }
        if (config.containsKey('subject')) {
            this.subject = config.subject?.toString() ?: 'Workflow Report'
        }
        if (config.containsKey('to')) {
            this.to = config.to?.toString()
        }
        if (config.containsKey('cc')) {
            this.cc = config.cc?.toString()
        }
        if (config.containsKey('bcc')) {
            this.bcc = config.bcc?.toString()
        }
        if (config.containsKey('template')) {
            this.template = config.template?.toString()
        }
    }

    Map toMap() {
        def map = [
            enabled: enabled,
            subject: subject,
            to: to,
            cc: cc,
            bcc: bcc,
            template: template,
        ]
        return map
    }

}

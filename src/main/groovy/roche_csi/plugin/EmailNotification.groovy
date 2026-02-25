package roche_csi.plugin

import java.io.InputStreamReader
import static nextflow.Nextflow.sendMail
import groovy.text.GStringTemplateEngine
import groovy.util.logging.Slf4j

@Slf4j
class EmailNotification {

    static void sendEmailNotification(Map emailConfig, List<BaseReport> reports) {
        def binding = [:]
        if (reports.isEmpty()) {
            log.warn('No reports available to send. Email will not contain any report data.')
        }
        reports.each { report ->
            binding << [(report.name.replaceAll('-', '_')): report.toMap()]
        }

        def attachments = []
        reports.each { report ->
            attachments << report.outputFiles.values().findAll { it.exists() }
        }
        def engine = new GStringTemplateEngine()
        def template = null
        if (emailConfig.template) {
            def templateFile = new File(emailConfig.template)
            if (!templateFile.exists()) {
                log.error("HTML template file not found at: ${emailConfig.template}")
                return
            }
            template = engine.createTemplate(templateFile)
        } else {
            def defaultTemplateStream = EmailNotification.class.getResourceAsStream('/roche_csi/plugin/email-template.html')
            template = engine.createTemplate(new InputStreamReader(defaultTemplateStream))
        }
        def body = template.make(binding).toString()
        try {
            sendMail(
                to: emailConfig.to,
                cc: emailConfig.cc,
                bcc: emailConfig.bcc,
                subject: emailConfig.subject,
                body: body,
                attachments: attachments
            )
        } catch (Exception e) {
            log.error('Error sending email notification', e)
        }
    }

}

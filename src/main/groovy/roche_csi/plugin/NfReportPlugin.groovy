package roche_csi.plugin

import groovy.transform.CompileStatic
import nextflow.plugin.BasePlugin
import org.pf4j.PluginWrapper

/**
 * The plugin entry point
 */
@CompileStatic
class NfReportPlugin extends BasePlugin {

    NfReportPlugin(PluginWrapper wrapper) {
        super(wrapper)
    }

}

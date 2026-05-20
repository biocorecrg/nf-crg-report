# nf-report Plugin

A comprehensive reporting plugin for Nextflow that generates various reports during workflow execution.

## Summary

The nf-report plugin provides multiple configurable report types:

- **Execution Report**: Provides a detailed overview of the workflow execution, including start/end times, parameters, and metadata.
- **Task Status Report**: Summarizes task execution details grouped by status (e.g., COMPLETED, FAILED, CACHED, RETRIED, ABORTED, IGNORED).
- **Sample Status Report**: Tracks sample processing across the workflow, with samples grouped by their processing status.

The reports can be generated in JSON and HTML formats, with customizable templates for HTML reports.

## Get Started

Add the plugin to your Nextflow configuration:

```groovy
plugins {
    id 'nf-report'
}
```

To enable reports, follow the instruction to configure the plugin in your `nextflow.config` file.

## Configuration

Configure the plugin in your `nextflow.config` file using the `nfreport` scope.

### Common options

The following options can be specified directly in the `nfreport` scope and they will apply to all report types.

| Option | Description | Default |
|--------|-------------|---------|
| `format` | Format of the report (json, html, or tsv)  | json |
| `outputDir` | Output directory for the report files | ./reports |
| `prefix` | Prefix for the report filename |  |
| `suffix` | Suffix for the report filename |  |
| `createLinkToLatestReport` | Create a symbolic link to the latest report | false |

The following options are available for all report types and can be specified in the scope of each report type. The available report types are: `executionReport`, `taskStatusReport`, and `sampleStatusReport`. If a report type is not enabled, its specific options will be ignored. If an option is also specified in the `nfreport` scope, the report type-specific option will take precedence.

| Option | Description | Default |
|--------|-------------|---------|
| `(reportType).enabled` | Enables the report generation of the specified report type | false |
| `(reportType).format` | Format of the report (json, html, tsv). Override the format specified in the `nfreport` scope. | json |
| `(reportType).fields` | Fields to include in the report. Leave empty for all fields |  |
| `(reportType).htmlTemplatePath` | HTML template for the report |  |
| `(reportType).prefix` | Prefix for the report filename. Override the prefix specified in the `nfreport` scope. |  |
| `(reportType).suffix` | Suffix for the report filename. Override the suffix specified in the `nfreport` scope. |  |
| `(reportType).outputDir` | Output directory for the report files | ./reports |
| `(reportType).outputFilename` | Output filename for the report | `execution-report`, `task-status-report`, or `sample-status-report` |
| `(reportType).createLinkToLatestReport` | Create a symbolic link to the latest report. Override the setting specified in the `nfreport` scope. | false |

Below is an example configuration demonstrating how to specify global options and report-specific options:

```groovy
nfreport {
    // Global options
    format = 'json' // or 'html'
    outputDir = './reports'
    prefix = 'myworkflow-'
    suffix = "-${new Date().format('yyyyMMdd')}"
    createLinkToLatestReport = true

    // Report specific options
    executionReport {
        enabled = true
        createLinkToLatestReport = false // Override global setting
    }

    taskStatusReport {
        enabled = true
        outputFilename = 'task-status-report' // Specify custom output filename
        format = 'html' // Overrides format in nfreport scope
        htmlTemplatePath = 'task-status-report-template.html' // Use custom HTML template to render report
    }

    sampleStatusReport {
        enabled = true
        format = 'html' // Overrides format in nfreport scope
    }
}
```

### Sample status report options

The following options are specific to the `sampleStatusReport`:

| Option | Description | Default |
|--------|-------------|---------|
| `sampleStatusReport.extractSampleNameFrom` | Method to extract sample names (tag or meta_map) | tag |
| `sampleStatusReport.sampleNameTagPattern` | Pattern for extracting sample names from task tags. Leave empty to use the entire tag |  |
| `sampleStatusReport.sampleNameMetaKeyPattern` | Pattern for extracting sample names from metadata maps. Leave empty to use the first element in the meta map. |  |
| `sampleStatusReport.printCompletionSummary` | Print a summary listing samples by status when the workflow finishes | false |

#### Sample Name Extraction for Sample Status Reports

The sample status report provides flexibility in extracting sample names from workflow tasks. You can configure how sample names are extracted using the following options in your `nextflow.config` file:

- **Extract from Task Tags**: By default, sample names are extracted from the `tag` field of the task configuration. You can specify a pattern to match the tag using the `sampleNameTagPattern` option. If no pattern is provided, the entire tag will be used as the sample name.

- **Extract from Metadata Map**: Alternatively, sample names can be extracted from the metadata map of task inputs. Use the `sampleNameMetaKeyPattern` option to define a pattern for matching metadata keys. The sample name will be taken from the value of the first matching key. If no pattern is provided, the first element in the metadata map will be used.

- **Collection Support**: When using `meta_map` extraction, if the matched value is a Collection (e.g., a list of sample IDs), the task will be attributed to all samples in the collection. This is useful when a single task processes multiple samples. Non-string elements in the collection are converted via `toString()`. Empty collections are ignored.

The following configuration demonstrates how to set these options: this configuration will configure the sample status report to extract sample names from the element with the key `sample_id` in the metadata map.

```groovy
nfreport {
    sampleStatusReport {
        enabled = true
        extractSampleNameFrom = 'meta_map'  // Options: 'tag' (default) or 'meta_map'
        sampleNameMetaKeyPattern = 'sample_id' // Pattern for extracting from metadata maps (will match meta maps like [sample_id: 'sample-01', ...])
    }
}
```

If `sample_id` contains a list (e.g., `[sample_id: ['sampleA', 'sampleB']]`), the task will be tracked under both `sampleA` and `sampleB`.

### Email notification options

The following options are used to configure email notification.

| Option | Description | Default |
|--------|-------------|---------|
| `email.enabled` | Enable email notification | false |
| `email.subject` | Subject line for email notifications | "Workflow Report" |
| `email.to` | A comma-separated list of email addresses to send notifications to. Required if `email.enabled` is true |  |
| `email.cc` | A comma-separated list of email addresses to CC on notifications |  |
| `email.bcc` | A comma-separated list of email addresses to BCC on notifications |  |
| `email.template` | A custom email template for notifications |  |

The email notification uses the same email system as Nextflow. See https://nextflow.io/docs/latest/notifications.html#mail-configuration for details on how to configure email settings such as SMTP server and port.

### Full configuration example

```groovy
nfreport {
    outputDir = './reports'
    prefix = 'myworkflow-'
    createLinkToLatestReport = true

    // Execution report configuration
    executionReport {
        enabled = true
    }

    // Task status JSON report
    taskStatusReport {
        enabled = true
    }

    // Sample status JSON report
    sampleStatusReport {
        enabled = true
        extractSampleNameFrom = 'meta_map'
        sampleNameMetaKeyPattern = 'sample_id'
    }

    // Email notifications
    email {
        enabled = true
        subject = "Workflow Report"
        to = "user@example.com"
        cc = "cc@example.com"
        bcc = "bcc@example.com"
    }
}
```

## Output format

The plugin supports multiple output formats for reports, including:

- **HTML**: Render reports as HTML documents. This format is suitable for viewing in a web browser and can include custom styling.
- **JSON**: Generate reports in JSON format. This format is useful for programmatic access and integration with other tools.
- **TSV**: Generate reports in TSV (Tab-Separated Values) format. This format is useful for viewing directly in a terminal or in spreadsheet applications. However, due to its simplicity, it does not support nested structures or grouping of data. Sample Status Report and Task Status Report will be flattened when generated in TSV format.

### Creating custom HTML and email templates

nf-report uses `GStringTemplateEngine` to render HTML reports and email notification from templates. The default template files can be found under `src/main/resources/roche_csi/plugin`. The variable names used in the templates are based on the report schema. The report schema defines the structure and content of the data that will be included in the reports and notifications. We provide the schema files for each report type as [JSON Schema](https://json-schema.org/) in the `examples` directory so advanced users can create and use their own templates.

Note: If a field is filtered out by the report configuration (`fields` option), it will not be available when rendering the templates.

## Building

To build the plugin:
```bash
make assemble
```

## Testing with Nextflow

The plugin can be tested without a local Nextflow installation:

1. Build and install the plugin to your local Nextflow installation: `make install`
2. Run a pipeline with the plugin: `nextflow run hello -plugins nf-report@1.1.0`

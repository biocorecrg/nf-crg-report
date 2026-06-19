package roche_csi.plugin

import java.time.format.DateTimeFormatter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import groovy.json.JsonBuilder
import groovy.util.logging.Slf4j
import nextflow.Session
import nextflow.processor.TaskHandler
import nextflow.processor.TaskRun
import nextflow.trace.TraceRecord
import nextflow.util.MemoryUnit
import nextflow.script.params.FileOutParam
import nextflow.script.params.TupleOutParam
import groovy.text.GStringTemplateEngine

/**
 * Generates task status reports with tasks grouped by status
 */
@Slf4j
class TaskStatusReport extends BaseReport {

    private Map<String, List<Map>> tasksByStatus = [
        'COMPLETED': [],
        'CACHED': [],
        'FAILED': [],
        'ABORTED': [],
        'RETRIED': [],
        'IGNORED': []
    ]

    private List<Map> tasks = []
    private boolean hasLoggedPricingWarning = false
    private boolean hasLoggedPricingRates = false

    TaskStatusReport() {
        super('task-status-report')
    }

    @Override
    void init(Session session, Map config) {
        super.init(session, config)
    }

    @Override
    void onTaskComplete(TaskHandler handler, TraceRecord trace) {
        super.onTaskComplete(handler, trace)
        if (!enabled) {
            return
        }

        def status = trace.get('status')?.toString() ?: 'COMPLETED'
        if (trace.error_action == 'RETRY') {
            status = 'RETRIED'
        } else if (trace.error_action == 'IGNORE') {
            status = 'IGNORED'
        }
        def taskData = createTaskData(handler, trace, status).findAll { k, v -> fields.isEmpty() || fields.contains(k) }
        recordTask(status, taskData)

        writeReport()
    }

    @Override
    void onTaskCached(TaskHandler handler, TraceRecord trace) {
        super.onTaskCached(handler, trace)
        if (!enabled) {
            return
        }

        def taskData = createTaskData(handler, trace, 'CACHED')
        recordTask('CACHED', taskData)

        writeReport()
    }

    @Override
    void onWorkflowComplete() {
        super.onWorkflowComplete()
        if (!enabled) {
            return
        }

        writeReport()
    }

    List<String> getOutputs(TaskHandler handler) {
        final task = handler.task as TaskRun
        final outputParams = task.getOutputsByType(FileOutParam)
        def outputs = []
        outputParams.each { param, files ->
            final fileList = files instanceof List ? files : [files]
            fileList.each { file ->
                if (file != null) {
                    outputs << file.toString()
                }
            }
        }
        return outputs
    }

    private Map createTaskData(TaskHandler handler, TraceRecord trace, String status) {
        def costConfig = session.config.navigate('nfreport.costs') as Map ?: [:]
        def priceJsonPath = costConfig.priceJsonPath ?: null
        def priceAPI = costConfig.priceAPI ?: null

        def prices = [:]
        if (priceAPI) {
            try {
                prices = fetchPriceAPI(priceAPI)
                if (prices == null || prices.isEmpty()) {
                    synchronized(this) {
                        if (!hasLoggedPricingWarning) {
                            log.warn("Pricing API at ${priceAPI} returned an empty response. Cost estimation will default to configured/default rates.")
                            hasLoggedPricingWarning = true
                        }
                    }
                }
            } catch (Exception e) {
                synchronized(this) {
                    if (!hasLoggedPricingWarning) {
                        log.warn("Pricing API at ${priceAPI} is not working or does not exist: ${e.message}. Cost estimation will default to configured/default rates.")
                        hasLoggedPricingWarning = true
                    }
                }
            }
        } else if (priceJsonPath) {
            try {
                def file = new File(priceJsonPath)
                if (file.exists()) {
                    prices = parsePriceJson(priceJsonPath)
                    if (prices == null || prices.isEmpty()) {
                        synchronized(this) {
                            if (!hasLoggedPricingWarning) {
                                log.warn("Pricing JSON at ${priceJsonPath} is empty. Cost estimation will default to configured/default rates.")
                                hasLoggedPricingWarning = true
                            }
                        }
                    }
                } else {
                    synchronized(this) {
                        if (!hasLoggedPricingWarning) {
                            log.warn("Pricing JSON file not found at: ${priceJsonPath}. Cost estimation will default to 0.0.")
                            hasLoggedPricingWarning = true
                        }
                    }
                }
            } catch (Exception e) {
                synchronized(this) {
                    if (!hasLoggedPricingWarning) {
                        log.warn("Failed to parse pricing JSON: ${e.message}")
                        hasLoggedPricingWarning = true
                    }
                }
            }
        } else {
            synchronized(this) {
                if (!hasLoggedPricingWarning) {
                    log.info("No pricing API or JSON path configured. Cost estimation will default to 0.0.")
                    hasLoggedPricingWarning = true
                }
            }
        }

        def kCPUHr = (prices.kCPUHr != null ? prices.kCPUHr : costConfig.kCPUHr ?: 0.0) as double
        def kGBHr = (prices.kGBHr != null ? prices.kGBHr : costConfig.kGBHr ?: 0.0) as double
        def kGPUGBHr = (prices.kGPUGBHr != null ? prices.kGPUGBHr : costConfig.kGPUGBHr ?: 0.0) as double
        def tbMonthRate = (prices.TBMonth != null ? prices.TBMonth : costConfig.TBMonth ?: 0.0) as double
        def defaultGpuMemGb = (costConfig.defaultGpuMemGb ?: 16) as double

        def cpuRate = kCPUHr / 1000.0
        def memGbRate = kGBHr / 1000.0
        def gpuGbRate = kGPUGBHr / 1000.0

        synchronized(this) {
            if (!hasLoggedPricingRates) {
                log.info("Pricing rates loaded: currency=${prices.currency ?: costConfig.currency ?: 'EUR'}, kCPUHr=${kCPUHr}, kGBHr=${kGBHr}, kGPUGBHr=${kGPUGBHr}, TBMonth=${tbMonthRate}")
                hasLoggedPricingRates = true
            }
        }

        def keys = trace.keySet()
        def cpus = (keys.contains('cpus') ? trace.get('cpus') ?: 1 : 1) as int
        def memoryBytes = (keys.contains('memory') ? trace.get('memory') ?: 0 : 0) as long
        def memoryGb = memoryBytes / (1024.0 * 1024.0 * 1024.0)
        def gpus = (keys.contains('accelerator') ? trace.get('accelerator') ?: 0 : 0) as int

        def realtimeMs = (keys.contains('realtime') ? trace.get('realtime') ?: 0 : 0) as long
        def hours = realtimeMs / (1000.0 * 60.0 * 60.0)

        def cpuCost = cpus * hours * cpuRate
        def memCost = memoryGb * hours * memGbRate
        def gpuCost = gpus * defaultGpuMemGb * hours * gpuGbRate
        def totalComputeCost = cpuCost + memCost + gpuCost

        def outputPaths = getOutputs(handler)
        long totalOutputSizeBytes = 0
        outputPaths.each { pathStr ->
            try {
                def path = java.nio.file.Paths.get(pathStr)
                if (java.nio.file.Files.exists(path) && java.nio.file.Files.isRegularFile(path)) {
                    totalOutputSizeBytes += java.nio.file.Files.size(path)
                }
            } catch (Exception e) {
                // Ignore paths that cannot be accessed or resolved
            }
        }
        def outputTb = totalOutputSizeBytes / (1024.0 * 1024.0 * 1024.0 * 1024.0)
        def monthlyStorageCost = outputTb * tbMonthRate
        def totalEstimatedCost = totalComputeCost + monthlyStorageCost

        def submit_time = Instant.ofEpochMilli((trace.get('submit') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def start_time = Instant.ofEpochMilli((trace.get('start') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        def complete_time = Instant.ofEpochMilli((trace.get('complete') ?: 0) as Long)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            def symbol = getCurrencySymbol()
            return [
                task_id: trace.get('task_id')?.toString(),
                hash: trace.get('hash')?.toString(),
                process_name: handler.task.processor.name,
                task_name: handler.task.name,
                tag: handler.task.config.tag?.toString(),
                status: trace.get('status')?.toString() ?: status,
                exit_status: trace.get('exit'),
                compute_cost: "${symbol}${String.format('%.4f', totalComputeCost)}",
                projected_monthly_storage_cost: "${symbol}${String.format('%.4f', monthlyStorageCost)}",
                total_estimated_cost: "${symbol}${String.format('%.4f', totalEstimatedCost)}",
            raw_compute_cost: totalComputeCost,
            raw_storage_cost: monthlyStorageCost,
            allocated_cpus: cpus,
            allocated_mem_gb: String.format('%.2f GB', memoryGb),
            allocated_gpus: gpus,
            output_size: String.format('%.4f GB', totalOutputSizeBytes / (1024.0 * 1024.0 * 1024.0)),
            submit_time: submit_time,
            start_time: start_time,
            complete_time: complete_time,
            duration_ms: trace.get('duration') ?: 0,
            realtime_ms: trace.get('realtime') ?: 0,
            attempt: handler.task.config.attempt ?: 1,
            rchar: (trace.get('rchar') != null) ? MemoryUnit.of(trace.get('rchar') ?: 0).toString() : null,
            wchar: (trace.get('wchar') != null) ? MemoryUnit.of(trace.get('wchar') ?: 0).toString() : null,
            pct_cpu: trace.get('%cpu'),
            peak_rss: (trace.get('peak_rss') != null) ? MemoryUnit.of(trace.get('peak_rss') ?: 0).toString() : null,
            peak_vmem: (trace.get('peak_vmem') != null) ? MemoryUnit.of(trace.get('peak_vmem') ?: 0).toString() : null,
            workdir: trace.get('workdir')?.toString(),
            log: trace.get('workdir') ? "${trace.get('workdir')}/.command.log" : null,
            out: trace.get('workdir') ? "${trace.get('workdir')}/.command.out" : null,
            err: trace.get('workdir') ? "${trace.get('workdir')}/.command.err" : null,
            scratch: trace.get('scratch')?.toString(),
            container: trace.get('container')?.toString(),
            outputs: getOutputs(handler)
        ]
    }

    private void recordTask(String status, Map taskData) {
        synchronized(tasksByStatus) {
            if (taskData && tasksByStatus.containsKey(status)) {
                tasksByStatus[status] << taskData
            }
        }
        synchronized(tasks) {
            tasks << taskData
        }
    }

    @Override
    Map toMap() {
        def map = super.toMap()
        synchronized(tasksByStatus) {
            double totalCompute = 0.0
            double totalStorage = 0.0
            synchronized(tasks) {
                tasks.each { task ->
                    totalCompute += (task.raw_compute_cost ?: 0.0) as double
                    totalStorage += (task.raw_storage_cost ?: 0.0) as double
                }
            }
            def costConfig = session.config.navigate('nfreport.costs') as Map ?: [:]

            // Read pricing to get rates
            def priceJsonPath = costConfig.priceJsonPath ?: null
            def priceAPI = costConfig.priceAPI ?: null
            def prices = [:]
            if (priceAPI) {
                try { prices = fetchPriceAPI(priceAPI) } catch (Exception e) {}
            } else if (priceJsonPath) {
                try { prices = parsePriceJson(priceJsonPath) } catch (Exception e) {}
            }

            def kCPUHr = (prices.kCPUHr != null ? prices.kCPUHr : costConfig.kCPUHr ?: 0.0) as double
            def kGBHr = (prices.kGBHr != null ? prices.kGBHr : costConfig.kGBHr ?: 0.0) as double
            def kGPUGBHr = (prices.kGPUGBHr != null ? prices.kGPUGBHr : costConfig.kGPUGBHr ?: 0.0) as double
            def tbMonthRate = (prices.TBMonth != null ? prices.TBMonth : costConfig.TBMonth ?: 0.0) as double
            def defaultGpuMemGb = (costConfig.defaultGpuMemGb ?: 16) as double

            def launchFile = costConfig.launchFile ?: null
            double headJobCost = 0.0
            if (launchFile) {
                def parsed = parseLaunchScript(launchFile)
                if (parsed.cpus > 0 || parsed.memoryGb > 0.0 || parsed.gpus > 0) {
                    def workflowInfo = session.workflowMetadata
                    long durationMs = 0
                    if (workflowInfo.start) {
                        def start = workflowInfo.start.toInstant()
                        def complete = workflowInfo.complete ? workflowInfo.complete.toInstant() : Instant.now()
                        durationMs = java.time.Duration.between(start, complete).toMillis()
                    }
                    double hours = durationMs / (1000.0 * 60.0 * 60.0)

                    def cpuRate = kCPUHr / 1000.0
                    def memGbRate = kGBHr / 1000.0
                    def gpuGbRate = kGPUGBHr / 1000.0

                    def cpuCost = parsed.cpus * hours * cpuRate
                    def memCost = parsed.memoryGb * hours * memGbRate
                    def gpuCost = parsed.gpus * defaultGpuMemGb * hours * gpuGbRate
                    headJobCost = cpuCost + memCost + gpuCost
                }
            }

            double finalCompute = totalCompute + headJobCost
            def symbol = getCurrencySymbol()

            def costByProcess = [:]
            synchronized(tasks) {
                tasks.each { task ->
                    def proc = task.process_name ?: 'unknown'
                    def rawCompute = (task.raw_compute_cost ?: 0.0) as double
                    def rawStorage = (task.raw_storage_cost ?: 0.0) as double
                    
                    if (!costByProcess.containsKey(proc)) {
                        costByProcess[proc] = [
                            count: 0,
                            compute_cost: 0.0,
                            projected_monthly_storage_cost: 0.0,
                            total_estimated_cost: 0.0
                        ]
                    }
                    def entry = costByProcess[proc]
                    entry.count += 1
                    entry.compute_cost += rawCompute
                    entry.projected_monthly_storage_cost += rawStorage
                    entry.total_estimated_cost += (rawCompute + rawStorage)
                }
            }
            
            // Format costs with currency symbol
            def formattedCostByProcess = [:]
            costByProcess.each { proc, entry ->
                formattedCostByProcess[proc] = [
                    count: entry.count,
                    compute_cost: "${symbol}${String.format('%.4f', entry.compute_cost)}",
                    projected_monthly_storage_cost: "${symbol}${String.format('%.4f', entry.projected_monthly_storage_cost)}",
                    total_estimated_cost: "${symbol}${String.format('%.4f', entry.total_estimated_cost)}",
                    raw_compute_cost: entry.compute_cost,
                    raw_storage_cost: entry.projected_monthly_storage_cost,
                    raw_total_estimated_cost: entry.total_estimated_cost
                ]
            }

            map << [
                summary: [
                    total_tasks: tasksByStatus.values().flatten().size(),
                    completed: tasksByStatus['COMPLETED'].size(),
                    cached: tasksByStatus['CACHED'].size(),
                    failed: tasksByStatus['FAILED'].size(),
                    aborted: tasksByStatus['ABORTED'].size(),
                    retried: tasksByStatus['RETRIED'].size(),
                    failure_ignored: tasksByStatus['IGNORED'].size(),
                    has_head_job_cost: headJobCost > 0.0,
                    tasks_compute_cost: "${symbol}${String.format('%.4f', totalCompute)}",
                    head_job_cost: "${symbol}${String.format('%.4f', headJobCost)}",
                    total_compute_cost: "${symbol}${String.format('%.4f', finalCompute)}",
                    projected_monthly_storage_cost: "${symbol}${String.format('%.4f', totalStorage)}",
                    total_estimated_cost: "${symbol}${String.format('%.4f', finalCompute + totalStorage)}",
                    pricing_rates: [
                        currency: prices.currency ?: costConfig.currency ?: 'EUR',
                        kCPUHr: kCPUHr,
                        kGBHr: kGBHr,
                        kGPUGBHr: kGPUGBHr,
                        TBMonth: tbMonthRate
                    ]
                ],
                costs_by_process: formattedCostByProcess,
                tasks_by_status: tasksByStatus
            ]
        }
        return map
    }

    private void writeJsonReport() {
        def json = new JsonBuilder(toMap())
        writeToFile(json.toPrettyString(), 'json')
    }

    private void writeHtmlReport() {
        def engine = new GStringTemplateEngine()
        def template = null
        if (htmlTemplatePath) {
            def templateFile = new File(htmlTemplatePath)
            if (!templateFile.exists()) {
                log.error("HTML template file not found at: ${htmlTemplatePath}")
                return
            }
            template = engine.createTemplate(templateFile)
        } else {
            def defaultTemplateStream = this.class.getResourceAsStream('/roche_csi/plugin/task-status-report-template.html')
            template = engine.createTemplate(new InputStreamReader(defaultTemplateStream))
        }
        def htmlContent = template.make(toMap()).toString()
        writeToFile(htmlContent, 'html')
    }

    private void writeTsvReport() {
        def tsvContent = new StringWriter()
        def headerKeys = fields ?: tasks.collectMany { it.keySet() }.unique()
        tsvContent << "${headerKeys.join('\t')}\n"
        // Write event data
        tasks.each { task ->
            def row = headerKeys.collect { key -> task[key] ?: '' }
            tsvContent << "${row.join('\t')}\n"
        }
        writeToFile(tsvContent.toString(), 'tsv')
    }

    private void writeReport() {
        synchronized(tasksByStatus) {
            if (format.contains('json')) {
                writeJsonReport()
            }
            if (format.contains('html')) {
                writeHtmlReport()
            }
            if (format.contains('tsv')) {
                writeTsvReport()
            }
        }
    }

    String getCurrencySymbol() {
        def costConfig = session.config.navigate('nfreport.costs') as Map ?: [:]
        def currency = costConfig.currency ?: 'EUR'

        def priceAPI = costConfig.priceAPI ?: null
        def priceJsonPath = costConfig.priceJsonPath ?: null
        def prices = [:]
        if (priceAPI) {
            try {
                prices = fetchPriceAPI(priceAPI)
            } catch (Exception e) {
                // ignore
            }
        } else if (priceJsonPath) {
            try {
                prices = parsePriceJson(priceJsonPath)
            } catch (Exception e) {
                // ignore
            }
        }

        if (prices?.currency) {
            currency = prices.currency
        }

        switch (currency.toString().toUpperCase()) {
            case 'EUR': return '€'
            case 'USD': return '$'
            case 'GBP': return '£'
            default: return "${currency} "
        }
    }

    private Map parsePriceJson(String path) {
        if (!path) return [:]
        def file = new File(path)
        if (!file.exists()) return [:]
        def text = file.text
        def cleanText = text.replaceAll(/(?m)\/\/.*$/, "").replaceAll(/(?m)#.*$/, "")
        return new groovy.json.JsonSlurper().parseText(cleanText) as Map
    }

    private Map fetchPriceAPI(String urlStr) {
        if (!urlStr) return [:]
        def conn = new URL(urlStr).openConnection()
        conn.setConnectTimeout(5000)
        conn.setReadTimeout(5000)
        def text = conn.getInputStream().getText("UTF-8")
        def cleanText = text.replaceAll(/(?m)\/\/.*$/, "").replaceAll(/(?m)#.*$/, "")
        return new groovy.json.JsonSlurper().parseText(cleanText) as Map
    }

    private Map parseLaunchScript(String path) {
        def res = [cpus: 0, memoryGb: 0.0, gpus: 0]
        if (!path) return res
        def file = new File(path)
        if (!file.exists()) {
            log.warn("Launch script not found at: ${path}")
            return res
        }
        
        boolean hasSlurm = false
        boolean hasSge = false

        file.eachLine { line ->
            line = line.trim()
            if (line.startsWith('#SBATCH')) {
                hasSlurm = true
                def cpuMatcher = line =~ /(?:--cpus-per-task|-c)[=\s]+(\d+)/
                if (cpuMatcher.find()) {
                    res.cpus = cpuMatcher.group(1) as int
                }
                
                def memMatcher = line =~ /(?:--mem|--mem-per-cpu)[=\s]+(\d+)([KMGTPkmgtp]?)/
                if (memMatcher.find()) {
                    double val = memMatcher.group(1) as double
                    String unit = memMatcher.group(2)?.toUpperCase() ?: 'M'
                    if (unit == 'K') res.memoryGb = val / (1024.0 * 1024.0)
                    else if (unit == 'M') res.memoryGb = val / 1024.0
                    else if (unit == 'G') res.memoryGb = val
                    else if (unit == 'T') res.memoryGb = val * 1024.0
                    else if (unit == 'P') res.memoryGb = val * 1024.0 * 1024.0
                }
                
                def gpuMatcher = line =~ /(?:--gpus|gpu:)(\d+)/
                if (gpuMatcher.find()) {
                    res.gpus = gpuMatcher.group(1) as int
                }
            } else if (line.startsWith('#$')) {
                hasSge = true
                def cpuMatcher = line =~ /-pe\s+\S+\s+(\d+)/
                if (cpuMatcher.find()) {
                    res.cpus = cpuMatcher.group(1) as int
                }
                
                def memMatcher = line =~ /-l\s+(?:h_vmem|m_mem_free)[=\s]+(\d+)([KMGTPkmgtp]?)/
                if (memMatcher.find()) {
                    double val = memMatcher.group(1) as double
                    String unit = memMatcher.group(2)?.toUpperCase() ?: 'M'
                    if (unit == 'K') res.memoryGb = val / (1024.0 * 1024.0)
                    else if (unit == 'M') res.memoryGb = val / 1024.0
                    else if (unit == 'G') res.memoryGb = val
                    else if (unit == 'T') res.memoryGb = val * 1024.0
                    else if (unit == 'P') res.memoryGb = val * 1024.0 * 1024.0
                }
            }
        }
        
        if (hasSlurm && res.cpus == 0) res.cpus = 1
        if (hasSge && res.cpus == 0) res.cpus = 1

        return res
    }

}

#!/usr/bin/env nextflow

/*
 * Validation pipeline for nf-report plugin
 */

process TEST_PROCESS_1 {
    tag "${meta.sample_name}"

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_1_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_1 with sample_name: ${meta.sample_name}" > test_process_1_output.txt
    """
}

process TEST_PROCESS_2 {
    tag "${meta.sample_name}"

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_2_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_2 with sample_name: ${meta.sample_name}" > test_process_2_output.txt
    """
}

process TEST_PROCESS_FAIL_1 {
    tag "${meta.sample_name}"
    errorStrategy 'ignore'

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_fail_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_FAIL with sample_name: ${meta.sample_name}" > test_process_fail_output.txt
    ${meta.no_fail ? 'exit 0' : 'exit 1'}
    """
}

process TEST_PROCESS_FAIL_2 {
    tag "${meta.sample_name}"
    errorStrategy 'ignore'

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_fail_2_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_FAIL_2 with sample_name: ${meta.sample_name}" > test_process_fail_2_output.txt
    ${meta.no_fail ? 'exit 0' : 'exit 1'}
    """
}

process TEST_PROCESS_SUCCEED_UPON_RETRY_1 {
    tag "${meta.sample_name}"
    errorStrategy 'retry'

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_succeed_upon_retry_1_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_SUCCEED_UPON_RETRY_1 with sample_name: ${meta.sample_name}" > test_process_succeed_upon_retry_1_output.txt
    ${task.attempt > 1 ? 'exit 0' : 'exit 1'}
    """
}

process TEST_PROCESS_SUCCEED_UPON_RETRY_2 {
    tag "${meta.sample_name}"
    errorStrategy 'retry'

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_succeed_upon_retry_2_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_SUCCEED_UPON_RETRY_2 with sample_name: ${meta.sample_name}" > test_process_succeed_upon_retry_2_output.txt
    ${task.attempt > 1 ? 'exit 0' : 'exit 1'}
    """
}

process TEST_PROCESS_MULTI_SAMPLE {
    tag "${meta.sample_name}"

    input:
    tuple val(meta), val(dummy)

    output:
    path "test_process_multi_sample_output.txt"

    script:
    """
    echo "Running TEST_PROCESS_MULTI_SAMPLE with sample_name: ${meta.sample_name}" > test_process_multi_sample_output.txt
    """
}

workflow {
    samples = channel.of(
        'sample1',
        'sample2',
        'sample3'
    ).map { sample_name ->
        [[sample_name: sample_name, no_fail: false], 1]
    }
    if (params.include_samples_that_never_fail) {
        samples = samples.concat(channel.of('sample4', 'sample5').map { sample_name ->
            [[sample_name: sample_name, no_fail: true], 1]
        })
    }
    if (params.run_processes_always_fail) {
        TEST_PROCESS_FAIL_1(samples)
        TEST_PROCESS_FAIL_2(samples)
    }
    if (params.run_processes_always_succeed) {
        TEST_PROCESS_1(samples)
        TEST_PROCESS_2(samples)
    }
    if (params.run_processes_always_succeed_upon_retry) {
        TEST_PROCESS_SUCCEED_UPON_RETRY_1(samples)
        TEST_PROCESS_SUCCEED_UPON_RETRY_2(samples)
    }
    if (params.run_processes_multi_sample) {
        // Each task is attributed to multiple samples via a Collection tag
        multi_samples = channel.of(
            [['sample1', 'sample2'], 1],
            [['sample3'], 1]
        ).map { sample_names, dummy ->
            [[sample_name: sample_names, no_fail: false], dummy]
        }
        TEST_PROCESS_MULTI_SAMPLE(multi_samples)
    }
}

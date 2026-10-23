package com.aioj.next.executor.domain;

import com.aioj.next.executor.config.AgentExecutorProperties;
import com.aioj.next.sandbox.client.Command;
import com.aioj.next.sandbox.client.GoJudgeClient;
import com.aioj.next.sandbox.client.LanguageProfile;
import com.aioj.next.sandbox.client.Limits;
import com.aioj.next.sandbox.client.ResultCode;
import com.aioj.next.sandbox.client.RunResult;
import com.aioj.next.sandbox.client.SandboxConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * The execution endpoints' side of go-judge: compile and run single
 * programs, and run the generator scripts that collect draft testcases. The
 * protocol, language table and limit rules come from sandbox-client. This is
 * judge-worker's class, copied when agent-executor was split off (plan T1.8);
 * ExecutionRequestCharacterizationTest pins that both send the same requests,
 * and T1.10 removes judge-worker's copy.
 */
@Component
public class SandboxExecutionClient {
    private static final int DEFAULT_SCRIPT_TARGET_CASE_COUNT = 3;
    private static final int MAX_SCRIPT_TARGET_CASE_COUNT = 20;
    private static final String COLLECT_MODE_PAIRED = "PAIRED";
    private static final String COLLECT_MODE_OFFICIAL_INPUTS = "OFFICIAL_INPUTS";
    private static final String COLLECT_MODE_OFFICIAL_PACKAGE = "OFFICIAL_PACKAGE";
    private static final String GENERATED_FILES_JSON = ".aioj_generated_files.json";
    private static final String OFFICIAL_PACKAGE_JSON = ".aioj_official_package.json";
    private static final String OFFICIAL_PACKAGE_ZIP = ".aioj_official_hidden.zip";

    private final AgentExecutorProperties properties;
    private final GoJudgeClient sandbox;

    public SandboxExecutionClient(AgentExecutorProperties properties) {
        this.properties = properties;
        this.sandbox = new GoJudgeClient(sandboxConfig(properties));
    }

    /** go-judge connection settings: the agent sandbox, never the judge sandbox. */
    static SandboxConfig sandboxConfig(AgentExecutorProperties properties) {
        return new SandboxConfig(properties.getSandboxEndpoint(), properties.getSandboxToken(),
                properties.getSandboxTimeout(), properties.getSandboxReadTimeout());
    }

    public CompileOutcome compileSource(String language, String sourceCode, long cpuLimitNs, long memoryLimitBytes) {
        LanguageProfile lang = LanguageProfile.of(language);
        if (!lang.requiresCompile()) {
            return CompileOutcome.success(null, 0L, 0L, null, 0, 0L, 0);
        }
        RunResult result = sandbox.run(Command.builder(lang.compileArgs())
                .env(lang.envVars())
                .standardFiles("", stdoutBaseCollectLimitBytes(), stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50))
                .copyInContent(lang.sourceFileName(), sourceCode == null ? "" : sourceCode)
                .copyOutCached(List.of(lang.executableName()))
                .build());
        Long timeMs = Limits.nanosToMillis(result.time());
        Long memoryKb = Limits.bytesToKb(result.memory());
        Long runTimeMs = Limits.nanosToMillis(result.runTime());
        String stderr = result.file("stderr");
        if (!result.accepted()) {
            return CompileOutcome.failed(firstText(stderr, result.error(), result.status()), timeMs, memoryKb,
                    stderr, result.exitStatus(), runTimeMs, result.procPeak()).withSandboxStatus(result.status());
        }
        String fileId = result.cachedFileId(lang.executableName());
        if (!StringUtils.hasText(fileId)) {
            return CompileOutcome.failed("Compile succeeded but sandbox did not return cached fileId",
                    timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs, result.procPeak())
                    .withSandboxStatus("Internal Error");
        }
        return CompileOutcome.success(fileId, timeMs, memoryKb, stderr, result.exitStatus(), runTimeMs,
                result.procPeak()).withSandboxStatus(result.status());
    }

    public RunOutcome runSource(String language, String sourceCode, String compiledFileId, String stdin,
                                long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes) {
        LanguageProfile lang = LanguageProfile.of(language);
        String temporaryFileId = null;
        try {
            String executableFileId = compiledFileId;
            if (lang.requiresCompile() && !StringUtils.hasText(executableFileId)) {
                CompileOutcome compile = compileSource(language, sourceCode, Limits.safeMultiply(cpuLimitNs, 10),
                        Limits.safeMultiply(memoryLimitBytes, 2));
                if (compile.failed()) {
                    return RunOutcome.fromCompileFailure(compile);
                }
                executableFileId = compile.fileId();
                temporaryFileId = executableFileId;
            }
            Command.Builder command = Command.builder(lang.runArgs())
                    .env(lang.envVars())
                    .standardFiles(stdin == null ? "" : stdin,
                            Limits.positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                            stderrCollectLimitBytes())
                    .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50));
            if (lang.requiresCompile()) {
                command.copyInCached(lang.executableName(), executableFileId);
            } else {
                command.copyInContent(lang.sourceFileName(), sourceCode == null ? "" : sourceCode);
            }
            return RunOutcome.from(sandbox.run(command.build()));
        } finally {
            if (temporaryFileId != null) {
                deleteCachedFile(temporaryFileId);
            }
        }
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes) {
        return runPythonScript(script, cpuLimitNs, memoryLimitBytes, stdoutCollectLimitBytes,
                DEFAULT_SCRIPT_TARGET_CASE_COUNT);
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                      int targetCaseCount) {
        return runPythonScript(script, cpuLimitNs, memoryLimitBytes, stdoutCollectLimitBytes, targetCaseCount,
                COLLECT_MODE_PAIRED);
    }

    public RunOutcome runPythonScript(String script, long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                      int targetCaseCount, String collectMode) {
        String collector = generatedFilesCollector(safeTargetCaseCount(targetCaseCount),
                safeCollectMode(collectMode),
                Math.max(1L, properties.getMaxDraftPackageEntryBytes()),
                Math.max(1L, properties.getMaxDraftPackageBytes()),
                Math.max(1, properties.getMaxDraftPackageEntries()));
        RunResult result = sandbox.run(Command.builder(List.of("/bin/bash", "-lc", collector))
                .env(List.of("PATH=/usr/bin:/bin"))
                .standardFiles("", Limits.positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                        stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(50))
                .copyInContent("generator.py", script == null ? "" : script)
                .copyOut(List.of(GENERATED_FILES_JSON))
                .copyOutMax(Math.max(1L, properties.getMaxDraftPackageBytes()))
                .build());
        return RunOutcome.from(result, result.file(GENERATED_FILES_JSON));
    }

    public RunOutcome runOfficialPackageScript(String script, String standardSolutionLanguage, String standardSolutionCode,
                                               long cpuLimitNs, long memoryLimitBytes, int stdoutCollectLimitBytes,
                                               int targetCaseCount, long standardCpuLimitNs, long standardMemoryLimitBytes,
                                               long maxCaseBytes, long maxPackageBytes) {
        LanguageProfile lang = LanguageProfile.of(standardSolutionLanguage);
        Long standardTimeLimitMillis = Limits.nanosToMillis(standardCpuLimitNs);
        String collector = officialPackageCollector(
                safeTargetCaseCount(targetCaseCount),
                standardTimeLimitMillis == null ? 1000L : Math.max(1L, standardTimeLimitMillis),
                Math.max(1L, standardMemoryLimitBytes),
                Math.max(1L, maxCaseBytes),
                Math.max(1L, maxPackageBytes),
                lang
        );
        RunResult result = sandbox.run(Command.builder(List.of("/bin/bash", "-lc", collector))
                .env(List.of("PATH=/usr/bin:/bin"))
                .standardFiles("", Limits.positiveOrDefault(stdoutCollectLimitBytes, stdoutBaseCollectLimitBytes()),
                        stderrCollectLimitBytes())
                .limits(cpuLimitNs, Limits.wallClockLimit(cpuLimitNs), memoryLimitBytes, processLimit(80))
                .copyInContent("generator.py", script == null ? "" : script)
                .copyInContent(lang.sourceFileName(), standardSolutionCode == null ? "" : standardSolutionCode)
                .copyOut(List.of(OFFICIAL_PACKAGE_JSON))
                .copyOutCached(List.of(OFFICIAL_PACKAGE_ZIP))
                .build());
        return RunOutcome.fromOfficialPackage(result, result.file(OFFICIAL_PACKAGE_JSON));
    }

    static String generatedFilesCollector(int targetCaseCount) {
        return generatedFilesCollector(targetCaseCount, COLLECT_MODE_PAIRED);
    }

    static String generatedFilesCollector(int targetCaseCount, String collectMode) {
        return generatedFilesCollector(targetCaseCount, collectMode,
                8L * 1024L * 1024L, 50L * 1024L * 1024L, 500);
    }

    static String generatedFilesCollector(int targetCaseCount, String collectMode,
                                          long maxEntryBytes, long maxCollectedBytes, int maxEntryCount) {
        return """
                python3 generator.py
                status=$?
                if [ $status -ne 0 ]; then exit $status; fi
                python3 - <<'PY'
                import json
                from pathlib import Path
                target_case_count = %d
                collect_mode = '%s'
                output_json = Path('%s')
                max_entry_bytes = %d
                max_collected_bytes = %d
                max_entry_count = %d
                collected_bytes = 0

                def bounded_text(path):
                    global collected_bytes
                    size = path.stat().st_size
                    if size > max_entry_bytes:
                        raise RuntimeError(f'generated testcase file exceeds limit: {path.name}')
                    if collected_bytes + size > max_collected_bytes:
                        raise RuntimeError('generated testcase collection exceeds total size limit')
                    collected_bytes += size
                    return path.read_text(encoding='utf-8', errors='replace')

                def candidate_files(root, relative_to_root):
                    items = []
                    if not root.exists():
                        return items
                    for path in sorted(root.rglob('*')):
                        if not path.is_file() or path.suffix not in ('.in', '.out'):
                            continue
                        if len(items) >= max_entry_count:
                            raise RuntimeError('generated testcase collection has too many entries')
                        name = path.name
                        if name.startswith('.aioj_') or name in ('generator.py', 'std.cpp', 'std', 'std.exe', 'main', 'main.cpp'):
                            continue
                        relative = path.as_posix() if relative_to_root == Path('.') else path.relative_to(relative_to_root).as_posix()
                        key = relative[:-len(path.suffix)]
                        items.append((key, path.suffix, relative, path))
                    return items

                def collect(root, relative_to_root):
                    parts = {}
                    input_count = 0
                    output_count = 0
                    for key, suffix, relative, path in candidate_files(root, relative_to_root):
                        if suffix == '.in':
                            input_count += 1
                        elif suffix == '.out':
                            output_count += 1
                        parts.setdefault(key, {})[suffix] = (relative, path)
                    pair_names = sorted(name for name, value in parts.items() if '.in' in value and '.out' in value)
                    input_names = sorted(name for name, value in parts.items() if '.in' in value)
                    return parts, pair_names, input_names, input_count, output_count

                parts, pair_names, input_names, input_count, output_count = collect(Path('testcases'), Path('testcases'))
                scan_root = 'testcases'
                if (collect_mode == 'OFFICIAL_INPUTS' and not input_names) or (collect_mode != 'OFFICIAL_INPUTS' and not pair_names):
                    parts, pair_names, input_names, input_count, output_count = collect(Path('.'), Path('.'))
                    scan_root = '.'

                def is_stress_case(name):
                    return Path(name).stem.startswith('stress_small_')

                files = {}
                if collect_mode == 'OFFICIAL_INPUTS':
                    official_inputs = [name for name in input_names if not is_stress_case(name)]
                    for name in official_inputs[:target_case_count]:
                        for suffix in ('.in', '.out'):
                            if suffix not in parts[name]:
                                continue
                            relative, path = parts[name][suffix]
                            files[relative] = bounded_text(path)
                else:
                    for name in pair_names[:target_case_count]:
                        for suffix in ('.in', '.out'):
                            relative, path = parts[name][suffix]
                            files[relative] = bounded_text(path)

                cross_check_inputs = {}
                for name in sorted(parts):
                    value = parts[name]
                    if '.in' not in value:
                        continue
                    relative, path = value['.in']
                    if Path(relative).stem.startswith('stress_small_'):
                        cross_check_inputs[relative] = bounded_text(path)
                    if len(cross_check_inputs) >= 30:
                        break

                manifest_json = ''
                for manifest_path in (Path('testcases') / 'manifest.json', Path('manifest.json')):
                    if manifest_path.exists() and manifest_path.is_file():
                        manifest_json = manifest_path.read_text(encoding='utf-8', errors='replace')[:20000]
                        break

                output_json.write_text(
                    json.dumps({
                        'files': files,
                        'generatedPairCount': len(pair_names),
                        'generatedFileCount': input_count + output_count,
                        'generatedInputCount': input_count,
                        'generatedOutputCount': output_count,
                        'crossCheckInputs': cross_check_inputs,
                        'manifestJson': manifest_json,
                        'scanRoot': scan_root
                    }, ensure_ascii=False),
                    encoding='utf-8'
                )
                PY
                """.formatted(targetCaseCount, safeCollectMode(collectMode), GENERATED_FILES_JSON,
                Math.max(1L, maxEntryBytes), Math.max(1L, maxCollectedBytes), Math.max(1, maxEntryCount));
    }

    static String officialPackageCollector(int targetCaseCount, long standardTimeLimitMillis,
                                           long standardMemoryLimitBytes, long maxCaseBytes,
                                           long maxPackageBytes, LanguageProfile lang) {
        return """
                python3 - <<'PY'
                import hashlib
                import json
                import os
                import shutil
                import subprocess
                import sys
                import time
                import zipfile
                from pathlib import Path
                try:
                    import resource
                except Exception:
                    resource = None

                TARGET_CASE_COUNT = %d
                STANDARD_TIME_LIMIT_SECONDS = max(1, int((%d + 999) // 1000))
                STANDARD_MEMORY_LIMIT_BYTES = %d
                MAX_CASE_BYTES = %d
                MAX_PACKAGE_BYTES = %d
                OUTPUT_JSON = Path('%s')
                OUTPUT_ZIP = Path('%s')
                SOURCE_FILE = %s
                COMPILE_ARGS = %s
                RUN_ARGS = %s
                HAS_COMPILE = %s

                def sha256_file(path):
                    h = hashlib.sha256()
                    with path.open('rb') as f:
                        for chunk in iter(lambda: f.read(1024 * 1024), b''):
                            h.update(chunk)
                    return h.hexdigest()

                def write_package(status, error_code=None, error_message=None, cases=None, scan_root=None, manifest_json='', package_path=None, generated_input_count=0, generated_output_count=0):
                    cases = cases or []
                    total_input = sum(c.get('inputBytes', 0) for c in cases)
                    total_output = sum(c.get('outputBytes', 0) for c in cases)
                    largest_case = 0
                    for c in cases:
                        largest_case = max(largest_case, c.get('inputBytes', 0), c.get('outputBytes', 0))
                    payload = {
                        'status': status,
                        'errorCode': error_code,
                        'errorMessage': error_message,
                        'caseCount': len(cases),
                        'generatedInputCount': generated_input_count,
                        'generatedOutputCount': generated_output_count,
                        'totalInputBytes': total_input,
                        'totalOutputBytes': total_output,
                        'totalBytes': total_input + total_output,
                        'largestCaseBytes': largest_case,
                        'manifestJson': manifest_json,
                        'scanRoot': scan_root,
                        'cases': cases,
                        'packageFileName': OUTPUT_ZIP.name,
                    }
                    if package_path and package_path.exists():
                        payload['packageFileSizeBytes'] = package_path.stat().st_size
                        payload['packageSha256'] = sha256_file(package_path)
                    OUTPUT_JSON.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')

                def ensure_tiny_zip():
                    with zipfile.ZipFile(OUTPUT_ZIP, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
                        zf.writestr('README.txt', 'AI-OJ official package was not materialized. See .aioj_official_package.json.\\n')

                def candidate_inputs(root, relative_to_root):
                    items = []
                    if not root.exists():
                        return items
                    for path in sorted(root.rglob('*.in')):
                        if not path.is_file():
                            continue
                        name = path.name
                        if name.startswith('.aioj_') or Path(name).stem.startswith('stress_small_'):
                            continue
                        relative = path.as_posix() if relative_to_root == Path('.') else path.relative_to(relative_to_root).as_posix()
                        key = relative[:-3]
                        items.append((key, relative, path))
                    return items

                def count_generated(root, relative_to_root):
                    input_count = 0
                    output_count = 0
                    if not root.exists():
                        return 0, 0
                    for path in root.rglob('*'):
                        if not path.is_file():
                            continue
                        if path.suffix == '.in':
                            input_count += 1
                        elif path.suffix == '.out':
                            output_count += 1
                    return input_count, output_count

                def run_generator():
                    return subprocess.run([sys.executable, 'generator.py'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

                def compile_standard():
                    if not HAS_COMPILE:
                        return None
                    return subprocess.run(COMPILE_ARGS, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)

                def child_memory_kb():
                    if resource is None:
                        return None
                    try:
                        value = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss
                        if sys.platform == 'darwin':
                            value = (value + 1023) // 1024
                        return int(value)
                    except Exception:
                        return None

                def run_standard(input_path, output_path):
                    started = time.perf_counter()
                    try:
                        with input_path.open('rb') as fin, output_path.open('wb') as fout:
                            result = subprocess.run(RUN_ARGS, stdin=fin, stdout=fout, stderr=subprocess.PIPE,
                                                    timeout=STANDARD_TIME_LIMIT_SECONDS)
                            elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
                            return result, elapsed_ms, child_memory_kb()
                    except subprocess.TimeoutExpired as ex:
                        elapsed_ms = max(0, int((time.perf_counter() - started) * 1000))
                        return ex, elapsed_ms, child_memory_kb()

                def safe_entry_name(index, suffix):
                    return f'testcases/{index:03d}{suffix}'

                def manifest_for(cases):
                    return {
                        'version': 'ai-draft-hidden-' + time.strftime('%%Y%%m%%d%%H%%M%%S'),
                        'checker': {'type': 'STANDARD'},
                        'cases': [
                            {
                                'name': c['name'],
                                'input': c['inputPath'],
                                'output': c['outputPath'],
                                'sample': False
                            }
                            for c in cases
                        ]
                    }

                ensure_tiny_zip()
                generator_result = run_generator()
                if generator_result.returncode != 0:
                    detail = (generator_result.stderr or generator_result.stdout or 'generator.py failed')[:2000]
                    write_package('FAILED', 'GENERATOR_PYTHON_FAILED', detail)
                    sys.stderr.write(detail)
                    sys.exit(generator_result.returncode)

                inputs = candidate_inputs(Path('testcases'), Path('testcases'))
                scan_root = 'testcases'
                generated_input_count, generated_output_count = count_generated(Path('testcases'), Path('testcases'))
                if not inputs:
                    inputs = candidate_inputs(Path('.'), Path('.'))
                    scan_root = '.'
                    generated_input_count, generated_output_count = count_generated(Path('.'), Path('.'))

                if len(inputs) < TARGET_CASE_COUNT:
                    write_package('FAILED', 'GENERATOR_MISSING_INPUTS',
                                  f'testcaseGeneratorPython generated {len(inputs)} official .in cases, expected at least {TARGET_CASE_COUNT}',
                                  scan_root=scan_root, generated_input_count=generated_input_count,
                                  generated_output_count=generated_output_count)
                    sys.exit(0)

                compile_result = compile_standard()
                if compile_result is not None and compile_result.returncode != 0:
                    detail = (compile_result.stderr or compile_result.stdout or 'standard solution compile failed')[:2000]
                    write_package('FAILED', 'STANDARD_COMPILE_FAILED', detail, scan_root=scan_root,
                                  generated_input_count=generated_input_count, generated_output_count=generated_output_count)
                    sys.exit(0)

                package_root = Path('.aioj_official_package')
                if package_root.exists():
                    shutil.rmtree(package_root)
                (package_root / 'testcases').mkdir(parents=True, exist_ok=True)

                cases = []
                total_bytes = 0
                for index, (key, relative, input_path) in enumerate(inputs[:TARGET_CASE_COUNT], 1):
                    input_size = input_path.stat().st_size
                    if input_size > MAX_CASE_BYTES:
                        write_package('FAILED', 'GENERATOR_CASE_TOO_LARGE',
                                      f'{relative} size {input_size} exceeds per-file limit {MAX_CASE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    out_path = package_root / safe_entry_name(index, '.out')
                    in_path = package_root / safe_entry_name(index, '.in')
                    shutil.copyfile(input_path, in_path)
                    run_result, run_time_ms, run_memory_kb = run_standard(in_path, out_path)
                    if isinstance(run_result, subprocess.TimeoutExpired):
                        write_package('FAILED', 'STANDARD_TLE_ON_GENERATED_CASE',
                                      f'standardSolutionCode exceeded time limit on {relative}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    if run_result.returncode != 0:
                        detail = (run_result.stderr.decode('utf-8', errors='replace') if isinstance(run_result.stderr, bytes) else (run_result.stderr or 'standard solution runtime error'))[:2000]
                        write_package('FAILED', 'STANDARD_RUNTIME_ON_GENERATED_CASE',
                                      f'{relative}: {detail}', cases=cases, scan_root=scan_root,
                                      generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    output_size = out_path.stat().st_size if out_path.exists() else 0
                    if not out_path.exists():
                        write_package('FAILED', 'STANDARD_OUTPUT_MATERIALIZATION_FAILED',
                                      f'standardSolutionCode produced no output file for {relative}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    if output_size > MAX_CASE_BYTES:
                        write_package('FAILED', 'GENERATOR_CASE_TOO_LARGE',
                                      f'{safe_entry_name(index, ".out")} size {output_size} exceeds per-file limit {MAX_CASE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    total_bytes += input_size + output_size
                    if total_bytes > MAX_PACKAGE_BYTES:
                        write_package('FAILED', 'GENERATOR_PACKAGE_TOO_LARGE',
                                      f'official hidden testcase package size {total_bytes} exceeds limit {MAX_PACKAGE_BYTES}',
                                      cases=cases, scan_root=scan_root, generated_input_count=generated_input_count,
                                      generated_output_count=generated_output_count)
                        sys.exit(0)
                    cases.append({
                        'name': f'{index:03d}',
                        'sourceInputPath': relative,
                        'inputPath': safe_entry_name(index, '.in'),
                        'outputPath': safe_entry_name(index, '.out'),
                        'inputBytes': input_size,
                        'outputBytes': output_size,
                        'inputSha256': sha256_file(in_path),
                        'outputSha256': sha256_file(out_path),
                        'status': 'ACCEPTED',
                        'timeMillis': run_time_ms,
                        'memoryKb': run_memory_kb,
                        'message': 'Accepted'
                    })

                manifest_json = json.dumps(manifest_for(cases), ensure_ascii=False, separators=(',', ':'))
                (package_root / 'manifest.json').write_text(manifest_json, encoding='utf-8')
                with zipfile.ZipFile(OUTPUT_ZIP, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
                    zf.write(package_root / 'manifest.json', 'manifest.json')
                    for c in cases:
                        zf.write(package_root / c['inputPath'], c['inputPath'])
                        zf.write(package_root / c['outputPath'], c['outputPath'])
                write_package('PASSED', cases=cases, scan_root=scan_root, manifest_json=manifest_json,
                              package_path=OUTPUT_ZIP, generated_input_count=generated_input_count,
                              generated_output_count=generated_output_count)
                PY
                """.formatted(
                targetCaseCount,
                standardTimeLimitMillis,
                standardMemoryLimitBytes,
                maxCaseBytes,
                maxPackageBytes,
                OFFICIAL_PACKAGE_JSON,
                OFFICIAL_PACKAGE_ZIP,
                pythonString(lang.sourceFileName()),
                pythonList(lang.compileArgs()),
                pythonList(lang.runArgs()),
                lang.requiresCompile() ? "True" : "False"
        );
    }

    public void deleteCachedFile(String fileId) {
        sandbox.deleteFile(fileId);
    }

    public void downloadCachedFile(String fileId, OutputStream outputStream) {
        sandbox.downloadFile(fileId, outputStream);
    }

    public static long millisToNanos(long millis) {
        return Limits.millisToNanos(millis);
    }

    public static long kbToBytes(long kb) {
        return Limits.kbToBytes(kb);
    }

    static int stdoutBaseCollectLimitBytes(int configuredLimit) {
        return Limits.stdoutBaseCollectLimitBytes(configuredLimit);
    }

    static int stderrCollectLimitBytes(int configuredLimit) {
        return Limits.stderrCollectLimitBytes(configuredLimit);
    }

    private int stdoutBaseCollectLimitBytes() {
        return stdoutBaseCollectLimitBytes(properties.getStdoutBaseCollectLimitBytes());
    }

    private int stderrCollectLimitBytes() {
        return stderrCollectLimitBytes(properties.getStderrCollectLimitBytes());
    }

    private int processLimit(int requested) {
        return Limits.processLimit(requested, properties.getMaxProcessLimit());
    }

    private static int safeTargetCaseCount(int targetCaseCount) {
        if (targetCaseCount <= 0) {
            return DEFAULT_SCRIPT_TARGET_CASE_COUNT;
        }
        return Math.min(MAX_SCRIPT_TARGET_CASE_COUNT, targetCaseCount);
    }

    private static String safeCollectMode(String collectMode) {
        if (COLLECT_MODE_OFFICIAL_INPUTS.equals(collectMode)) {
            return COLLECT_MODE_OFFICIAL_INPUTS;
        }
        if (COLLECT_MODE_OFFICIAL_PACKAGE.equals(collectMode)) {
            return COLLECT_MODE_OFFICIAL_PACKAGE;
        }
        return COLLECT_MODE_PAIRED;
    }

    private static String pythonString(String value) {
        String escaped = (value == null ? "" : value)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
        return "'" + escaped + "'";
    }

    private static String pythonList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return "[" + values.stream().map(SandboxExecutionClient::pythonString).reduce((a, b) -> a + ", " + b).orElse("") + "]";
    }

    private static String firstText(String... values) {
        if (values != null) {
            for (String value : values) {
                if (StringUtils.hasText(value)) {
                    return value;
                }
            }
        }
        return "Sandbox execution failed";
    }

    public record CompileOutcome(boolean failed,
                                 String fileId,
                                 String message,
                                 Long timeMillis,
                                 Long memoryKb,
                                 String stderr,
                                 Integer exitStatus,
                                 Long runTimeMillis,
                                 Integer procPeak,
                                 String sandboxStatus) {
        public CompileOutcome(boolean failed, String fileId, String message, Long timeMillis, Long memoryKb,
                              String stderr, Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            this(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus, runTimeMillis, procPeak, null);
        }

        public CompileOutcome(boolean failed, String fileId, String message, Long timeMillis, Long memoryKb,
                              String stderr, Integer exitStatus, Long runTimeMillis) {
            this(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus, runTimeMillis, null);
        }

        CompileOutcome withSandboxStatus(String status) {
            return new CompileOutcome(failed, fileId, message, timeMillis, memoryKb, stderr, exitStatus,
                    runTimeMillis, procPeak, status);
        }

        static CompileOutcome success(String fileId, Long timeMillis, Long memoryKb, String stderr,
                                      Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            return new CompileOutcome(false, fileId, "Accepted", timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, procPeak);
        }

        static CompileOutcome failed(String message, Long timeMillis, Long memoryKb, String stderr,
                                     Integer exitStatus, Long runTimeMillis, Integer procPeak) {
            return new CompileOutcome(true, null, message, timeMillis, memoryKb, stderr,
                    exitStatus, runTimeMillis, procPeak);
        }
    }

    public record RunOutcome(String status,
                             String message,
                             Long timeMillis,
                             Long memoryKb,
                             String stdout,
                             String stderr,
                             Integer exitStatus,
                             Long runTimeMillis,
                             String generatedFilesJson,
                             String officialPackageJson,
                             Map<String, String> fileIds,
                             String resultCode,
                             Integer procPeak) {
        public RunOutcome(String status, String message, Long timeMillis, Long memoryKb, String stdout,
                          String stderr, Integer exitStatus, Long runTimeMillis, String generatedFilesJson) {
            this(status, message, timeMillis, memoryKb, stdout, stderr, exitStatus, runTimeMillis,
                    generatedFilesJson, null, Map.of(), normalizeResultCode(status), null);
        }

        public RunOutcome(String status, String message, Long timeMillis, Long memoryKb, String stdout,
                          String stderr, Integer exitStatus, Long runTimeMillis, String generatedFilesJson,
                          String officialPackageJson, Map<String, String> fileIds) {
            this(status, message, timeMillis, memoryKb, stdout, stderr, exitStatus, runTimeMillis,
                    generatedFilesJson, officialPackageJson, fileIds, normalizeResultCode(status), null);
        }

        static RunOutcome from(RunResult result) {
            return from(result, null);
        }

        static RunOutcome from(RunResult result, String generatedFilesJson) {
            return new RunOutcome(
                    result.status(),
                    firstText(result.error(), result.status()),
                    Limits.nanosToMillis(result.time()),
                    Limits.bytesToKb(result.memory()),
                    result.file("stdout"),
                    result.file("stderr"),
                    result.exitStatus(),
                    Limits.nanosToMillis(result.runTime()),
                    generatedFilesJson,
                    null,
                    result.fileIds(),
                    ResultCode.of(result).name(),
                    result.procPeak()
            );
        }

        static RunOutcome fromOfficialPackage(RunResult result, String officialPackageJson) {
            return new RunOutcome(
                    result.status(),
                    firstText(result.error(), result.status()),
                    Limits.nanosToMillis(result.time()),
                    Limits.bytesToKb(result.memory()),
                    result.file("stdout"),
                    result.file("stderr"),
                    result.exitStatus(),
                    Limits.nanosToMillis(result.runTime()),
                    null,
                    officialPackageJson,
                    result.fileIds(),
                    ResultCode.of(result).name(),
                    result.procPeak()
            );
        }

        static RunOutcome fromCompileFailure(CompileOutcome compile) {
            return new RunOutcome("Compile Error", compile.message(), compile.timeMillis(), compile.memoryKb(),
                    null, compile.stderr(), compile.exitStatus(), compile.runTimeMillis(), null, null, Map.of(),
                    "COMPILE_ERROR", compile.procPeak());
        }

        public boolean accepted() {
            return RunResult.ACCEPTED.equals(status);
        }
    }

    static String normalizeResultCode(String status) {
        return ResultCode.of(status, null).name();
    }
}

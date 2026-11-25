"""Run one local B0 pilot, preserving raw data, configuration and diagnostics."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import time
from uuid import uuid4

from check_load import validate

ROOT = Path(__file__).resolve().parents[1]


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def command(arguments, environment=None, check=True):
    result = subprocess.run(arguments, cwd=ROOT, env=environment, capture_output=True, text=True, encoding='utf-8')
    if check and result.returncode:
        raise RuntimeError(f'{arguments}: {result.stderr[-2000:]} {result.stdout[-2000:]}')
    return result


def wait_until(epoch_ms):
    while time.time() * 1000 < epoch_ms:
        time.sleep(min(0.1, max(0, epoch_ms / 1000 - time.time())))


def run_load(directory, duration, run_id, environment):
    arguments = ['k6', 'run', '--quiet', '--log-format', 'raw', '--console-output', str(directory / 'events.jsonl'),
                 '-e', f'DURATION_SECONDS={duration}', '-e', f'RUN_ID={run_id}',
                 '-e', f'SUMMARY_FILE={directory / "summary.json"}', 'experiments/load.js']
    log = (directory / 'k6.log').open('w', encoding='utf-8')
    process = subprocess.Popen(arguments, cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    return process, log


def first_start(directory, process):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        path = directory / 'events.jsonl'
        if path.exists():
            for line in path.read_text(encoding='utf-8').splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if row['type'] == 'start':
                    return row['startEpochMs']
        if process.poll() is not None:
            raise RuntimeError('k6 exited before first request')
        time.sleep(0.05)
    raise TimeoutError('No load starts observed')


def fault(enabled, environment):
    before = int(time.time() * 1000)
    response = command(['docker', 'compose', 'exec', '-T', 'inventory', 'curl', '--fail', '--silent', '-X', 'POST',
                        f'http://localhost:8080/internal/fault?enabled={str(enabled).lower()}'], environment)
    after = int(time.time() * 1000)
    state = json.loads(response.stdout)
    if state['enabled'] != enabled:
        raise ValueError('Unexpected fault state')
    # A server epoch outside the host round trip prevents phase attribution on this host.
    if not before - 20 <= state['appliedEpochMs'] <= after + 20:
        raise ValueError('Inventory epoch differs from host clock')
    return {**state, 'hostBeforeMs': before, 'hostAfterMs': after,
            'clockOffsetLowerMs': state['appliedEpochMs'] - after,
            'clockOffsetUpperMs': state['appliedEpochMs'] - before}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--topology', choices=['shared', 'split'], default='shared')
    parser.add_argument('--normal', type=int, default=30)
    parser.add_argument('--fault', type=int, default=30)
    parser.add_argument('--recovery', type=int, default=30)
    parser.add_argument('--warmup', type=int, default=20)
    parser.add_argument('--allow-dirty', action='store_true', help='Only for functional probes; records working-tree state')
    args = parser.parse_args()
    if min(args.normal, args.fault, args.recovery, args.warmup) < 1:
        parser.error('All phase durations must be positive')
    status = command(['git', 'status', '--porcelain']).stdout
    if status and not args.allow_dirty:
        parser.error('Commit source changes before a measured pilot')
    if command(['docker', 'compose', 'ps', '-q']).stdout.strip():
        parser.error('Stop the existing project stand before starting a new run')

    run_id = time.strftime('%Y%m%d-%H%M%S-') + args.topology + '-' + uuid4().hex[:8]
    directory = ROOT / 'results' / run_id
    directory.mkdir(parents=True)
    duration = args.normal + args.fault + args.recovery
    environment = dict(os.environ, RUN_ID=run_id, INVENTORY_SPLIT_POOLS=str(args.topology == 'split').lower())
    config = {**vars(args), 'runId': run_id, 'mode': 'B0', 'durationSeconds': duration,
              'quotesRps': 50, 'availabilityRps': 15, 'sloMs': {'quotes': 280, 'availability': 100},
              'timeoutMs': 1200, 'drainSeconds': 3,
              'pools': {'shared': [8, 16]} if args.topology == 'shared' else {'batch': [6, 12], 'availability': [2, 4]}}
    save(directory / 'config.json', config)
    manifest = {'commit': command(['git', 'rev-parse', 'HEAD']).stdout.strip(), 'workingTree': status,
                'platform': platform.platform(), 'python': platform.python_version(),
                'k6': command(['k6', 'version']).stdout.strip(),
                'java': command(['java', '-version']).stderr.strip(),
                'docker': command(['docker', 'version']).stdout,
                'compose': command(['docker', 'compose', 'version']).stdout,
                'probe': args.allow_dirty, 'valid': False}
    (directory / 'compose.yaml').write_text(command(['docker', 'compose', 'config'], environment).stdout, encoding='utf-8')
    (directory / 'source.diff').write_text(command(['git', 'diff', 'HEAD']).stdout, encoding='utf-8')
    save(directory / 'manifest.json', manifest)
    process = None
    log = None
    phases = {}
    try:
        startup = command(['docker', 'compose', 'up', '-d', '--build', '--wait', '--wait-timeout', '120'], environment)
        (directory / 'startup.log').write_text(startup.stdout + startup.stderr, encoding='utf-8')
        save(directory / 'containers.json', json.loads(command(['docker', 'inspect'] + [
            command(['docker', 'compose', 'ps', '-q', service], environment).stdout.strip()
            for service in ('gateway', 'checkout', 'inventory')]).stdout))
        fault(False, environment)
        warmup = directory / 'warmup'
        warmup.mkdir()
        process, log = run_load(warmup, args.warmup, run_id + '-warmup', environment)
        code = process.wait(timeout=args.warmup + 30)
        log.close()
        process = None
        if code:
            raise ValueError('Warmup k6 failed')
        save(warmup / 'validation.json', validate(warmup, args.warmup))
        time.sleep(3)
        for service in ('gateway', 'checkout', 'inventory'):
            baseline = command(['docker', 'compose', 'exec', '-T', service, 'tail', '-n', '1',
                                f'/tmp/snapshots/{service}.jsonl'], environment)
            save(directory / f'{service}-before.json', json.loads(baseline.stdout))
        process, log = run_load(directory, duration, run_id, environment)
        phases['loadStartEpochMs'] = first_start(directory, process)
        wait_until(phases['loadStartEpochMs'] + args.normal * 1000)
        phases['faultOn'] = fault(True, environment)
        save(directory / 'phases.json', phases)
        wait_until(phases['loadStartEpochMs'] + (args.normal + args.fault) * 1000)
        phases['faultOff'] = fault(False, environment)
        phases['inputEndEpochMs'] = phases['loadStartEpochMs'] + duration * 1000
        save(directory / 'phases.json', phases)
        code = process.wait(timeout=args.recovery + 30)
        log.close()
        process = None
        if code:
            raise ValueError('Measured k6 run failed; see k6.log')
        save(directory / 'validation.json', validate(directory, duration))
        time.sleep(3)
        manifest['valid'] = True
    except Exception as exception:
        manifest['exclusionReason'] = str(exception)
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        if log is not None:
            log.close()
        diagnostics = []
        for service in ('gateway', 'checkout', 'inventory'):
            result = command(['docker', 'compose', 'cp', f'{service}:/tmp/snapshots/{service}.jsonl',
                              str(directory / f'{service}.jsonl')], environment, check=False)
            if result.returncode:
                diagnostics.append(result.stderr)
        result = command(['docker', 'compose', 'logs', '--no-color'], environment, check=False)
        (directory / 'compose.log').write_text(result.stdout + result.stderr, encoding='utf-8')
        shutdown = command(['docker', 'compose', 'down'], environment, check=False)
        if shutdown.returncode or diagnostics:
            manifest['valid'] = False
            manifest['diagnosticErrors'] = diagnostics + [shutdown.stderr if shutdown.returncode else '']
        manifest['checksums'] = {str(path.relative_to(directory)): hashlib.sha256(path.read_bytes()).hexdigest()
                                 for path in directory.rglob('*') if path.is_file() and path.name != 'manifest.json'}
        save(directory / 'manifest.json', manifest)
        print(directory, flush=True)
    if not manifest['valid']:
        raise RuntimeError('Run archive is incomplete')


if __name__ == '__main__':
    main()

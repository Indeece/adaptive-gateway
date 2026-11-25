"""Validate raw k6 starts, results and independent counters for one constant-rate run."""

import argparse
import json
from collections import Counter
from pathlib import Path


def validate(directory, duration, quotes_rate=50, availability_rate=15):
    events = [json.loads(line) for line in (directory / 'events.jsonl').read_text(encoding='utf-8').splitlines()]
    summary = json.loads((directory / 'summary.json').read_text(encoding='utf-8'))
    starts = [row for row in events if row['type'] == 'start']
    results = [row for row in events if row['type'] == 'result']
    start_ids = [row['requestId'] for row in starts]
    result_ids = [row['requestId'] for row in results]
    if len(set(start_ids)) != len(starts) or Counter(start_ids) != Counter(result_ids):
        raise ValueError('Missing, duplicate or unmatched request events')
    start_by_id = {row['requestId']: row for row in starts}
    for row in results:
        initial = start_by_id[row['requestId']]
        if any(row[field] != initial[field] for field in ('route', 'runId', 'startEpochMs')):
            raise ValueError('Request identity mismatch')
        if row['elapsedMs'] != row['endEpochMs'] - row['startEpochMs'] or row['elapsedMs'] < 0:
            raise ValueError('Invalid request timestamps')
        slo = 280 if row['route'] == 'quotes' else 100
        useful = row['status'] == 200 and row['validBody'] and row['elapsedMs'] <= slo
        if row['useful'] != useful:
            raise ValueError('Incorrect usefulness classification')

    def count(metric):
        return summary['metrics'].get(metric, {}).get('values', {}).get('count', 0)

    errors = []
    if count('dropped_iterations') != 0:
        errors.append('dropped iterations')
    if count('request_starts') != len(starts) or count('request_completions') != len(results):
        errors.append('global counters differ from events')
    if count('iterations') != len(results) or count('http_reqs') != len(results):
        errors.append('incomplete iterations or extra HTTP requests')
    totals = {}
    for route, rate in [('quotes', quotes_rate), ('availability', availability_rate)]:
        planned = duration * rate
        route_starts = sum(row['route'] == route for row in starts)
        route_results = [row for row in results if row['route'] == route]
        if abs(route_starts - planned) > 1 or len(route_results) != route_starts:
            errors.append(f'{route}: actual request count differs from schedule')
        if count(f'{route}_starts') != route_starts or count(f'{route}_completions') != len(route_results):
            errors.append(f'{route}: independent counters differ')
        totals[route] = {'planned': planned, 'started': route_starts, 'completed': len(route_results),
                         'scheduleBoundaryDifference': route_starts - planned,
                         'useful': sum(row['useful'] for row in route_results)}
    if errors:
        raise ValueError('; '.join(errors))
    return {'valid': True, 'droppedIterations': count('dropped_iterations'), 'routes': totals}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory', type=Path)
    parser.add_argument('--duration', type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(validate(args.directory, args.duration), indent=2))

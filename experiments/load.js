import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const durationSeconds = Number(__ENV.DURATION_SECONDS || 10);
const quotesRate = Number(__ENV.QUOTES_RPS || 50);
const availabilityRate = Number(__ENV.AVAILABILITY_RPS || 15);
const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const runId = __ENV.RUN_ID || 'manual';
const started = new Counter('request_starts');
const completed = new Counter('request_completions');
const startsByRoute = {
    quotes: new Counter('quotes_starts'),
    availability: new Counter('availability_starts'),
};
const completionsByRoute = {
    quotes: new Counter('quotes_completions'),
    availability: new Counter('availability_completions'),
};

function scenario(route, rate) {
    return {
        executor: 'constant-arrival-rate',
        exec: route,
        rate,
        timeUnit: '1s',
        duration: `${durationSeconds}s`,
        preAllocatedVUs: Math.ceil(rate * 1.2) + 10,
        maxVUs: Math.ceil(rate * 1.2) + 10,
        gracefulStop: '3s',
    };
}

export const options = {
    scenarios: {
        quotes: scenario('quotes', quotesRate),
        availability: scenario('availability', availabilityRate),
    },
    thresholds: { dropped_iterations: ['count==0'] },
    maxRedirects: 0,
};

export function setup() {
    console.log(JSON.stringify({ type: 'loadStart', runId, epochMs: Date.now(), durationSeconds }));
}

function request(route) {
    const requestId = `${route}-${exec.scenario.iterationInTest}`;
    const startEpochMs = Date.now();
    started.add(1);
    startsByRoute[route].add(1);
    console.log(JSON.stringify({ type: 'start', runId, route, requestId, startEpochMs }));
    const response = http.get(`${baseUrl}/${route}/SKU-001`, {
        timeout: '1200ms', tags: { route, name: `/${route}/{sku}` },
    });
    const endEpochMs = Date.now();
    const elapsedMs = endEpochMs - startEpochMs;
    let validBody = false;
    let outcome = response.status === 0 ? 'client_transport' : `http_${response.status}`;
    try {
        const body = response.json();
        if (response.status === 200) {
            validBody = body.sku === 'SKU-001' && body.available === true;
            validBody = validBody && (route === 'quotes'
                ? body.price === 100 && body.currency === 'RUB'
                : body.quantity === 10);
            outcome = validBody ? 'success' : 'invalid_body';
        } else if (typeof body.code === 'string') {
            outcome = body.code;
        }
    } catch (_) {
        if (response.status === 200) outcome = 'invalid_body';
    }
    completed.add(1);
    completionsByRoute[route].add(1);
    console.log(JSON.stringify({
        type: 'result', runId, mode: 'B0', route, requestId,
        startEpochMs, endEpochMs, elapsedMs, status: response.status,
        outcome, validBody,
        useful: response.status === 200 && validBody && elapsedMs <= (route === 'quotes' ? 280 : 100),
    }));
}

export function quotes() { request('quotes'); }
export function availability() { request('availability'); }

export function handleSummary(data) {
    return { [__ENV.SUMMARY_FILE || 'summary.json']: JSON.stringify(data, null, 2) };
}

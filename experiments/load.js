import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const timeout = Number(__ENV.REQUEST_TIMEOUT_MS);
const faultOn = Number(__ENV.WARMUP_SECONDS) + Number(__ENV.NORMAL_SECONDS);
const faultOff = faultOn + Number(__ENV.FAULT_SECONDS);
const duration = faultOff + Number(__ENV.RECOVERY_SECONDS);
const timeoutOption = `${timeout}ms`;
const starts = new Counter('request_starts');
const completions = new Counter('request_completions');
const useful = new Counter('useful_responses');
const errors = new Counter('response_errors');

function load(rate) {
    const virtualUsers = Math.ceil(rate * timeout / 1000) + 10;
    return {
        executor: 'constant-arrival-rate',
        rate, timeUnit: '1s', duration: `${duration}s`,
        preAllocatedVUs: virtualUsers, maxVUs: virtualUsers,
        gracefulStop: `${__ENV.DRAIN_SECONDS}s`,
    };
}

function switchAt(seconds, enabled) {
    return {
        executor: 'per-vu-iterations', exec: 'switchFault',
        vus: 1, iterations: 1, startTime: `${seconds}s`,
        maxDuration: '4s', gracefulStop: '0s',
        env: { FAULT_ENABLED: String(enabled) },
    };
}

export const options = {
    scenarios: {
        quotes: load(Number(__ENV.QUOTES_RPS)),
        availability: load(Number(__ENV.AVAILABILITY_RPS)),
        slow_batch: switchAt(faultOn, true),
        restore_batch: switchAt(faultOff, false),
    },
    maxRedirects: 0,
    systemTags: ['scenario'],
    thresholds: { dropped_iterations: ['count==0'] },
};

function applyFault(enabled) {
    const url = `${__ENV.INVENTORY_URL}/internal/fault?enabled=${enabled}`;
    const response = http.post(url, null, { timeout: '3s', tags: { name: 'fault control' } });
    if (response.status !== 200 || response.json('enabled') !== enabled) {
        exec.test.abort(`Inventory could not apply fault=${enabled} (HTTP ${response.status})`);
    }
    console.log(response.body);
}

export function setup() {
    applyFault(false);
}

export function switchFault() {
    applyFault(__ENV.FAULT_ENABLED === 'true');
}

export function teardown() {
    applyFault(false);
}

export default function () {
    const route = exec.scenario.name;
    const isQuote = route === 'quotes';
    const slo = Number(isQuote ? __ENV.QUOTES_SLO_MS : __ENV.AVAILABILITY_SLO_MS);
    const tags = { route, name: `/${route}/{sku}` };
    const startedAt = Date.now();
    starts.add(1, tags);
    const response = http.get(`${__ENV.GATEWAY_URL}/${route}/SKU-001`, { timeout: timeoutOption, tags });
    const elapsed = Date.now() - startedAt;
    let body;
    try {
        body = response.json();
    } catch {
        body = null;
    }
    const valid = body?.sku === 'SKU-001' && body.available === true &&
        (isQuote ? body.price === 100 && body.currency === 'RUB' : body.quantity === 10);
    const onTime = response.status === 200 && valid && elapsed <= slo;
    completions.add(1, tags);
    useful.add(onTime ? 1 : 0, tags);
    if (!onTime) {
        let outcome = response.status === 200 ? 'late_or_invalid_body' : `http_${response.status}`;
        if (typeof body?.code === 'string') outcome = body.code;
        errors.add(1, { ...tags, outcome });
    }
}

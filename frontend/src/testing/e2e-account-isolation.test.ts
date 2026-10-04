import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { workflowAccountName } from '../../e2e/workflow-account-name';

const e2e = new URL('../../e2e/', import.meta.url);
const files = readdirSync(e2e).filter(file => file.endsWith('.spec.ts'));
const source = (file: string) => readFileSync(new URL(file, e2e), 'utf8');
const workflows = files.filter(file => source(file).includes('signInWorkflow') || source(file).includes("from './viewer-fixture'"));
const seed = readFileSync(new URL('../../../backend/src/test/java/com/pis/security/testfixture/E2eFixtureConfiguration.java', import.meta.url), 'utf8');

describe('disposable E2E account isolation', () => {
  it('seeds exactly the workflow files, with unique owner/receiver identities', () => {
    const seeded = [...(seed.match(/WORKFLOW_SCENARIOS=java.util.List.of\(([^;]+)\);/)?.[1] ?? '').matchAll(/"([a-z]+)"/g)].map(match => `${match[1]}.spec.ts`);
    expect(seeded.sort()).toEqual([...workflows].sort());
    const names = workflows.flatMap(file => ['synthetic.workflow', 'synthetic.technician'].map(prefix => workflowAccountName(file, prefix)));
    expect(new Set(names).size).toBe(workflows.length * 2);
    for (const file of workflows) {
      expect(source(file)).not.toContain("PIS_E2E_HANDOFF_USERNAME");
      expect(source(file)).not.toContain("'synthetic.technician'");
    }
  });
  it('isolates RGB, results and human decisions from each others per-user resource budgets', () => {
    for(const name of ['viewer','results','decisions'])expect(workflows).toContain(`${name}.spec.ts`);
    expect(new Set(['viewer','results','decisions'].map(name=>workflowAccountName(`${name}.spec.ts`,'synthetic.workflow'))).size).toBe(3);
    expect(workflowAccountName('viewer.spec.ts','synthetic.workflow')).not.toBe(workflowAccountName('results.spec.ts','synthetic.workflow'));
    expect(seed).toContain('java.util.Set.of("viewer","results","decisions").contains(scenario)');
  });
  it('uses stable names across platforms, supports prefixes and rejects ambiguous names', () => {
    expect(workflowAccountName('/runner/e2e/archive.spec.ts', 'synthetic.workflow')).toBe('synthetic.workflow.archive');
    expect(workflowAccountName('C:\\e2e\\archive.spec.ts', 'custom')).toBe('custom.archive');
    expect(() => workflowAccountName('bad.ts', 'custom')).toThrow();
    expect(() => workflowAccountName('archive.spec.ts', 'x'.repeat(64))).toThrow();
  });
  it('requires budget review before any file or the shared IP approaches its unchanged limit', () => {
    // Conservative suite guard: up to two real logins per test, plus amendment's extra reviewer session.
    // Authentication/CSRF error cases remain on their own reader/disabled identities.
    let total = 1;
    for (const file of files) {
      const tests = [...source(file).matchAll(/^test\(/gm)].length;
      expect(tests, `${file}: split fixtures before reaching the 20-login account ceiling`).toBeLessThan(20);
      total += tests * 2;
    }
    expect(total, 'Review session reuse before reaching the unchanged 100-login IP ceiling').toBeLessThan(100);
  });
});

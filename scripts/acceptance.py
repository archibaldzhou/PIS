#!/usr/bin/env python3
"""T42 evidence integrity only. Does not execute or claim clinical/HTTP acceptance."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = '5be3b823a68dbddb2d8962b53492afe618f82f82'
REQUIRED_STEPS = [
    'Backend test and package', 'Bounded synthetic viewer provider validation',
    'Isolated synthetic backup and recovery', 'Frontend install', 'Frontend unit tests',
    'Frontend lint', 'Frontend typecheck and build', 'Local release and proxy contracts',
    'Packaged application deployment and rollback rehearsal', 'Install test browser',
    'Browser integration tests', 'Built dist real service browser contracts',
    'Built dist synthetic UI contracts', 'Synthetic browser UI contracts',
    'Audit all frontend dependencies, including development tools',
]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file(path):
    p = ROOT / path
    require(not Path(path).is_absolute() and '..' not in Path(path).parts,
            'Reference must be repository relative')
    require(p.is_file() and not p.is_symlink(), 'Missing/non-file reference: ' + path)
    return p


def digest(path):
    data = path.read_bytes()
    # .gitattributes explicitly checks this Windows wrapper out as CRLF; git blob is LF.
    # Normalize only that documented path; every other byte is checked verbatim.
    if path.relative_to(ROOT).as_posix() == 'backend/mvnw.cmd':
        data = data.replace(b'\r\n', b'\n')
    return hashlib.sha256(data).hexdigest()


def render(matrix):
    lines = ['# T01–T42 要求、实现、测试与限制矩阵', '',
             '由 `python3 scripts/acceptance.py --render` 从显式 matrix.json 生成；校验不推断功能正确。', '',
             '基线完整 CI：[' + BASE + '](https://github.com/archibaldzhou/PIS/actions/runs/37196371021)，父会话核验成功。', '',
             matrix['sourceLimitation'], '',
             '各行“已验证”仅限对应合成开发范围。原型映射是语义子集，未逐像素复刻；无医院/临床批准。', '',
             '证据等级和本地/CI执行差异见 [验收报告](report.md)。原型所有47页含未实现项见 [原型索引](prototype-map.json)。', '']
    def links(paths):
        return '、'.join(f'[{p}](../../{p})' for p in paths)
    for r in matrix['tasks']:
        lines += [f"## {r['id']} {r['requirement']}", '',
                  f"- 状态：{r['status']}", f"- 开发依据：{links([r['prd']])}",
                  f"- 实现：{links(r['implementation'])}",
                  f"- 迁移：{links(r['migrations']) or '无新增；保留既有基线'}",
                  f"- 原型：{', '.join(r['prototype']) or '基础工程，无独立页面'}",
                  f"- 回归入口：{links(r['tests'])}",
                  f"- 后端断言定位：{', '.join('`' + x['method'] + '`' for x in r['testMethods']) or '见上述专用检查入口'}",
                  f"- 证据：{links(r['evidence'])}", f"- 限制：{r['limitations']}", '']
    return '\n'.join(lines).rstrip() + '\n'


def check():
    matrix = json.loads(file('docs/acceptance/matrix.json').read_text())
    baseline = json.loads(file('docs/acceptance/baseline-evidence.json').read_text())
    require(matrix['baseline'] == baseline['revision'] == BASE, 'Explicit baseline changed')
    require(matrix['taskCount'] == 42 and [r['id'] for r in matrix['tasks']] ==
            [f'T{i:02}' for i in range(1, 43)], 'Exactly ordered T01–T42 required')
    for r in matrix['tasks']:
        require(all(r.get(k) for k in ['requirement', 'status', 'prd', 'implementation', 'tests', 'evidence', 'limitations']),
                'Incomplete traceability: ' + r['id'])
        for p in [r['prd']] + sum([r[k] for k in ['implementation', 'migrations', 'tests', 'evidence']], []):
            file(p)
        for anchor in r['testMethods']:
            require(re.search(r'void\s+' + re.escape(anchor['method']) + r'\s*\(', file(anchor['file']).read_text()),
                    'Missing test method: ' + anchor['method'])
    migrations = list((ROOT / 'backend/src/main/resources/db/migration').glob('*.sql'))
    require(sorted(int(re.fullmatch(r'V(\d+)__.+\.sql', p.name)[1]) for p in migrations) == list(range(1, 38)),
            'Explicit V1–V37 migration contract changed; review required')
    require({p for r in matrix['tasks'] for p in r['migrations']} ==
            {str(p.relative_to(ROOT)) for p in migrations}, 'Unmapped migration')
    delta = baseline['reviewedDelta']
    require(set(delta) == {'frontend/src/App.tsx', 'frontend/dist-tests/contract.spec.ts'},
            'Only the explicit T42 notice fix may differ; review required')
    for p, values in delta.items():
        require(digest(file(p)) == values['currentSha256'], 'Unreviewed delta: ' + p)
    for p, sha in baseline['referenceSha256'].items():
        # Historical documentation and the intentionally extended CI are not runtime evidence.
        if p.startswith(('backend/', 'frontend/', 'scripts/')):
            require(digest(file(p)) == (delta[p]['currentSha256'] if p in delta else sha), 'Baseline runtime evidence stale: ' + p)
    tracked = subprocess.check_output(['git', 'ls-files'], cwd=ROOT, text=True).splitlines()
    runtime = sorted(p for p in tracked if p.startswith(tuple(baseline['runtimePrefixes'])) or p in baseline['runtimeFiles'])
    sha = hashlib.sha256()
    for p in runtime:
        sha.update((p + '\0' + (delta[p]['baselineSha256'] if p in delta else digest(file(p))) + '\n').encode())
    require(len(runtime) == baseline['runtimeTreeFiles'] and sha.hexdigest() == baseline['runtimeTreeSha256'],
            'Runtime/test tree changed: previous CI cannot substitute for new verification')
    screens = json.loads(file('docs/acceptance/prototype-map.json').read_text())['screens']
    require(len(screens) == 47 and {s['id'] for s in screens} == {f'UI-{i:03}' for i in range(1, 48)}, 'Prototype index incomplete')
    for s in screens:
        require(s['tasks'] == [r['id'] for r in matrix['tasks'] if s['id'] in r['prototype']], 'Prototype mapping drift')
        require(re.fullmatch('[a-f0-9]{64}', s['sha256']), 'Prototype digest missing')
    workflow = file('.github/workflows/ci.yml').read_text()
    for step in REQUIRED_STEPS:
        require('name: ' + step in workflow, 'Required CI step missing: ' + step)
    require('timeout-minutes: 30' in workflow and 'validation/t42' in workflow, 'CI budget/branch missing')
    require('permissions:\n  contents: read' in workflow, 'CI permission contract changed')
    require(not re.search(r'continue-on-error:\s*true', workflow), 'CI error suppression forbidden')
    text = render(matrix)
    target = ROOT / 'docs/acceptance/matrix.md'
    if sys.argv[1:] == ['--render']:
        target.write_text(text)
    else:
        require(not sys.argv[1:], 'Only --render supported')
        require(target.read_text() == text, 'Matrix Markdown stale; run --render')
    for p in [target, file('docs/acceptance/report.md'), file('docs/runbooks/t42-synthetic-demo.md')]:
        for ref in re.findall(r'\]\(([^)]+)\)', p.read_text()):
            if '://' not in ref and not ref.startswith('#'):
                require((p.parent / ref.split('#')[0]).exists(), 'Broken document link: ' + ref)
    print('PASS: explicit 42 tasks; V1–V37; 47 prototype mappings; ' + str(len(runtime)) +
          ' baseline runtime/test files (559 unchanged, 2 explicit notice-fix deltas awaiting new CI); referenced evidence and required CI steps. Not a runtime test.')


if __name__ == '__main__':
    check()

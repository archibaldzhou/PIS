"""Reproduce the source-level workflow graph without Maven; not a bytecode/JUnit check."""
from pathlib import Path
import argparse
import re
import subprocess

root = Path(__file__).resolve().parents[4]
parser = argparse.ArgumentParser()
parser.add_argument('--revision', help='Read a local committed tree, without checking it out')
parser.add_argument('--expect-cycle', action='store_true')
args = parser.parse_args()
domains = ['accession', 'grossing', 'processing', 'specimen', 'label', 'material',
           'quality', 'worklist', 'diagnosis', 'report', 'integration', 'frozen', 'archive']
pattern = re.compile(r'com\.pis\.(' + '|'.join(domains) + r')\.([A-Z][A-Za-z0-9]*|\*)')
edges = {domain: set() for domain in domains}
evidence = {}
for domain in domains:
    prefix = f'backend/src/main/java/com/pis/{domain}'
    if args.revision:
        files = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', args.revision, '--', prefix], cwd=root, text=True).splitlines()
    else:
        files = [str(p.relative_to(root)) for p in (root / prefix).rglob('*.java')]
    for name in sorted(files):
        if not name.endswith('.java'):
            continue
        content = subprocess.check_output(['git', 'show', f'{args.revision}:{name}'], cwd=root, text=True) if args.revision else (root / name).read_text()
        for match in pattern.finditer(content):
            other, symbol = match.groups()
            if other == domain:
                continue
            edges[domain].add(other)
            evidence.setdefault((domain, other), set()).add(f'{name}: {match[0]}')
            if domain == 'processing':
                assert other + '.' + symbol in ['accession.RequestService', 'accession.WorkflowAccess', 'grossing.GrossService', 'quality.QualityGate'], name

def visit(domain, path):
    if domain in path:
        return path[path.index(domain):] + [domain]
    for target in sorted(edges[domain]):
        cycle = visit(target, path + [domain])
        if cycle:
            return cycle
    return None

cycle = next((found for domain in domains if (found := visit(domain, []))), None)
if cycle:
    print('CYCLE: ' + ' -> '.join(cycle))
    for a, b in zip(cycle, cycle[1:]):
        for item in sorted(evidence[(a, b)]):
            print(f'  {a} -> {b}: {item}')
else:
    print('PASS: workflow source graph is acyclic, including wildcard imports')
    for domain in domains:
        print(domain + ' -> ' + ', '.join(sorted(edges[domain])))
assert bool(cycle) == args.expect_cycle, 'Unexpected source architecture result'
print('Source check only; full type compilation, Spring wiring and HTTP require CI')

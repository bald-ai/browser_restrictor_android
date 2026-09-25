"""Exact compiler-source probes supplement functions JaCoCo cannot distinguish.
An unresolved function never borrows another callable's hit. Generated conditions
without a source range are retained in raw evidence but are not handwritten edges.
"""
import pathlib


def read_probes(manifest_path, events_path, root):
    probes = []
    for line in pathlib.Path(manifest_path).read_text().splitlines():
        identity, file, name, start, end, body_start, body_end, origin, kind = line.split('\t')
        probes.append(dict(id=identity, file=str(pathlib.Path(file).relative_to(root)), name=name,
                           start=int(start), end=int(end), bodyStart=int(body_start), bodyEnd=int(body_end),
                           origin=origin, kind=kind))
    events = set(pathlib.Path(events_path).read_text().splitlines()) - {''}
    valid = {event for p in probes for event in
             ([p['id']] if p['kind'] == 'entry' else [p['id'] + ':true', p['id'] + ':false'])}
    if events - valid:
        raise ValueError('Observed probe IDs are absent from this compilation manifest; rebuild without incremental compilation.')
    return probes, events


def callable_name(name):
    if name == 'KtFunctionLiteral':
        return '<anonymous>'
    if name in ('KtPrimaryConstructor', 'KtSecondaryConstructor'):
        return '<init>'
    if name.startswith(('get:', 'set:')):
        return '<' + name.replace(':', '-') + '>'
    return name


def exact_probe_coverage(row, probes, events):
    candidates = [p for p in probes if p['kind'] == 'entry' and p['file'] == row['file']
                  and p['name'] == callable_name(row['name'])
                  and row['startOffset'] <= p['start'] <= row['endOffset']
                  and p['end'] == row['endOffset']]
    if len(candidates) != 1:
        return None
    entry = candidates[0]
    prefix = entry['id'].removesuffix(':entry') + ':condition:'
    conditions = [p for p in probes if p['kind'] == 'condition' and p['id'].startswith(prefix)
                  and row['startOffset'] <= p['bodyStart'] <= p['bodyEnd'] <= row['endOffset']]
    ids = [p['id'] + suffix for p in conditions for suffix in [':true', ':false']]
    if ids:
        basis = 'kotlin-ir-source-branch'
    else:
        # Exact body invocation, not inferred coverage from a caller's shared source line.
        ids = [entry['id']]
        basis = 'kotlin-ir-function-entry'
    return dict(coverageBasis=basis, covered=sum(identity in events for identity in ids),
                total=len(ids), coverageProbeIds=ids, coverageProbeEntry=entry)


def source_branch_coverage(probes, events, function_coverages):
    """Aggregate exact source conditions, retaining a separate ownership audit.

    Negative/out-of-owner source ranges are compiler scaffolding, not executable
    source expressions. No handwritten condition is removed for being uncovered.
    """
    conditions = [p for p in probes if p['kind'] == 'condition'
                  and 0 <= p['start'] <= p['bodyStart'] <= p['bodyEnd'] <= p['end']]
    identities = {p['id'] + suffix for p in conditions for suffix in (':true', ':false')}
    if not identities:
        raise ValueError('No handwritten branch instrumentation is available.')
    owned = {identity for coverage in function_coverages if coverage
             and coverage['coverageBasis'] == 'kotlin-ir-source-branch'
             for identity in coverage['coverageProbeIds']}
    covered = len(identities & events)
    return dict(covered=covered, missed=len(identities) - covered, total=len(identities),
                pct=100 * covered / len(identities), basis='kotlin-ir-source-branch',
                unownedProbeIds=sorted(identities - owned))

"""Keep non-executable contracts visible without borrowing implementation scores."""

CRAP_LIMIT = 10


def read_kotlin_function(line):
    file, name, start, end, first, last, complexity, kind = line.split('\t')
    if kind not in ('executable', 'abstract-declaration'):
        raise ValueError(f'Unknown function classification: {kind}')
    return dict(component='android', file=file, name=name,
                startOffset=int(start), endOffset=int(end), startLine=int(first), endLine=int(last),
                classification=kind, complexity=None if kind == 'abstract-declaration' else int(complexity))


def summarize_functions(functions):
    executable = [f for f in functions if f.get('classification') != 'abstract-declaration']
    return dict(functions=len(functions), executableFunctions=len(executable),
                nonExecutableDeclarations=len(functions) - len(executable),
                unmappedFunctions=sum(f['crap'] is None for f in executable),
                maxComplexity=max((f['complexity'] for f in executable), default=0),
                complexityAboveLimit=sum(f['complexity'] > CRAP_LIMIT for f in executable),
                maxCrap=max((f['crap'] or 0 for f in executable), default=0),
                crapAboveLimit=sum(f['crap'] is not None and f['crap'] > CRAP_LIMIT for f in executable))

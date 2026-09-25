"""Keep non-executable contracts visible without borrowing implementation scores."""


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
                complexityAbove8=sum(f['complexity'] > 8 for f in executable),
                maxCrap=max((f['crap'] or 0 for f in executable), default=0),
                crapAbove8=sum(f['crap'] is not None and f['crap'] > 8 for f in executable))

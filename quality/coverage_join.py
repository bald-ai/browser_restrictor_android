"""Conservative fallback from Kotlin PSI functions to concrete JaCoCo methods.
Never join by line alone: require callable kind, source filename, a unique method,
and a unique leaf source function. Inlined lambdas without methods stay unmapped.
"""
def nested_functions(row, rows):
    return [other for other in rows if other['file'] == row['file']
            and row['startOffset'] < other['startOffset']
            and other['endOffset'] <= row['endOffset']]

def compatible(row, method):
    if row['file'] != method['file'] or not row['startLine'] <= method['line'] <= row['endLine']:
        return False
    if row['name'] == 'KtFunctionLiteral':
        if '$lambda$' in method['name']:
            return True
        # The actual compiled superclass distinguishes source lambdas from
        # generated ContinuationImpl state machines of named suspend functions.
        return (method['name'] == 'invokeSuspend' and method.get('superName') == 'kotlin/coroutines/jvm/internal/SuspendLambda'
                or method['name'] == 'invoke' and method.get('superName') == 'kotlin/jvm/internal/Lambda')
    if row['name'].startswith(('get:', 'set:')):
        kind, name = row['name'].split(':', 1)
        capitalized = name[0].upper() + name[1:]
        expected = {kind + capitalized}
        if name.startswith('is') and len(name) > 2 and name[2].isupper():
            expected.add(name if kind == 'get' else 'set' + name[2:])
        return method['name'] in expected
    if row['name'].startswith('Kt'):
        return False  # Generic accessor/constructor names do not identify a JVM method.
    return method['name'] == row['name'] or method['name'].endswith('$' + row['name'])

def unique_method(row, rows, methods):
    if nested_functions(row, rows):
        return None  # An inlined child would contaminate the parent's counters.
    candidates = [method for method in methods if compatible(row, method)
                  and sum(method['counters'].get('LINE', {}).values()) > 0]
    if len(candidates) != 1:
        return None
    method = candidates[0]
    owners = [other for other in rows if compatible(other, method) and not nested_functions(other, rows)]
    return method if len(owners) == 1 else None

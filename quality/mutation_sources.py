"""Resolve historical mutation target names without changing saved trials."""
from pathlib import PurePosixPath

ANDROID = 'app/src/main/java/com/michalkrsik/internetrestrictor/'

def mutation_source(report, mutation, inventory):
    if report.get('sourceFile'):
        return report['sourceFile']
    if mutation.get('file'):
        return 'backend-v1/' + mutation['file']
    scope = report.get('scope', '').split(' with ')[0]
    if scope.endswith('.kt'):
        return ANDROID + scope
    # Early records named the Kotlin class in prose instead of storing a path.
    # Require exactly one matching production source file; never invent a path.
    name = scope.split(' ', 1)[0]
    candidates = [path for path in inventory if path.startswith(ANDROID)
                  and PurePosixPath(path).stem == name]
    return candidates[0] if len(candidates) == 1 else None

"""Recognize assertion failures without counting crashes as mutation detections."""


def is_assertion_failure(failure):
    kind = failure.get('type', '')
    return 'Assertion' in kind or kind in {
        'org.junit.ComparisonFailure',
        'junit.framework.ComparisonFailure',
    }
